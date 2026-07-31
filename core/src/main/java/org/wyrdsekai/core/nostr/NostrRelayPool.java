package org.wyrdsekai.core.nostr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * multi-relay WebSocket connection pool.
 *
 * <p>One persistent {@link WebSocket} per configured relay URL. Reconnects on
 * close with exponential backoff (cap 5min). Publishing fans out to all
 * connected relays; the call succeeds if at least one relay accepts.
 *
 * <p>This is the {@link NostrAdapter}'s dependency — adapters call
 * {@link #publish(NostrEvent)}. Inbound subscriptions come in Phase 2b.
 *
 * <p>Lifecycle: build once at startup with the configured relay list, call
 * {@link #start()} to dial them, call {@link #close()} on shutdown.
 */
public final class NostrRelayPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NostrRelayPool.class);
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final List<String> relayUrls;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, RelayConn> conns = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    public NostrRelayPool(List<String> relayUrls) {
        this(relayUrls, HttpClient.newHttpClient());
    }

    /** Test-friendly ctor — substitute an HttpClient (or one with a mock transport). */
    public NostrRelayPool(List<String> relayUrls, HttpClient httpClient) {
        this.relayUrls = List.copyOf(relayUrls);
        this.httpClient = httpClient;
    }

    /** Start dialing all configured relays. Non-blocking — connections come up async. */
    public void start() {
        for (var url : relayUrls) {
            conns.computeIfAbsent(url, u -> new RelayConn(u));
            conns.get(url).connectAsync();
        }
    }

    /**
     * Publish an event to every relay in the pool. Resolves when the first
     * relay accepts (sends back an {@code OK} frame) or all fail / time out.
     *
     * @return PublishResult summarising per-relay outcomes
     */
    public PublishResult publish(NostrEvent event) {
        if (closed) {
            return new PublishResult(0, relayUrls.size(), List.of("pool_closed"));
        }
        var frame = event.toRelayPublishFrame();
        var results = new ArrayList<RelayPublishOutcome>(conns.size());
        var futures = new ArrayList<CompletableFuture<RelayPublishOutcome>>(conns.size());
        for (var entry : conns.entrySet()) {
            futures.add(entry.getValue().publish(event.id(), frame));
        }
        for (var f : futures) {
            try {
                results.add(f.get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException te) {
                results.add(new RelayPublishOutcome("?", false, "timeout"));
                f.cancel(true);
            } catch (Exception e) {
                results.add(new RelayPublishOutcome("?", false, e.getMessage()));
            }
        }
        int ok = 0;
        int fail = 0;
        var errors = new ArrayList<String>();
        for (var r : results) {
            if (r.ok()) ok++;
            else {
                fail++;
                if (r.message() != null) errors.add(r.relay() + ": " + r.message());
            }
        }
        return new PublishResult(ok, fail, errors);
    }

    /**
     * Open a subscription on every connected relay. Sends a
     * {@code ["REQ", subId, filter]} frame; incoming {@code ["EVENT", subId, event]}
     * frames are routed to the {@link NostrEventListener}, and one
     * {@link NostrEventListener#onEose(String)} fires per relay when it has
     * sent all stored events matching the filter (relays then keep delivering
     * matching live events until {@link #unsubscribe(String)}).
     *
     * <p>Sub IDs are caller-chosen; uniqueness is the caller's job. Same id
     * used twice = caller hears events for both filters.
     *
     * @param subId       caller-chosen subscription id (max 64 chars per NIP-01)
     * @param filter      NIP-01 filter object (e.g. {@code {"kinds":[1], "#p":[hex]}})
     * @param listener    callback for events / EOSE / errors
     * @return per-relay attempted state
     */
    public Map<String, Boolean> subscribe(String subId, Map<String, Object> filter,
                                          NostrEventListener listener) {
        if (closed) return Map.of();
        if (subId == null || subId.isBlank() || subId.length() > 64) {
            throw new IllegalArgumentException("subId must be 1-64 chars");
        }
        var frame = buildReqFrame(subId, filter);
        var out = new LinkedHashMap<String, Boolean>();
        for (var entry : conns.entrySet()) {
            out.put(entry.getKey(), entry.getValue().subscribe(subId, frame, listener));
        }
        return out;
    }

    /**
     * Close a previously-opened subscription. Sends {@code ["CLOSE", subId]}
     * to every relay and removes the listener locally. Safe to call multiple
     * times.
     */
    public void unsubscribe(String subId) {
        if (subId == null || subId.isBlank()) return;
        var frame = "[\"CLOSE\",\"" + subId.replace("\"", "") + "\"]";
        for (var c : conns.values()) c.unsubscribe(subId, frame);
    }

    /** Listener for inbound NIP-01 EVENT frames matching a subscription. */
    public interface NostrEventListener {
        /** Fires once per matching event. Includes the relay URL it came from. */
        void onEvent(String relay, String subId, NostrEvent event);
        /** Fires once per relay when it's done sending stored events. */
        default void onEose(String relay, String subId) {}
        /** Optional — fires when a relay closes the subscription explicitly. */
        default void onClosed(String relay, String subId, String reason) {}
    }

    private static String buildReqFrame(String subId, Map<String, Object> filter) {
        try {
            var mapper = new ObjectMapper();
            var arr = mapper.createArrayNode();
            arr.add("REQ");
            arr.add(subId);
            arr.addPOJO(filter == null ? Map.of() : filter);
            return mapper.writeValueAsString(arr);
        } catch (Exception e) {
            throw new RuntimeException("REQ frame serialize failed", e);
        }
    }

    @Override public void close() {
        closed = true;
        for (var c : conns.values()) c.close();
    }

    public Map<String, ConnState> stateSnapshot() {
        var out = new LinkedHashMap<String, ConnState>();
        for (var entry : conns.entrySet()) {
            out.put(entry.getKey(), entry.getValue().state());
        }
        return out;
    }

    // ─────────── records ───────────

    public record PublishResult(int acceptedCount, int rejectedCount, List<String> errors) {
        public boolean any() { return acceptedCount > 0; }
        public Map<String, Object> toMap() {
            return Map.of(
                "accepted", acceptedCount,
                "rejected", rejectedCount,
                "errors", errors);
        }
    }

    public record RelayPublishOutcome(String relay, boolean ok, String message) {}

    public enum ConnState { CONNECTING, OPEN, CLOSED, FAILED }

    // ─────────── per-relay connection ───────────

    private final class RelayConn implements WebSocket.Listener {
        private final String url;
        private volatile WebSocket ws;
        private volatile ConnState state = ConnState.CLOSED;
        private final ConcurrentHashMap<String, CompletableFuture<RelayPublishOutcome>> pending =
            new ConcurrentHashMap<>();
        /** Active subscriptions on this relay: subId → (frame, listener). */
        private final ConcurrentHashMap<String, SubEntry> subs = new ConcurrentHashMap<>();
        private final StringBuilder textBuf = new StringBuilder();
        private final AtomicLong backoffMs = new AtomicLong(500);

        RelayConn(String url) { this.url = url; }

        private record SubEntry(String reqFrame, NostrEventListener listener) {}

        ConnState state() { return state; }

        void connectAsync() {
            if (closed) return;
            state = ConnState.CONNECTING;
            httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(url), this)
                .whenComplete((sock, err) -> {
                    if (err != null) {
                        state = ConnState.FAILED;
                        log.info("nostr relay {} dial failed: {}", url, err.getMessage());
                        scheduleReconnect();
                    } else {
                        ws = sock;
                        state = ConnState.OPEN;
                        backoffMs.set(500);
                        log.info("nostr relay {} connected", url);
                        // Re-send REQ frames for any active subscriptions so
                        // the relay starts streaming events again. Critical
                        // for the inbound path to survive reconnects.
                        replaySubscriptions();
                    }
                });
        }

        boolean subscribe(String subId, String reqFrame, NostrEventListener listener) {
            subs.put(subId, new SubEntry(reqFrame, listener));
            var sock = ws;
            if (sock == null || state != ConnState.OPEN) {
                // Will be replayed on reconnect by connectAsync()
                return false;
            }
            try {
                sock.sendText(reqFrame, true);
                return true;
            } catch (Exception e) {
                log.debug("nostr {} REQ failed: {}", url, e.getMessage());
                return false;
            }
        }

        void unsubscribe(String subId, String closeFrame) {
            var removed = subs.remove(subId);
            if (removed == null) return;
            var sock = ws;
            if (sock != null && state == ConnState.OPEN) {
                try { sock.sendText(closeFrame, true); }
                catch (Exception e) { log.debug("nostr {} CLOSE failed: {}", url, e.getMessage()); }
            }
        }

        /** Replay active subscriptions after reconnect. */
        private void replaySubscriptions() {
            var sock = ws;
            if (sock == null) return;
            for (var entry : subs.entrySet()) {
                try { sock.sendText(entry.getValue().reqFrame(), true); }
                catch (Exception e) {
                    log.debug("nostr {} replay REQ for {} failed: {}",
                        url, entry.getKey(), e.getMessage());
                }
            }
        }

        CompletableFuture<RelayPublishOutcome> publish(String eventId, String frame) {
            var sock = ws;
            if (sock == null || state != ConnState.OPEN) {
                return CompletableFuture.completedFuture(
                    new RelayPublishOutcome(url, false, "not_connected"));
            }
            var fut = new CompletableFuture<RelayPublishOutcome>();
            pending.put(eventId, fut);
            try {
                sock.sendText(frame, true)
                    .whenComplete((s, err) -> {
                        if (err != null) {
                            pending.remove(eventId);
                            fut.complete(new RelayPublishOutcome(url, false,
                                "send: " + err.getMessage()));
                        }
                    });
            } catch (Exception e) {
                pending.remove(eventId);
                fut.complete(new RelayPublishOutcome(url, false, "send: " + e.getMessage()));
            }
            return fut;
        }

        @Override public CompletionStage<?> onText(
                WebSocket ws, CharSequence data, boolean last) {
            textBuf.append(data);
            if (last) {
                var full = textBuf.toString();
                textBuf.setLength(0);
                handleRelayFrame(full);
            }
            ws.request(1);
            return null;
        }

        private void handleRelayFrame(String frame) {
            // NIP-01 relay→client frames we handle:
            //   ["OK", event_id, accepted, message]   — publish ack
            //   ["EVENT", sub_id, event_object]       — inbound subscription delivery
            //   ["EOSE", sub_id]                      — end of stored events for sub
            //   ["CLOSED", sub_id, reason]            — relay terminated the sub
            //   ["NOTICE", message]                   — operator message; just log
            try {
                var node = new ObjectMapper().readTree(frame);
                if (!node.isArray() || node.size() < 2) return;
                var kind = node.get(0).asText();
                switch (kind) {
                    case "OK" -> {
                        if (node.size() < 3) return;
                        var eventId = node.get(1).asText();
                        var ok = node.get(2).asBoolean(false);
                        var msg = node.size() > 3 ? node.get(3).asText("") : "";
                        var fut = pending.remove(eventId);
                        if (fut != null) fut.complete(new RelayPublishOutcome(url, ok, ok ? null : msg));
                    }
                    case "EVENT" -> {
                        if (node.size() < 3) return;
                        var subId = node.get(1).asText();
                        var entry = subs.get(subId);
                        if (entry == null) return;   // stale event for cancelled sub
                        try {
                            var ev = new ObjectMapper()
                                .treeToValue(node.get(2), NostrEvent.class);
                            // Verify signature before dispatching — never hand
                            // unverified events to the listener.
                            if (ev.verify()) {
                                entry.listener().onEvent(url, subId, ev);
                            } else {
                                log.debug("nostr {} dropped bad-sig event for sub {}", url, subId);
                            }
                        } catch (Exception parseErr) {
                            log.debug("nostr {} EVENT parse failed: {}", url, parseErr.getMessage());
                        }
                    }
                    case "EOSE" -> {
                        var subId = node.get(1).asText();
                        var entry = subs.get(subId);
                        if (entry != null) entry.listener().onEose(url, subId);
                    }
                    case "CLOSED" -> {
                        var subId = node.get(1).asText();
                        var reason = node.size() > 2 ? node.get(2).asText("") : "";
                        var entry = subs.remove(subId);
                        if (entry != null) entry.listener().onClosed(url, subId, reason);
                    }
                    case "NOTICE" -> log.debug("nostr {} NOTICE: {}",
                        url, node.size() > 1 ? node.get(1).asText() : "");
                    default -> log.debug("nostr {} unknown frame kind: {}", url, kind);
                }
            } catch (Exception e) {
                log.debug("nostr {} frame parse failed: {}", url, e.getMessage());
            }
        }

        @Override public CompletionStage<?> onClose(
                WebSocket ws, int statusCode, String reason) {
            state = ConnState.CLOSED;
            log.info("nostr relay {} closed: {} {}", url, statusCode, reason);
            failPending("closed");
            scheduleReconnect();
            return null;
        }

        @Override public void onError(WebSocket ws, Throwable error) {
            state = ConnState.FAILED;
            log.info("nostr relay {} error: {}", url, error.getMessage());
            failPending("error: " + error.getMessage());
            scheduleReconnect();
        }

        private void failPending(String reason) {
            var snapshot = new ArrayList<>(pending.keySet());
            for (var id : snapshot) {
                var fut = pending.remove(id);
                if (fut != null) fut.complete(new RelayPublishOutcome(url, false, reason));
            }
        }

        private void scheduleReconnect() {
            if (closed) return;
            var delay = backoffMs.getAndUpdate(ms -> Math.min(ms * 2, MAX_BACKOFF.toMillis()));
            CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(this::connectAsync);
        }

        void close() {
            failPending("pool_closed");
            var sock = ws;
            if (sock != null) {
                try { sock.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); }
                catch (Exception e) { /* swallow */ }
            }
        }
    }
}
