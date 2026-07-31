package org.wyrdsekai.core.nostr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.nostr.NostrRelayPool.NostrEventListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that exercises the subscribe/EVENT/EOSE path end-to-end
 * against a tiny in-process Nostr-protocol mock relay (just enough NIP-01 to
 * round-trip REQ → EVENT → EOSE).
 *
 * <p>Mock relay accepts WebSocket connections, parses REQ frames, and replies
 * with a single canned EVENT followed by EOSE.
 */
class NostrRelayPoolSubscribeTest {

    private MockNostrRelay mockRelay;
    private NostrRelayPool pool;
    private NostrKey signingKey;
    private NostrEvent cannedEvent;

    @BeforeEach void setup() throws Exception {
        signingKey = NostrKey.generate();
        cannedEvent = NostrEvent.buildAndSign(
            signingKey, 1, List.of(List.of("t", "wyrdsekai")),
            "hello from mock relay",
            System.currentTimeMillis() / 1000);
        mockRelay = new MockNostrRelay(cannedEvent);
        mockRelay.start();
        pool = new NostrRelayPool(List.of(mockRelay.wsUrl()));
        pool.start();
        // Wait for the connection to be established (best-effort, generous).
        for (int i = 0; i < 50; i++) {
            if (pool.stateSnapshot().get(mockRelay.wsUrl()) == NostrRelayPool.ConnState.OPEN) break;
            Thread.sleep(50);
        }
    }

    @AfterEach void teardown() {
        if (pool != null) pool.close();
        if (mockRelay != null) mockRelay.stop();
    }

    @Test void subscribe_receives_event_and_eose() throws Exception {
        var eventLatch = new CountDownLatch(1);
        var eoseLatch = new CountDownLatch(1);
        var receivedRelay = new AtomicReference<String>();
        var receivedEvent = new AtomicReference<NostrEvent>();

        var listener = new NostrEventListener() {
            @Override public void onEvent(String relay, String subId, NostrEvent event) {
                receivedRelay.set(relay);
                receivedEvent.set(event);
                eventLatch.countDown();
            }
            @Override public void onEose(String relay, String subId) {
                eoseLatch.countDown();
            }
        };

        var perRelay = pool.subscribe("sub-test-1",
            Map.of("kinds", List.of(1), "limit", 10), listener);

        // At least one relay should have accepted the REQ
        assertThat(perRelay.values()).contains(true);

        // Mock relay sends back one EVENT + EOSE; allow up to 5s
        assertThat(eventLatch.await(5, TimeUnit.SECONDS))
            .as("EVENT delivered within 5s").isTrue();
        assertThat(eoseLatch.await(5, TimeUnit.SECONDS))
            .as("EOSE delivered within 5s").isTrue();

        var ev = receivedEvent.get();
        assertThat(ev).isNotNull();
        assertThat(ev.id()).isEqualTo(cannedEvent.id());
        assertThat(ev.content()).isEqualTo("hello from mock relay");
        assertThat(ev.verify()).isTrue();
        assertThat(receivedRelay.get()).isEqualTo(mockRelay.wsUrl());

        // Cleanup
        pool.unsubscribe("sub-test-1");
    }

