package org.wyrdsekai.server.mcp;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.ByteBuffer;

/**
 * the zone side of the dumb relay pipe.
 *
 * <p>A remote phone tunnels a FULL session over the relay NATS bus. This
 * handler treats the relay as a dumb byte pipe: it carries the phone's
 * session uplink/downlink frames between NATS and a loopback WebSocket to the
 * zone's OWN session server (the same {@code /ws} endpoint every LAN client
 * uses). No per-action surface, no room logic — the existing WS server does
 * login / rooms / items / movement / companions because, from its point of
 * view, the tunneled session is just another client socket.
 *
 * <p>Subjects (zoneId-scoped, mirroring {@code wyrd.zone.*}):
 * <ul>
 *   <li>{@code wyrd.tunnel.{zone}.{session}.open}  — phone opens a session,
 *       payload {@code {"token":"..."}} (auth for the loopback {@code /ws}).</li>
 *   <li>{@code wyrd.tunnel.{zone}.{session}.up}    — phone→zone C2S JSON frame
 *       (forwarded verbatim into the loopback WS).</li>
 *   <li>{@code wyrd.tunnel.{zone}.{session}.down}  — zone→phone S2C JSON frame
 *       (published here from the loopback WS's onText).</li>
 *   <li>{@code wyrd.tunnel.{zone}.{session}.close} — either side ends it.</li>
 * </ul>
 *
 * <p>The frames are the exact same C2S/S2C JSON the {@code /ws} endpoint reads
 * and writes (Jackson, {@code "type"} discriminator), so this handler never
 * parses them — it only moves bytes. That is what makes remote-over-relay
 * byte-identical to on-LAN.
 */
public final class TunnelSessionHandler {

    private static final Logger log = LoggerFactory.getLogger(TunnelSessionHandler.class);

    private final Connection nats;
    private final String zoneId;
    private final int httpPort;
    private final String subjPrefix;          // "wyrd.tunnel.{zone}."
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, WebSocket> sessions = new ConcurrentHashMap<>();

    /**
     * Keepalive for the loopback leg.
     *
     * <p>The zone's {@code /ws} is closed after 5 minutes of silence (set
     * server-wide in Main; ZoneBridgeEndpoint raises its own to 30). A phone
     * that simply sits idle therefore loses its session on a timer, not on any
     * real fault — four of them expired together in one observed run, all with
     * {@code 1001 Connection Idle Timeout} exactly 300s after the last frame.
     *
     * <p>The phone's own WebSocket keepalive cannot prevent this: the tunnel is
     * a text-frame pipe ({@code onText}/{@code sendText}), so a ping from the
     * phone terminates at the relay and never reaches this socket. The leg has
     * to keep itself alive, which is what this does.
     *
     * <p>A real PING (not a synthetic app frame) is deliberate: it resets the
     * server's idle timer without appearing in the session's message stream,
     * and a dead leg still fails fast because the pong never arrives.
     */
    private static final long KEEPALIVE_SECONDS = 60;
    private final ScheduledExecutorService keepalives =
        Executors.newSingleThreadScheduledExecutor(r -> {
            var th = new Thread(r, "wyrd-tunnel-keepalive");
            th.setDaemon(true);
            return th;
        });
    private final Map<String, ScheduledFuture<?>> keepaliveTasks = new ConcurrentHashMap<>();
    // Uplink frames that arrive between `open` and the loopback WS completing
    // its handshake (e.g. the phone's initial `look`). Held here, flushed in
    // order once the socket is up, so no command is dropped to the race.
    private final Map<String, List<String>> pending = new ConcurrentHashMap<>();
    private final AtomicReference<Dispatcher> dispatcherRef = new AtomicReference<>();

    public TunnelSessionHandler(Connection nats, String zoneId, int httpPort) {
        this.nats = nats;
        this.zoneId = zoneId;
        this.httpPort = httpPort;
        this.subjPrefix = "wyrd.tunnel." + zoneId + ".";
    }

    /** Subscribe to the tunnel subjects on the (already-connected) relay leg. */
    public synchronized void start() {
        if (nats == null || nats.getStatus() != Connection.Status.CONNECTED) {
            log.warn("TunnelSessionHandler.start(): NATS not connected, skipping");
            return;
        }
        var dispatcher = nats.createDispatcher(this::dispatch);
        dispatcher.subscribe(subjPrefix + "*.open");
        dispatcher.subscribe(subjPrefix + "*.up");
        dispatcher.subscribe(subjPrefix + "*.close");
        dispatcherRef.set(dispatcher);
        log.info("TunnelSessionHandler started — subscribed {}*.{{open,up,close}} on zone {}",
            subjPrefix, zoneId);
    }

