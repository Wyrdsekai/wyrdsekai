package org.wyrdsekai.core.agent.channels;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live-verify of {@link MatrixChannel} against an in-process fake
 * homeserver. This exercises the full HTTP request/response path that
 * runs against matrix.org in production, without needing a real bot
 * account. Validates:
 *
 * <ul>
 *   <li>{@code PUT /_matrix/client/v3/rooms/.../send/m.room.message/{txnId}}
 *       gets called with the correct body, auth header, txnId.</li>
 *   <li>{@code GET /_matrix/client/v3/sync} polls and processes events,
 *       advancing the next_batch token persisted via
 *       {@link ChannelStateStore}.</li>
 *   <li>{@code GET /_matrix/client/v3/account/whoami} resolves the bot's
 *       own user_id for self-echo filtering.</li>
 * </ul>
 *
 * <p>Tagged as a contract test: if matrix.org changes the protocol, the
 * MatrixChannel test suite (this + MatrixChannelTest) will catch it
 * before live deployment surprises us.</p>
 */
class MatrixChannelLiveVerifyTest {

    @AfterEach
    void cleanup() { ChannelStateStore.resetForTests(); }

    @Test
    void send_executes_full_http_round_trip(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        var receivedAuth = new AtomicReference<String>();
        var receivedPath = new AtomicReference<String>();
        var receivedBody = new AtomicReference<String>();

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_matrix/client/v3/rooms/", exchange -> {
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedPath.set(exchange.getRequestURI().getPath());
            try (var in = exchange.getRequestBody()) {
                receivedBody.set(new String(in.readAllBytes()));
            }
            var resp = "{\"event_id\":\"$abc:fake\"}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (var os = exchange.getResponseBody()) { os.write(resp); }
        });
        server.start();
        var baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        try {
            var ch = new MatrixChannel(baseUrl, "tok-xyz", "!room:fake.org");
            var ok = ch.send("hello world", "high", "wyrd", "https://example.test/x")
                .get(5, TimeUnit.SECONDS);
            assertThat(ok).isTrue();
            assertThat(receivedAuth.get()).isEqualTo("Bearer tok-xyz");
            assertThat(receivedPath.get()).contains("/send/m.room.message/wyrd-");
            assertThat(receivedBody.get()).contains("[wyrd] hello world");
            assertThat(receivedBody.get()).contains("https://example.test/x");
            assertThat(receivedBody.get()).contains("\"msgtype\":\"m.text\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void send_returns_false_on_non_200_response(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_matrix", exchange -> {
            // Drain body so the connection completes cleanly.
            try (var in = exchange.getRequestBody()) { in.readAllBytes(); }
            var body = "{\"errcode\":\"M_FORBIDDEN\",\"error\":\"bad token\"}".getBytes();
            exchange.sendResponseHeaders(403, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
        var baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        try {
            var ch = new MatrixChannel(baseUrl, "bogus", "!room:fake.org");
            var ok = ch.send("hello", "high", "wyrd", null)
                .get(5, TimeUnit.SECONDS);
            assertThat(ok).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listener_polls_sync_and_persists_next_batch(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        var syncRequests = new AtomicInteger();
        var seenSinceTokens = new ArrayList<String>();
        var firstSyncReceived = new CountDownLatch(1);
        var secondSyncReceived = new CountDownLatch(1);

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_matrix/client/v3/account/whoami", exchange -> {
            var body = "{\"user_id\":\"@bot:fake.org\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.createContext("/_matrix/client/v3/sync", exchange -> {
            var query = exchange.getRequestURI().getQuery();
            String since = null;
            if (query != null) {
                for (var part : query.split("&")) {
                    if (part.startsWith("since=")) since = part.substring(6);
                }
            }
            synchronized (seenSinceTokens) { seenSinceTokens.add(since); }
            int n = syncRequests.incrementAndGet();
            String body;
            if (n == 1) {
                // First /sync: events arrive, next_batch token = TOKEN-A
                body = """
                    {
                      "next_batch": "TOKEN-A",
                      "rooms": {"join": {"!room:fake.org": {
                        "timeline": {"events": [
                          {"type":"m.room.message","event_id":"$first:fake.org",
                           "sender":"@alice:fake.org",
                           "content":{"msgtype":"m.text","body":"hi from alice"}}
                        ]}
                      }}}
                    }
                    """;
                firstSyncReceived.countDown();
            } else {
                // Subsequent /sync calls return empty + new token
                body = "{\"next_batch\":\"TOKEN-B\",\"rooms\":{\"join\":{}}}";
                if (n == 2) secondSyncReceived.countDown();
            }
            var bytes = body.getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        var baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        try {
            var ch = new MatrixChannel(baseUrl, "tok", "!room:fake.org");
            ch.startListener("wyrd");

            // Wait for two /sync round-trips (so we know the next_batch
            // token is being threaded through correctly).
            assertThat(firstSyncReceived.await(8, TimeUnit.SECONDS))
                .as("first /sync arrived").isTrue();
            assertThat(secondSyncReceived.await(8, TimeUnit.SECONDS))
                .as("second /sync arrived").isTrue();

            // The persisted offset will have advanced to either TOKEN-A
            // (after first sync) or TOKEN-B (after second). Either way the
            // checkpoint is being threaded through correctly.
            var persistedOffset = store.readOffset("matrix", "!room:fake.org");
            assertThat(persistedOffset).isPresent();
            assertThat(persistedOffset.get()).isIn("TOKEN-A", "TOKEN-B");

            // The second /sync should have carried since=TOKEN-A.
            synchronized (seenSinceTokens) {
                assertThat(seenSinceTokens).hasSizeGreaterThanOrEqualTo(2);
                assertThat(seenSinceTokens.get(0)).isNull();      // initial sync
                assertThat(seenSinceTokens.get(1)).isEqualTo("TOKEN-A");
            }

            // (Dedup mark-processed only happens when AgentEventStream +
            // EntityRegistry are wired. The processSync unit test in
            // MatrixChannelTest covers the dedup path independently.)

            ch.stopListener();
        } finally {
            server.stop(0);
        }
    }
}
