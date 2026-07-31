package org.wyrdsekai.between;

import com.fasterxml.jackson.databind.JsonNode;
import io.nats.client.*;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * NATS connection wrapper with typed pub/sub for Between messages.
 * Handles reconnection automatically via jnats.
 */
public final class NatsBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NatsBridge.class);

    private final String natsUrl;
    private final String nodeId;
    private final String zoneId;
    private final NodeIdentity identity;
    private final String authUser;
    private final String authPassword;
    private Connection connection;
    private final ConcurrentHashMap<String, Consumer<BetweenEnvelope>> handlers = new ConcurrentHashMap<>();
    private volatile Dispatcher sharedDispatcher;

    public NatsBridge(String natsUrl, String nodeId, String zoneId, NodeIdentity identity) {
        this(natsUrl, nodeId, zoneId, identity, null, null);
    }

    /**
     * Create a NatsBridge with optional user/password authentication (used for relay connections).
     */
    public NatsBridge(String natsUrl, String nodeId, String zoneId, NodeIdentity identity,
                      String authUser, String authPassword) {
        this.natsUrl = natsUrl;
        this.nodeId = nodeId;
        this.zoneId = zoneId;
        this.identity = identity;
        this.authUser = authUser;
        this.authPassword = authPassword;
    }

    /**
     * Connect to the NATS server.
     */
    public void connect() throws IOException, InterruptedException {
        // Ensure IPv4 for dual-homed networks (macOS IPv6 dual-stack causes NATS failures)
        System.setProperty("java.net.preferIPv4Stack", "true");

        var builder = new Options.Builder()
            .server(natsUrl)
            .connectionName("wyrd-" + nodeId.substring(0, 8))
            .connectionTimeout(Duration.ofSeconds(10))  // longer for dual-homed networks
            .maxReconnects(-1)      // infinite reconnects
            .reconnectWait(Duration.ofSeconds(2))
            // Aggressive keepalive — prevents connection drops on WiFi/dual-homed.
            // On macOS, once the NATS connection drops, the JVM's routing gets corrupted
            // by Pekko and reconnects fail with NoRouteToHost. Keep the connection alive.
            .pingInterval(Duration.ofSeconds(10))        // ping every 10s (default 120s)
            .maxPingsOut(5)                              // allow 5 outstanding (default 2)
            // Use InterfaceAwareDataPort for multi-homed machines (macOS dual-network fix).
            .dataPortType("io.nats.client.impl.InterfaceAwareDataPort");

        // Apply user/password auth if provided (used for relay connections)
        if (authUser != null && !authUser.isEmpty()) {
            builder.userInfo(authUser, authPassword != null ? authPassword : "");
        }

        var options = builder
            .connectionListener((conn, type) -> {
                switch (type) {
                    case CONNECTED -> log.info("NATS connected to {}", natsUrl);
                    case RECONNECTED -> log.info("NATS reconnected");
                    case DISCONNECTED -> log.warn("NATS disconnected");
                    case CLOSED -> log.info("NATS connection closed");
                    default -> log.debug("NATS event: {}", type);
                }
            })
            .errorListener(new ErrorListener() {
                @Override
                public void errorOccurred(Connection conn, String error) {
                    log.error("NATS error: {}", error);
                }
                @Override
                public void exceptionOccurred(Connection conn, Exception exp) {
                    log.error("NATS exception", exp);
                }
                @Override
                public void slowConsumerDetected(Connection conn, io.nats.client.Consumer consumer) {
                    log.warn("NATS slow consumer detected");
                }
            })
            .build();

        connection = Nats.connect(options);
        log.info("NATS bridge established — node {} in zone {}", nodeId, zoneId);
    }

    /** Get the raw NATS connection (for low-level subscriptions). */
    public Connection rawConnection() {
        return connection;
    }

    /** Publish raw bytes to a subject. */
    public void publish(String subject, byte[] data) {
        if (connection == null || connection.getStatus() != Connection.Status.CONNECTED) return;
        connection.publish(subject, data);
    }

    /**
     * Publish a signed envelope to a subject.
     */
    public void publish(String subject, BetweenEnvelope envelope) {
        if (connection == null || connection.getStatus() != Connection.Status.CONNECTED) {
            log.warn("Cannot publish — NATS not connected");
            return;
        }
        connection.publish(subject, envelope.toBytes());
    }

    /**
     * Publish a broadcast message (no specific destination).
     */
    public void broadcast(String layer, String topic, JsonNode payload) {
        var subject = subjectBroadcast(layer, topic);
        var envelope = BetweenEnvelope.create(nodeId, null, payload, identity);
        publish(subject, envelope);
    }

    /**
     * Publish a directed message to a specific node.
     */
    public void send(String targetNodeId, String layer, String topic,
                     JsonNode payload) {
        var subject = subjectDirected(targetNodeId, layer, topic);
        var envelope = BetweenEnvelope.create(nodeId, targetNodeId, payload, identity);
        publish(subject, envelope);
    }

    /**
     * Subscribe to broadcast messages on a layer.topic.
     */
    public void subscribeBroadcast(String layer, String topic,
                                    Consumer<BetweenEnvelope> handler) {
        var subject = "between." + zoneId + ".*.*.cluster".replace("cluster", layer)
            .replaceFirst("\\*\\.\\*\\..*", "*." + layer + "." + topic);
        // Simplified: subscribe to between.{zone}.*.*.{layer}.{topic}
        var pattern = "between." + zoneId + ".*.*." + layer + "." + topic;
        subscribe(pattern, handler);
    }

    /**
     * Subscribe to directed messages for this node on a layer.topic.
     */
    public void subscribeDirected(String layer, String topic,
                                   Consumer<BetweenEnvelope> handler) {
        var pattern = "between." + zoneId + ".*." + nodeId + "." + layer + "." + topic;
        subscribe(pattern, handler);
    }

    /**
     * Subscribe to all messages on a layer (broadcast + directed to this node).
     */
    public void subscribeLayer(String layer, Consumer<BetweenEnvelope> handler) {
        // All broadcasts
        var broadcastPattern = "between." + zoneId + ".*.>"; // too broad, narrow it
        // Subscribe to both patterns
        subscribe("between." + zoneId + ".*.*." + layer + ".>", handler);
    }

    /**
     * Subscribe to a NATS subject pattern with an envelope handler.
     * Uses a single shared dispatcher (one thread) for all subscriptions
     * to avoid creating many threads during startup — rapid thread creation
     * triggers WiFi-level packet drops on dual-homed macOS machines.
     */
    public void subscribe(String subject, Consumer<BetweenEnvelope> handler) {
        if (connection == null) {
            log.warn("Cannot subscribe — NATS not connected");
            return;
        }

        handlers.put(subject, handler);

        if (sharedDispatcher == null) {
            sharedDispatcher = connection.createDispatcher(msg -> {
                // Route to the handler registered for this subject.
                // NATS delivers based on subscription, so we match by subject.
                var matchedHandler = findHandler(msg.getSubject());
                if (matchedHandler == null) return;
                try {
                    var envelope = BetweenEnvelope.fromBytes(msg.getData());
                    if (nodeId.equals(envelope.src())) return;
                    matchedHandler.accept(envelope);
                } catch (Exception e) {
                    log.error("Error processing NATS message on {}: {}", msg.getSubject(), e.getMessage());
                }
            });
        }
        sharedDispatcher.subscribe(subject);

        log.debug("Subscribed to {}", subject);
    }

    /**
     * Find the handler for a subject by matching registered patterns.
     * NATS subjects use . as delimiter and > as wildcard suffix.
     */
    private Consumer<BetweenEnvelope> findHandler(String subject) {
        // Exact match first
        var h = handlers.get(subject);
        if (h != null) return h;

        // Pattern match: try progressively shorter prefixes with > wildcard
        for (var entry : handlers.entrySet()) {
            var pattern = entry.getKey();
            if (subjectMatches(subject, pattern)) return entry.getValue();
        }
        return null;
    }

    /**
     * Match a NATS subject against a pattern (supports * and > wildcards).
     */
    private static boolean subjectMatches(String subject, String pattern) {
        var subParts = subject.split("\\.");
        var patParts = pattern.split("\\.");
        for (int i = 0; i < patParts.length; i++) {
            if (">".equals(patParts[i])) return true; // > matches rest
            if (i >= subParts.length) return false;
            if (!"*".equals(patParts[i]) && !patParts[i].equals(subParts[i])) return false;
        }
        return subParts.length == patParts.length;
    }

    /**
     * Subscribe to raw byte messages on a subject (no envelope parsing).
     * Used for cross-zone session proxying where messages are plain JSON.
     * Returns a subscription token (the subject) for unsubscription.
     */
    public String subscribeRaw(String subject, Consumer<byte[]> handler) {
        if (connection == null) {
            log.warn("Cannot subscribe raw — NATS not connected");
            return null;
        }
        var dispatcher = connection.createDispatcher(msg -> {
            try {
                handler.accept(msg.getData());
            } catch (Exception e) {
                log.error("Error processing raw NATS message on {}: {}", msg.getSubject(), e.getMessage());
            }
        });
        dispatcher.subscribe(subject);
        log.debug("Subscribed raw to {}", subject);
        return subject;
    }

    /**
     * Publish raw bytes to a subject (no envelope wrapping).
     * Used for cross-zone session messages where plain JSON is sufficient.
     */
    public void publishRaw(String subject, byte[] data) {
        if (connection == null || connection.getStatus() != Connection.Status.CONNECTED) {
            log.warn("Cannot publish raw — NATS not connected");
            return;
        }
        connection.publish(subject, data);
        try { connection.flush(Duration.ofMillis(500)); } catch (Exception ignored) {}
    }

    /**
     * Unsubscribe from a subject.
     */
    public void unsubscribe(String subject) {
        handlers.remove(subject);
        if (sharedDispatcher != null) {
            sharedDispatcher.unsubscribe(subject);
        }
    }

    // ── Request/Reply (for room command proxying) ──

    /**
     * Send a request and wait for a reply (async).
     * Uses NATS built-in request/reply with an inbox subject.
     */
    public CompletableFuture<JsonNode> request(
            String layer, String topic,
            JsonNode payload, Duration timeout) {
        if (connection == null || connection.getStatus() != Connection.Status.CONNECTED) {
            return CompletableFuture.failedFuture(
                new IOException("NATS not connected"));
        }
        var subject = subjectBroadcast(layer, topic);
        var envelope = BetweenEnvelope.create(nodeId, null, payload, identity);
        return CompletableFuture.supplyAsync(() -> {
            try {
                var msg = connection.request(subject, envelope.toBytes(), timeout);
                if (msg == null) throw new IOException("Request timed out");
                var replyEnvelope = BetweenEnvelope.fromBytes(msg.getData());
                return replyEnvelope.payload();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Subscribe to requests on a layer.topic and handle with reply capability.
     * The handler receives (payload, replySubject) — call respond() with the replySubject to reply.
     */
    public void subscribeRequest(String layer, String topic,
                                  BiConsumer<JsonNode, String> handler) {
        var pattern = "between." + zoneId + ".*.*." + layer + "." + topic;
        if (connection == null) {
            log.warn("Cannot subscribe — NATS not connected");
            return;
        }

        // Request/reply needs its own dispatcher because we need access to the reply subject
        var dispatcher = connection.createDispatcher(msg -> {
            if (msg.getReplyTo() == null) return; // not a request
            try {
                var envelope = BetweenEnvelope.fromBytes(msg.getData());
                if (nodeId.equals(envelope.src())) return; // ignore own messages
                handler.accept(envelope.payload(), msg.getReplyTo());
            } catch (Exception e) {
                log.error("Error processing NATS request on {}: {}", msg.getSubject(), e.getMessage());
            }
        });
        dispatcher.subscribe(pattern);
        log.debug("Subscribed to requests on {}", pattern);
    }

    /**
     * As {@link #subscribeRequest} but hands the handler the FULL signed
     * envelope, not just the payload — for layers that must verify the
     * sender against a roster before acting (courier file transfer writes
     * to disk; "it arrived on the bus" is not authentication once a relay
     * leg bridges {@code between.{zone}.>} to remote peers).
     */
    public void subscribeRequestEnvelope(String layer, String topic,
                                          BiConsumer<BetweenEnvelope, String> handler) {
        var pattern = "between." + zoneId + ".*.*." + layer + "." + topic;
        if (connection == null) {
            log.warn("Cannot subscribe — NATS not connected");
            return;
        }
        var dispatcher = connection.createDispatcher(msg -> {
            if (msg.getReplyTo() == null) return; // not a request
            try {
                var envelope = BetweenEnvelope.fromBytes(msg.getData());
                if (nodeId.equals(envelope.src())) return; // ignore own messages
                handler.accept(envelope, msg.getReplyTo());
            } catch (Exception e) {
                log.error("Error processing NATS request on {}: {}", msg.getSubject(), e.getMessage());
            }
        });
        dispatcher.subscribe(pattern);
        log.debug("Subscribed to envelope requests on {}", pattern);
    }

    /**
     * Send a reply to a NATS request.
     */
    public void respond(String replySubject, JsonNode payload) {
        if (connection == null || connection.getStatus() != Connection.Status.CONNECTED) {
            log.warn("Cannot respond — NATS not connected");
            return;
        }
        var envelope = BetweenEnvelope.create(nodeId, null, payload, identity);
        connection.publish(replySubject, envelope.toBytes());
    }

    // ── JetStream (reserved for zone-to-zone relay — not used in household mesh) ──

    /**
     * Ensure a JetStream stream exists. Creates it if absent.
     * @param streamName  stream name (e.g., "WYRD_ACCOUNTS")
     * @param subjects    subjects this stream captures (e.g., "account.>")
     */
    public void ensureStream(String streamName, String... subjects) {
        if (connection == null) return;
        try {
            var jsm = connection.jetStreamManagement();
            try {
                jsm.getStreamInfo(streamName);
                log.debug("JetStream stream '{}' already exists", streamName);
            } catch (JetStreamApiException e) {
                if (e.getApiErrorCode() == 10059) { // stream not found
                    var config = StreamConfiguration.builder()
                        .name(streamName)
                        .subjects(subjects)
                        .retentionPolicy(RetentionPolicy.Limits)
                        .maxAge(Duration.ofDays(30))
                        .storageType(StorageType.File)
                        .replicas(1)
                        .build();
                    jsm.addStream(config);
                    log.info("JetStream stream '{}' created (subjects: {})", streamName,
                        String.join(", ", subjects));
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            log.error("Failed to ensure JetStream stream '{}': {}", streamName, e.getMessage());
        }
    }

    /**
     * Publish a message to a JetStream subject (persistent, replayed to new subscribers).
     */
    public void jetStreamPublish(String subject, byte[] data) {
        if (connection == null) return;
        try {
            var js = connection.jetStream();
            js.publish(subject, data);
        } catch (Exception e) {
            log.warn("JetStream publish to {} failed: {}", subject, e.getMessage());
        }
    }

    /**
     * Subscribe to a JetStream subject with a durable consumer.
     * Replays ALL messages from the stream start on first subscribe.
     * @param streamName    stream to subscribe from
     * @param subject       subject filter (e.g., "account.>")
     * @param durableName   durable consumer name (unique per node)
     * @param handler       message handler
     */
    /**
     * Subscribe to a JetStream subject with a durable pull consumer.
     * Replays ALL messages from the stream start, then polls for new ones.
     * Pull-based is more reliable than push on pre-connected bridges where
     * the NATS connection was created before the ActorSystem.
     */
    public void jetStreamSubscribe(String streamName, String subject, String durableName,
                                    Consumer<Message> handler) {
        if (connection == null) return;
        try {
            var jsm = connection.jetStreamManagement();

            // Create or bind to durable consumer
            try {
                jsm.getConsumerInfo(streamName, durableName);
            } catch (JetStreamApiException e) {
                if (e.getApiErrorCode() == 10014) { // consumer not found
                    var consConfig = ConsumerConfiguration.builder()
                        .durable(durableName)
                        .filterSubject(subject)
                        .ackPolicy(AckPolicy.Explicit)
                        .deliverPolicy(DeliverPolicy.All)
                        .ackWait(Duration.ofSeconds(10)) // fast redelivery on disconnect
                        .build();
                    jsm.addOrUpdateConsumer(streamName, consConfig);
                }
            }

            // Pull-based polling loop in a daemon thread
            var js = connection.jetStream();
            var pullOpts = PullSubscribeOptions.builder()
                .stream(streamName)
                .durable(durableName)
                .build();
            var sub = js.subscribe(subject, pullOpts);

            final var subjectFinal = subject;
            final var pullOptsFinal = pullOpts;
            var pullThread = new Thread(() -> {
                // Never exit — survive disconnects, reconnects, and transient errors.
                // Re-subscribe every 30s unconditionally — jnats pull subs silently die
                // after reconnects without throwing exceptions.
                JetStreamSubscription activeSub = sub;
                long lastResubscribe = System.currentTimeMillis();
                while (connection != null) {
                    try {
                        var status = connection.getStatus();
                        if (status != Connection.Status.CONNECTED) {
                            Thread.sleep(3000);
                            continue;
                        }
                        // Periodic re-subscribe to survive silent subscription death
                        if (System.currentTimeMillis() - lastResubscribe > 30_000) {
                            try {
                                activeSub = connection.jetStream().subscribe(subjectFinal, pullOptsFinal);
                                lastResubscribe = System.currentTimeMillis();
                                log.info("JetStream pull re-subscribed: {}/{}", streamName, durableName);
                            } catch (Exception resub) {
                                log.debug("Re-subscribe failed: {}", resub.getMessage());
                            }
                        }
                        var messages = activeSub.fetch(10, Duration.ofSeconds(5));
                        for (var msg : messages) {
                            try {
                                handler.accept(msg);
                                msg.ack();
                            } catch (Exception e) {
                                log.warn("JetStream handler error on {}: {}",
                                    msg.getSubject(), e.getMessage());
                            }
                        }
                    } catch (InterruptedException ie) {
                        break;
                    } catch (Exception e) {
                        log.debug("JetStream pull: {}", e.getMessage());
                        lastResubscribe = 0; // force re-subscribe on next cycle
                        try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
                    }
                }
                log.info("JetStream pull loop ended for {}/{}", streamName, durableName);
            }, "jetstream-pull-" + durableName.substring(durableName.length() - 8));
            pullThread.setDaemon(true);
            pullThread.start();

            log.info("JetStream subscribed (pull): stream={} subject={} durable={}",
                streamName, subject, durableName);
        } catch (Exception e) {
            log.error("JetStream subscribe failed: stream={} subject={} error={}",
                streamName, subject, e.getMessage());
        }
    }

    public boolean isConnected() {
        return connection != null && connection.getStatus() == Connection.Status.CONNECTED;
    }

    public String zoneId() {
        return zoneId;
    }

    public String nodeId() {
        return nodeId;
    }

    public String natsUrl() {
        return natsUrl;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            connection = null;
        }
        handlers.clear();
        sharedDispatcher = null;
        log.info("NATS bridge closed");
    }

    // --- Subject helpers ---

    private String subjectBroadcast(String layer, String topic) {
        return "between." + zoneId + "." + nodeId + ".*." + layer + "." + topic;
    }

    private String subjectDirected(String targetNodeId, String layer, String topic) {
        return "between." + zoneId + "." + nodeId + "." + targetNodeId + "." + layer + "." + topic;
    }
}
