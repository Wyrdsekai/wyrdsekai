package org.wyrdsekai.between;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Direct relay connection for session proxy messaging.
 * Publishes/subscribes directly on the relay NATS, bypassing the RelayBridge
 * forwarding to avoid feedback loops.
 *
 * <p><b>Reconnect-replay invariant</b>: subscriptions registered via {@link
 * #subscribe(String, Consumer)} are tracked, and the connection's
 * {@link ConnectionListener} re-binds them on RECONNECTED. jnats documents
 * automatic subscription restoration, but production incident 2026-05-12
 * (relay-node NATS restart) showed silent subscription loss — explicit replay
 * closes that edge.</p>
 */
public class RelaySessionTransport {

    private static final Logger log = LoggerFactory.getLogger(RelaySessionTransport.class);

    private final Connection connection;

    // Tracked subscriptions. Iteration order matters for predictable replay
    // logs; LinkedHashMap preserves insertion order without sacrificing
    // O(1) lookup. Synchronized externally via the transport instance.
    private final Map<String, Consumer<byte[]>> liveSubs = new LinkedHashMap<>();
    private final AtomicReference<Dispatcher> sharedDispatcherRef = new AtomicReference<>();

    public RelaySessionTransport(Connection connection) {
        this.connection = connection;
    }

    /** No-arg constructor for test subclasses that bypass the NATS connection. */
    protected RelaySessionTransport() {
        this.connection = null;
    }

    public boolean isConnected() {
        return connection != null && connection.getStatus() == Connection.Status.CONNECTED;
    }

    /**
     * Server-advertised maximum payload size in bytes (the NATS server's
     * {@code max_payload} setting, default 1 MiB). Returns {@code -1} when
     * the transport has no live connection or the server hasn't advertised
     * a value yet — callers should treat that as "unknown, send and let
     * the wire enforce" rather than refusing to publish.
     *
     * <p>Exposed so callers (e.g. {@link
     * org.wyrdsekai.between.inference.NatsInferenceClient}) can pre-check
     * payload size and fail fast with a typed error before paying the
     * Jackson serialization cost on a publish that will be rejected
     * anyway. See the 2026-04-27 test-node GC death-spiral incident.</p>
     */
    public long maxPayload() {
        if (connection == null) return -1;
        try {
            return connection.getMaxPayload();
        } catch (Exception e) {
            return -1;
        }
    }

    public void publish(String subject, byte[] data) {
        if (!isConnected()) return;
        connection.publish(subject, data);
        try { connection.flush(Duration.ofMillis(500)); } catch (Exception ignored) {}
    }

    /**
     * Token returned by {@link #subscribe} so callers can later
     * {@link #closeDispatcherObj} a single subscription without tearing down
     * the shared dispatcher. Cheap value-type — just the subject pattern.
     */
    public record Subscription(String subject) {}

    /**
     * Subscribe to a subject. Returns a {@link Subscription} token that can
     * be passed to {@link #closeDispatcherObj} to unsubscribe just this
     * subject. The dispatcher itself is shared across all subscriptions on
     * this transport.
     *
     * @return Subscription token. #8 (2026-07-19 OSS hardening): a token is now
     *         returned even when disconnected — the subscription is RECORDED and
     *         binds on the next (re)connect (see below), so it is never silently
     *         dropped.
     */
    public Object subscribe(String subject, Consumer<byte[]> handler) {
        synchronized (this) {
            // #8 — record the subscription EVEN when disconnected. A relay leg
            // that is offline at subscribe-time (e.g. a multi-homed leg down at
            // boot) used to have subscribe() return null and drop the sub, so it
            // silently received NO inbound traffic until a full restart. Recording
            // it means the ConnectionListener's replaySubscriptions() binds it on
            // (re)connect.
            liveSubs.put(subject, handler);
            if (isConnected()) {
                bindSubject(subject);
            } else {
                log.info("RelaySessionTransport: subject '{}' recorded while disconnected — "
                    + "will bind on next (re)connect", subject);
            }
        }
        return new Subscription(subject);
    }

    /**
     * Lazily create (or fetch) the shared dispatcher and register {@code
     * subject} on it. Called from {@link #subscribe} and from
     * {@link #replaySubscriptions} after a reconnect. Caller holds the
     * transport monitor.
     */
    private void bindSubject(String subject) {
        var dispatcher = sharedDispatcherRef.get();
        if (dispatcher == null) {
            dispatcher = connection.createDispatcher(msg -> {
                // Route by subject pattern — wildcards are matched by NATS
                // server-side, so we just look up by the literal subscribed
                // pattern (which is what gets stored in liveSubs).
                var h = resolveHandler(msg.getSubject());
                if (h == null) return;
                try {
                    h.accept(msg.getData());
                } catch (Exception e) {
                    log.error("Error on relay session transport {}: {}",
                        msg.getSubject(), e.getMessage());
                }
            });
            sharedDispatcherRef.set(dispatcher);
        }
        dispatcher.subscribe(subject);
    }

    /**
     * Find the registered handler whose subscribed pattern matches the
     * incoming subject. Supports NATS wildcards ({@code *} segment,
     * {@code &gt;} suffix).
     */
    private synchronized Consumer<byte[]> resolveHandler(String subject) {
        var direct = liveSubs.get(subject);
        if (direct != null) return direct;
        for (var entry : liveSubs.entrySet()) {
            if (subjectMatches(subject, entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static boolean subjectMatches(String subject, String pattern) {
        var subParts = subject.split("\\.");
        var patParts = pattern.split("\\.");
        for (int i = 0; i < patParts.length; i++) {
            if (">".equals(patParts[i])) return true;
            if (i >= subParts.length) return false;
            if (!"*".equals(patParts[i]) && !patParts[i].equals(subParts[i])) return false;
        }
        return subParts.length == patParts.length;
    }

    /**
     * Replay all tracked subscriptions on a fresh dispatcher. Invoked from
     * the {@link ConnectionListener} on RECONNECTED in case jnats's built-in
     * auto-restoration missed any subjects (see class javadoc).
     */
    public synchronized void replaySubscriptions() {
        if (!isConnected()) {
            log.warn("RelaySessionTransport.replaySubscriptions(): not connected, deferring");
            return;
        }
        if (liveSubs.isEmpty()) return;
        var stale = sharedDispatcherRef.getAndSet(null);
        if (stale != null) {
            try { connection.closeDispatcher(stale); } catch (Exception ignored) {}
        }
        for (var subject : liveSubs.keySet()) {
            bindSubject(subject);
        }
        log.info("RelaySessionTransport replayed {} subscriptions after reconnect", liveSubs.size());
    }

    /** Legacy entry point — closes the entire dispatcher. New callers should
     *  pass the {@link Subscription} token through {@link #closeDispatcherObj}
     *  to unsubscribe just one subject. */
    public void closeDispatcher(Dispatcher dispatcher) {
        if (connection != null && dispatcher != null) {
            connection.closeDispatcher(dispatcher);
        }
    }

    /**
     * Close a per-subscription token (or, for legacy callers, a raw
     * Dispatcher). Unsubscribes the single subject backing the
     * {@link Subscription}; the shared dispatcher itself stays alive for
     * other subscriptions on this transport.
     */
    public void closeDispatcherObj(Object token) {
        if (token == null) return;
        if (token instanceof Subscription s) {
            unsubscribe(s.subject());
        } else if (token instanceof Dispatcher d) {
            // Old code-path. Closing the dispatcher is destructive — all
            // other subscriptions on the same transport go with it. We
            // honor it because that's what the prior contract did, but
            // the shared-dispatcher design means this should now be rare.
            closeDispatcher(d);
            sharedDispatcherRef.compareAndSet(d, null);
            synchronized (this) { liveSubs.clear(); }
        }
    }

    private void unsubscribe(String subject) {
        Dispatcher d;
        synchronized (this) {
            liveSubs.remove(subject);
            d = sharedDispatcherRef.get();
        }
        if (d != null) {
            try { d.unsubscribe(subject); } catch (Exception ignored) { /* best effort */ }
        }
    }

    public void close() {
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Create a RelaySessionTransport by connecting to a relay NATS server.
     * Legacy password-mode entrypoint — kept for backwards compat during the
     * migration window. New code should pass a {@link
     * NodeIdentity} via {@link #connect(String, NodeIdentity, String)}.
     */
    public static RelaySessionTransport connect(String relayUrl, String user, String password,
                                                  String connectionName) {
        return connect(relayUrl, user, password, /*nodeIdentity=*/null, connectionName);
    }

    /**
     * connect: when {@code nodeIdentity} is non-null, use NKey
     * AuthHandler instead of user/password. Mirror of the same dual-mode
     * decision RelayBridge makes — one transport, two auth flavours during
     * the migration window. Phase 4 retires the password path.
     */
    public static RelaySessionTransport connect(String relayUrl, NodeIdentity nodeIdentity,
                                                  String connectionName) {
        return connect(relayUrl, /*user=*/null, /*password=*/null, nodeIdentity, connectionName);
    }

    /**
     * Full-config connect — internal entry point shared by both legacy and
     * NKey overloads. Prefers NKey when {@code nodeIdentity} is wired (matches
     * RelayBridge.start() precedence). Both modes coexist in the same relay
     * authorization block during migration; either is accepted.
     */
    public static RelaySessionTransport connect(String relayUrl, String user, String password,
                                                  NodeIdentity nodeIdentity,
                                                  String connectionName) {
        try {
            // Forward reference so the ConnectionListener can call
            // replaySubscriptions() after we hand the connection to the new
            // transport instance.
            var transportRef = new AtomicReference<RelaySessionTransport>();
            var opts = new Options.Builder()
                .server(relayUrl)
                .connectionName(connectionName)
                .maxReconnects(-1)
                .reconnectWait(Duration.ofSeconds(5))
                // Tighter than the 30s × 10 = 5min window that masked the
                // 2026-05-12 relay-node NATS restart from test-node. 15s × 3 = 45s
                // worst-case dead-connection detection.
                .pingInterval(Duration.ofSeconds(15))
                .maxPingsOut(3)
                .connectionListener((conn, type) -> {
                    switch (type) {
                        case CONNECTED ->
                            log.info("RelaySessionTransport connected: {}", relayUrl);
                        case DISCONNECTED ->
                            log.warn("RelaySessionTransport disconnected: {}", relayUrl);
                        case RECONNECTED -> {
                            log.info("RelaySessionTransport reconnected: {}", relayUrl);
                            var t = transportRef.get();
                            if (t != null) t.replaySubscriptions();
                        }
                        case RESUBSCRIBED ->
                            log.info("RelaySessionTransport resubscribed (jnats auto-restore)");
                        case CLOSED ->
                            log.info("RelaySessionTransport closed: {}", relayUrl);
                        default -> log.debug("RelaySessionTransport event: {}", type);
                    }
                });
            if (nodeIdentity != null) {
                opts.authHandler(nodeIdentity.nkeyAuthHandler());
                log.info("RelaySessionTransport: NKey auth (pubkey={})",
                    truncatePubkey(nodeIdentity.nkeyPublicKey()));
            } else if (user != null && !user.isEmpty()) {
                opts.userInfo(user, password != null ? password : "");
            }
            var conn = Nats.connect(opts.build());
            log.info("RelaySessionTransport connected to {} (auth={})",
                relayUrl, nodeIdentity != null ? "nkey" : "password");
            var transport = new RelaySessionTransport(conn);
            transportRef.set(transport);
            return transport;
        } catch (Exception e) {
            log.warn("Failed to connect RelaySessionTransport to {}: {}", relayUrl, e.getMessage());
            return null;
        }
    }

    private static String truncatePubkey(String pubkey) {
        if (pubkey == null || pubkey.length() < 16) return pubkey;
        return pubkey.substring(0, 8) + "…" + pubkey.substring(pubkey.length() - 4);
    }
}
