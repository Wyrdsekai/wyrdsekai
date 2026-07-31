package org.wyrdsekai.daemon.common;

import io.nats.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Lightweight NATS client wrapper for daemon use.
 * Handles connection lifecycle, reconnection, and typed pub/sub.
 *
 * Uses raw jnats — no dependency on Between's NatsBridge or envelope signing.
 * The daemon operates within the household trust model (no per-message auth).
 */
public final class DaemonNatsClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DaemonNatsClient.class);

    private final String serverUrl;
    private final String nodeId;
    private Connection connection;
    private volatile boolean connected;

    public DaemonNatsClient(String serverUrl, String nodeId) {
        this.serverUrl = serverUrl;
        this.nodeId = nodeId;
    }

    /**
     * Connect to the NATS server. Blocks until connected or throws.
     */
    public void connect() throws IOException, InterruptedException {
        var options = new Options.Builder()
            .server(serverUrl)
            .connectionName("wyrd-daemon-" + nodeId.substring(0, Math.min(8, nodeId.length())))
            .maxReconnects(-1)
            .reconnectWait(Duration.ofSeconds(2))
            .connectionListener((conn, type) -> {
                switch (type) {
                    case CONNECTED -> {
                        connected = true;
                        log.info("NATS connected to {}", serverUrl);
                    }
                    case RECONNECTED -> {
                        connected = true;
                        log.info("NATS reconnected");
                    }
                    case DISCONNECTED -> {
                        connected = false;
                        log.warn("NATS disconnected");
                    }
                    case CLOSED -> {
                        connected = false;
                        log.info("NATS connection closed");
                    }
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
        connected = true;
        log.info("NATS daemon bridge established — node {}", nodeId);
    }

    /**
     * Publish a raw JSON string to a subject.
     */
    public void publish(String subject, String json) {
        if (!isConnected()) {
            log.warn("Cannot publish to {} — NATS not connected", subject);
            return;
        }
        connection.publish(subject, json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Subscribe to a subject with a string message handler.
     */
    public Dispatcher subscribe(String subject, Consumer<String> handler) {
        if (connection == null) {
            throw new IllegalStateException("Not connected");
        }
        var dispatcher = connection.createDispatcher(msg -> {
            try {
                var json = new String(msg.getData(), StandardCharsets.UTF_8);
                handler.accept(json);
            } catch (Exception e) {
                log.error("Error processing message on {}: {}", subject, e.getMessage());
            }
        });
        dispatcher.subscribe(subject);
        log.debug("Subscribed to {}", subject);
        return dispatcher;
    }

    /**
     * Handler for NATS request/reply: receives request data and a reply callback.
     */
    @FunctionalInterface
    public interface RequestHandler {
        void handle(byte[] data, Consumer<byte[]> reply);
    }

    /**
     * Subscribe to a subject for NATS request/reply.
     * The handler receives the request data and a reply callback that
     * publishes to the message's replyTo subject.
     */
    public Dispatcher subscribeRequestReply(String subject, RequestHandler handler) {
        if (connection == null) {
            throw new IllegalStateException("Not connected");
        }
        var dispatcher = connection.createDispatcher(msg -> {
            try {
                handler.handle(msg.getData(), reply -> {
                    if (msg.getReplyTo() != null) {
                        connection.publish(msg.getReplyTo(), reply);
                    }
                });
            } catch (Exception e) {
                log.error("Error processing request on {}: {}", subject, e.getMessage());
            }
        });
        dispatcher.subscribe(subject);
        log.debug("Subscribed to request/reply on {}", subject);
        return dispatcher;
    }

    /**
     * Send a NATS request and wait for a reply.
     */
    public Message request(String subject, String json, Duration timeout)
            throws InterruptedException {
        if (!isConnected()) {
            throw new IllegalStateException("Not connected");
        }
        return connection.request(subject,
            json.getBytes(StandardCharsets.UTF_8), timeout);
    }

    public boolean isConnected() {
        return connected && connection != null
            && connection.getStatus() == Connection.Status.CONNECTED;
    }

    public String nodeId() { return nodeId; }
    public String serverUrl() { return serverUrl; }

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
        connected = false;
        log.info("NATS daemon client closed");
    }
}