    @Test void event_with_bad_signature_is_dropped() throws Exception {
        // Build an event, then tamper its content to break the signature.
        // The mock relay will broadcast the bad event; the listener must NOT
        // receive it (the pool verifies before dispatching).
        var bad = new NostrEvent(
            cannedEvent.id(),    // stale id — won't match new content
            cannedEvent.pubkey(),
            cannedEvent.createdAt(),
            cannedEvent.kind(),
            cannedEvent.tags(),
            "TAMPERED CONTENT",
            cannedEvent.sig());

        // Swap the mock relay's canned event without restarting.
        mockRelay.setCannedEvent(bad);

        var eventLatch = new CountDownLatch(1);
        var eoseLatch = new CountDownLatch(1);
        var listener = new NostrEventListener() {
            @Override public void onEvent(String relay, String subId, NostrEvent event) {
                eventLatch.countDown();
            }
            @Override public void onEose(String relay, String subId) {
                eoseLatch.countDown();
            }
        };

        pool.subscribe("sub-bad-sig", Map.of("kinds", List.of(1)), listener);

        // EOSE should arrive (relay sends it after the EVENT regardless), but
        // the EVENT itself should be dropped due to verify() failing.
        assertThat(eoseLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(eventLatch.await(500, TimeUnit.MILLISECONDS))
            .as("Bad-signature event should be dropped").isFalse();

        pool.unsubscribe("sub-bad-sig");
    }

    // ─────────── mock relay ───────────

    /**
     * Minimal in-process Nostr relay for tests. Only implements the bare
     * minimum to verify the publish + subscribe paths:
     * - Accept WebSocket on ws://127.0.0.1:PORT
     * - On REQ, send back the canned event then EOSE
     * - On EVENT (publish), send back OK
     * Uses the JDK HttpServer to upgrade to WebSocket via a custom listener.
     *
     * <p>JDK doesn't ship a built-in WebSocket *server*. To avoid pulling in
     * Jetty/Tyrus/Netty for tests, we use the {@code HttpClient}'s built-in
     * server-side websocket support... which doesn't exist either. So this
     * is a hand-rolled minimal server using TCP sockets.
     */
    private static final class MockNostrRelay {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private volatile NostrEvent cannedEvent;
        private int port;
        private Thread wsThread;
        private ServerSocket wsSocket;
        private volatile boolean running = false;

        MockNostrRelay(NostrEvent canned) {
            this.cannedEvent = canned;
        }

        void setCannedEvent(NostrEvent ev) { this.cannedEvent = ev; }

        void start() throws IOException {
            wsSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
            port = wsSocket.getLocalPort();
            running = true;
            wsThread = new Thread(this::acceptLoop, "mock-nostr-relay");
            wsThread.setDaemon(true);
            wsThread.start();
        }

        void stop() {
            running = false;
            try { if (wsSocket != null) wsSocket.close(); } catch (Exception ignored) {}
            if (wsThread != null) wsThread.interrupt();
        }

        String wsUrl() { return "ws://127.0.0.1:" + port; }

        private void acceptLoop() {
            while (running) {
                try {
                    var client = wsSocket.accept();
                    new Thread(() -> handleClient(client), "mock-nostr-client").start();
                } catch (Exception e) {
                    if (running) e.printStackTrace();
                    return;
                }
            }
        }

        private void handleClient(Socket client) {
            try (client) {
                client.setSoTimeout(10000);
                var in = client.getInputStream();
                var out = client.getOutputStream();
                // Read HTTP upgrade request, perform handshake
                var req = readHttpRequest(in);
                var keyMatch = Pattern.compile(
                    "Sec-WebSocket-Key:\\s*([^\\s]+)",
                    Pattern.CASE_INSENSITIVE).matcher(req);
                if (!keyMatch.find()) return;
                var key = keyMatch.group(1).trim();
                var accept = wsAcceptKey(key);
                var resp = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                out.write(resp.getBytes());
                out.flush();
                // Now in WS frame mode
                while (running && !client.isClosed()) {
                    var frame = readWsFrame(in);
                    if (frame == null) break;
                    handleClientFrame(frame, out);
                }
            } catch (Exception e) {
                /* client disconnected */
            }
        }

        private void handleClientFrame(String text, OutputStream out) throws Exception {
            var node = MAPPER.readTree(text);
            if (!node.isArray() || node.size() < 1) return;
            var kind = node.get(0).asText();
            switch (kind) {
                case "REQ" -> {
                    var subId = node.get(1).asText();
                    // Send canned EVENT
                    var eventArr = MAPPER.createArrayNode();
                    eventArr.add("EVENT");
                    eventArr.add(subId);
                    eventArr.addPOJO(cannedEvent);
                    sendWsText(out, MAPPER.writeValueAsString(eventArr));
                    // Then EOSE
                    var eoseArr = MAPPER.createArrayNode();
                    eoseArr.add("EOSE");
                    eoseArr.add(subId);
                    sendWsText(out, MAPPER.writeValueAsString(eoseArr));
                }
                case "EVENT" -> {
                    var ev = node.get(1);
                    var okArr = MAPPER.createArrayNode();
                    okArr.add("OK");
                    okArr.add(ev.get("id").asText());
                    okArr.add(true);
                    okArr.add("");
                    sendWsText(out, MAPPER.writeValueAsString(okArr));
                }
                case "CLOSE" -> { /* ack, no reply */ }
                default -> { /* ignore */ }
            }
        }

        private static String readHttpRequest(InputStream in) throws IOException {
            var buf = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                buf.write(b);
                var s = buf.toString();
                if (s.endsWith("\r\n\r\n")) return s;
            }
            return buf.toString();
        }

        private static String wsAcceptKey(String key) throws Exception {
            var concat = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            var sha = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder().encodeToString(
                sha.digest(concat.getBytes(StandardCharsets.UTF_8)));
        }

        private static String readWsFrame(InputStream in) throws IOException {
            int b1 = in.read(); if (b1 < 0) return null;
            int b2 = in.read(); if (b2 < 0) return null;
            int opcode = b1 & 0x0F;
            if (opcode == 0x8) return null;  // close
            boolean masked = (b2 & 0x80) != 0;
            long len = b2 & 0x7F;
            if (len == 126) {
                len = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xff);
            }
            byte[] mask = new byte[4];
            if (masked) in.readNBytes(mask, 0, 4);
            byte[] payload = in.readNBytes((int) len);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            }
            if (opcode != 0x1) return "";  // we only handle text
            return new String(payload, StandardCharsets.UTF_8);
        }

        private static void sendWsText(OutputStream out, String text) throws IOException {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            out.write(0x81);  // FIN + text
            if (payload.length < 126) out.write(payload.length);
            else if (payload.length < 65536) {
                out.write(126);
                out.write((payload.length >> 8) & 0xff);
                out.write(payload.length & 0xff);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) out.write((int) ((payload.length >> (i * 8)) & 0xff));
            }
            out.write(payload);
            out.flush();
        }

    }
}