    public synchronized void stop() {
        var d = dispatcherRef.getAndSet(null);
        if (d != null && nats != null && nats.getStatus() == Connection.Status.CONNECTED) {
            try { nats.closeDispatcher(d); } catch (Exception ignored) { /* best effort */ }
        }
        for (var entry : sessions.entrySet()) {
            try { entry.getValue().sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); } catch (Exception ignored) {}
        }
        sessions.clear();
        keepaliveTasks.values().forEach(f -> f.cancel(false));
        keepaliveTasks.clear();
        keepalives.shutdownNow();
    }

    private void dispatch(Message msg) {
        var subject = msg.getSubject();
        if (!subject.startsWith(subjPrefix)) return;
        // remainder = "{session}.{verb}" — sessionId is a UUID (no dots).
        var rest = subject.substring(subjPrefix.length());
        var dot = rest.lastIndexOf('.');
        if (dot <= 0) return;
        var session = rest.substring(0, dot);
        var verb = rest.substring(dot + 1);
        if (!isWellFormedSession(session)) {
            log.debug("Tunnel: ignoring malformed session id on {}", subject);
            return;
        }
        switch (verb) {
            case "open" -> openSession(session, new String(msg.getData(), StandardCharsets.UTF_8));
            case "up" -> forwardUp(session, msg.getData());
            case "close" -> closeSession(session);
            default -> { /* ignore */ }
        }
    }

    /**
     * A session id must be an opaque, unguessable token (clients mint 128 CSPRNG
     * bits — see RelayTunnelServerConnection on both clients). Reject anything
     * that isn't plausibly one: it can only be a malformed client, a probe, or
     * an attempt to smuggle odd characters into subjects and log lines.
     * Length is bounded so a flood can't grow the session/pending maps with
     * long keys. Audit F1 residual, 2026-07-25.
     */
    static boolean isWellFormedSession(String session) {
        if (session == null || session.length() < 16 || session.length() > 64) return false;
        for (int i = 0; i < session.length(); i++) {
            var c = session.charAt(i);
            var ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Cap on simultaneously-tracked sessions. Household phones share one relay
     * NATS account, so a buggy (or hostile) household device could otherwise
     * open unbounded sessions and exhaust the zone's memory / loopback sockets.
     */
    static final int MAX_LIVE_SESSIONS = 64;

    /** Open a loopback WS to the zone's own session server, authed by the phone's token. */
    private void openSession(String session, String openPayload) {
        if (sessions.containsKey(session)) return; // idempotent — duplicate open
        if (sessions.size() + pending.size() >= MAX_LIVE_SESSIONS) {
            log.warn("Tunnel: refusing session {} — {} live sessions at cap {}",
                session, sessions.size() + pending.size(), MAX_LIVE_SESSIONS);
            publishDown(subjPrefix + session + ".down",
                "{\"type\":\"error\",\"seq\":0,\"code\":\"tunnel_busy\",\"message\":\"too many open sessions on this zone\"}");
            return;
        }
        // Register a buffer so uplink frames arriving during the connect race
        // are held (and so unsolicited up-frames with no prior open are ignored).
        pending.putIfAbsent(session, new CopyOnWriteArrayList<>());
        var downSubject = subjPrefix + session + ".down";
        // The tunnel is the universal door — and not only for presence.
        // `door` selects WHICH loopback endpoint carries this session; the
        // whitelist is deliberate (never a caller-supplied path). Absent or
        // unknown door = the classic /ws session, byte-identical to before.
        //   "hermod": the phone's mesh listener leg (/ws/hermod). Task plane,
        //   device-token authed, no presence session created. A phone roaming
        //   home↔away lands on the SAME PhoneDoorProxy either way — roaming
        //   is a channel supersede, not a second identity.
        var door = extractField(openPayload, "door");
        if ("hermod".equals(door)) {
            var deviceToken = extractField(openPayload, "deviceToken");
            if (deviceToken == null || deviceToken.isBlank()) {
                pending.remove(session);
                publishDown(downSubject,
                    "{\"type\":\"error\",\"seq\":0,\"code\":\"tunnel_auth\",\"message\":\"hermod door requires a device token\"}");
                return;
            }
            openLoopback(session, downSubject, "/ws/hermod?device_token="
                + URLEncoder.encode(deviceToken, StandardCharsets.UTF_8));
            return;
        }
        String token = extractField(openPayload, "token");
        try {
            // `home=1`: a phone tunnel is a fresh device session — land the user
            // in their Study (so the bonded companion is co-present) instead of
            // resuming the server-tracked last room (which may be the Nexus from
            // another surface). The /ws landing honours this only for authed
            // sessions; guests still go to the Nexus.
            var q = (token == null || token.isBlank())
                ? "?guest=1"
                : "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&home=1";
            openLoopback(session, downSubject, "/ws" + q);
        } catch (Exception e) {
            log.warn("Tunnel session {} open error: {}", session, e.getMessage());
        }
    }

    /** Connect the loopback leg to one of the whitelisted local endpoints. */
    private void openLoopback(String session, String downSubject, String pathAndQuery) {
        try {
            var uri = URI.create("ws://127.0.0.1:" + httpPort + pathAndQuery);
            http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, new LoopbackListener(session, downSubject))
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        log.warn("Tunnel session {} loopback connect failed: {}", session, err.getMessage());
                        publishDown(downSubject,
                            "{\"type\":\"error\",\"seq\":0,\"code\":\"tunnel_connect_failed\",\"message\":\"zone session unavailable\"}");
                    } else {
                        sessions.put(session, ws);
                        // Flush any uplink frames that raced ahead of the connect.
                        var queued = pending.remove(session);
                        if (queued != null) {
                            for (var frame : queued) {
                                try { ws.sendText(frame, true); } catch (Exception ignored) {}
                            }
                        }
                        scheduleKeepalive(session, ws);
                        log.info("Tunnel session {} opened (loopback {} on :{})",
                            session, pathAndQuery.substring(0, pathAndQuery.indexOf('?') > 0
                                ? pathAndQuery.indexOf('?') : pathAndQuery.length()), httpPort);
                    }
                });
        } catch (Exception e) {
            log.warn("Tunnel session {} open error: {}", session, e.getMessage());
        }
    }

    /** Forward a phone C2S frame verbatim into the loopback WS. */
    private void forwardUp(String session, byte[] data) {
        var frame = new String(data, StandardCharsets.UTF_8);
        var ws = sessions.get(session);
        if (ws == null) {
            // Loopback not connected yet — buffer in order (bounded) so the
            // initial command isn't lost to the open/first-frame race. If we
            // never saw an `open` for this session, ignore (unsolicited).
            var q = pending.get(session);
            if (q != null && q.size() < 64) q.add(frame);
            return;
        }
        try {
            ws.sendText(frame, true);
        } catch (Exception e) {
            log.debug("Tunnel session {} up-forward error: {}", session, e.getMessage());
        }
    }

    private void scheduleKeepalive(String session, WebSocket ws) {
        var task = keepalives.scheduleAtFixedRate(() -> {
            try {
                ws.sendPing(ByteBuffer.allocate(0));
            } catch (Exception e) {
                // The leg is gone; stop pinging it. The listener's onClose/onError
                // does the actual teardown — this only stops the timer.
                log.debug("Tunnel session {} keepalive failed: {}", session, e.getMessage());
                cancelKeepalive(session);
            }
        }, KEEPALIVE_SECONDS, KEEPALIVE_SECONDS, TimeUnit.SECONDS);
        var prev = keepaliveTasks.put(session, task);
        if (prev != null) prev.cancel(false);   // never leak a timer on re-open
    }

    private void cancelKeepalive(String session) {
        var task = keepaliveTasks.remove(session);
        if (task != null) task.cancel(false);
    }

    private void closeSession(String session) {
        cancelKeepalive(session);
        pending.remove(session);
        var ws = sessions.remove(session);
        if (ws != null) {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "client closed"); } catch (Exception ignored) {}
            log.info("Tunnel session {} closed", session);
        }
    }

    private void publishDown(String downSubject, String json) {
        try {
            nats.publish(downSubject, json.getBytes(StandardCharsets.UTF_8));
            nats.flush(Duration.ofSeconds(2));
        } catch (Exception e) {
            log.debug("Tunnel down-publish error on {}: {}", downSubject, e.getMessage());
        }
    }

    static String extractField(String payload, String field) {
        if (payload == null) return null;
        try {
            var node = Json.mapper().readTree(payload);
            return node.path(field).asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Pumps the loopback WS's S2C frames back onto the relay down-subject. */
    private final class LoopbackListener implements WebSocket.Listener {
        private final String session;
        private final String downSubject;
        private final StringBuilder buf = new StringBuilder();

        LoopbackListener(String session, String downSubject) {
            this.session = session;
            this.downSubject = downSubject;
        }

        @Override public void onOpen(WebSocket ws) { ws.request(1); }

        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                publishDown(downSubject, buf.toString());
                buf.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            cancelKeepalive(session);
            sessions.remove(session);
            log.info("Tunnel session {} loopback closed ({} {})", session, statusCode, reason);
            // On a non-normal close (e.g. 4002 Authentication required), tell the
            // client WHY instead of leaving it in silence — the connect-failure
            // path already publishes an error frame, but a post-open close did not.
            if (statusCode != WebSocket.NORMAL_CLOSURE) {
                var safe = reason == null ? "" : reason.replace("\\", "\\\\").replace("\"", "\\\"");
                publishDown(downSubject,
                    "{\"type\":\"error\",\"seq\":0,\"code\":\"tunnel_closed\",\"message\":\""
                        + safe + "\"}");
            }
            return null;
        }

        @Override public void onError(WebSocket ws, Throwable error) {
            cancelKeepalive(session);
            sessions.remove(session);
            log.warn("Tunnel session {} loopback error: {}", session, error.getMessage());
        }
    }
}
