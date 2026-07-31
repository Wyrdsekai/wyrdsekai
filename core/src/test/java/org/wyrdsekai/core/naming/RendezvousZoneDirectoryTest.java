package org.wyrdsekai.core.naming;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the client-side {@link RendezvousZoneDirectory} against a
 * scripted in-process HTTP server playing the rendezvous role. We keep
 * the server deliberately stupid so the test is a contract check: does
 * the client POST/GET the right shapes, and does the merge logic work.
 */
class RendezvousZoneDirectoryTest {

    private static final String DID_A =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_B =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";

    private HttpServer server;
    private String baseUrl;
    private final CopyOnWriteArrayList<String> publishedBodies = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> tombstonedBodies = new CopyOnWriteArrayList<>();

    @BeforeEach void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stopServer() {
        if (server != null) server.stop(0);
    }

    private static ZoneManifestV1 manifest(String did, String label, String refreshed,
                                            List<String> tags) {
        return new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, did, label, "Display", null,
            "tag", "desc", tags, null, null, null, 0,
            "2026-01-15T00:00:00Z", refreshed, null);
    }

    private void serve(String path, Consumer<HttpExchange> handler) {
        server.createContext(path, ex -> {
            try { handler.accept(ex); }
            finally { ex.close(); }
        });
    }

    private void reply(HttpExchange ex, int status, String body) {
        try {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (var os = ex.getResponseBody()) { os.write(bytes); }
        } catch (Exception e) { /* test-only */ }
    }

    @Test void publish_postsManifestAsJson() {
        serve("/publish", ex -> {
            try {
                var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                publishedBodies.add(body);
                reply(ex, 200, "{\"status\":\"accepted\"}");
            } catch (Exception e) { reply(ex, 500, "{}"); }
        });

        var dir = new RendezvousZoneDirectory(() -> List.of(baseUrl));
        dir.publish(manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", List.of("social")));

        assertEquals(1, publishedBodies.size());
        assertTrue(publishedBodies.get(0).contains(DID_A));
        assertTrue(publishedBodies.get(0).contains("social"));
    }

    @Test void publish_fansOutToAllRendezvous() throws IOException {
        serve("/publish", ex -> {
            try {
                publishedBodies.add(new String(ex.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
                reply(ex, 200, "{\"status\":\"accepted\"}");
            } catch (Exception e) { reply(ex, 500, "{}"); }
        });

        var server2 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var seen2 = new CopyOnWriteArrayList<String>();
        server2.createContext("/publish", ex -> {
            try {
                seen2.add(new String(ex.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
                reply(ex, 200, "{\"status\":\"accepted\"}");
            } finally { ex.close(); }
        });
        server2.start();
        var url2 = "http://127.0.0.1:" + server2.getAddress().getPort();

        try {
            var dir = new RendezvousZoneDirectory(() -> List.of(baseUrl, url2));
            dir.publish(manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", List.of()));
            assertEquals(1, publishedBodies.size(), "rendezvous 1 received");
            assertEquals(1, seen2.size(), "rendezvous 2 received");
        } finally {
            server2.stop(0);
        }
    }

    @Test void publish_oneRendezvousDown_othersStillGetIt() {
        serve("/publish", ex -> {
            try {
                publishedBodies.add(new String(ex.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
                reply(ex, 200, "{\"status\":\"accepted\"}");
            } catch (Exception e) { reply(ex, 500, "{}"); }
        });

        // Live rendezvous + a dead one on an unused port.
        var dir = new RendezvousZoneDirectory(
            () -> List.of(baseUrl, "http://127.0.0.1:1"));
        assertDoesNotThrow(() -> dir.publish(
            manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", List.of())));
        assertEquals(1, publishedBodies.size(),
            "dead rendezvous must not block live one");
    }

    @Test void unpublish_postsTombstone() {
        serve("/tombstone", ex -> {
            try {
                tombstonedBodies.add(new String(ex.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
                reply(ex, 200, "{\"status\":\"tombstoned\"}");
            } catch (Exception e) { reply(ex, 500, "{}"); }
        });

        var dir = new RendezvousZoneDirectory(() -> List.of(baseUrl));
        dir.unpublish(DID_A);
        assertEquals(1, tombstonedBodies.size());
        assertTrue(tombstonedBodies.get(0).contains(DID_A));
    }

    @Test void lookup_returnsManifestFromRendezvous() {
        var m = manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", List.of());
        // Path prefix — matches any /api/directory/... URL. The URI path
        // contains the URL-encoded DID which has percent escapes; the
        // HttpServer prefix matcher compares the raw request line, so
        // using the prefix pattern is simpler than exact-encoded matching.
        serve("/api/directory/", ex -> reply(ex, 200, m.toJsonString()));

        var dir = new RendezvousZoneDirectory(() -> List.of(baseUrl));
        var opt = dir.lookup(DID_A);
        assertTrue(opt.isPresent());
        assertEquals("kitchen", opt.get().zoneLabel());
    }

    @Test void lookup_404ReturnsEmpty() {
        var dir = new RendezvousZoneDirectory(() -> List.of(baseUrl));
        assertTrue(dir.lookup(DID_A).isEmpty());
    }

    @Test void discoverByTag_mergesAcrossRendezvous() throws IOException {
        serve("/api/directory/tag/social", ex ->
            reply(ex, 200, "{\"tag\":\"social\",\"count\":1,\"dids\":[\"" + DID_A + "\"]}"));
        var server2 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server2.createContext("/api/directory/tag/social", ex -> {
            try {
                reply(ex, 200, "{\"tag\":\"social\",\"count\":1,\"dids\":[\"" + DID_B + "\"]}");
            } finally { ex.close(); }
        });
        server2.start();
        var url2 = "http://127.0.0.1:" + server2.getAddress().getPort();

        try {
            var dir = new RendezvousZoneDirectory(() -> List.of(baseUrl, url2));
            var dids = dir.discoverByTag("social");
            assertEquals(2, dids.size(), "must union DIDs from all rendezvous");
            assertTrue(dids.contains(DID_A));
            assertTrue(dids.contains(DID_B));
        } finally {
            server2.stop(0);
        }
    }

    @Test void recent_dedupesNewestRefreshedAtWins() throws IOException {
        var older = manifest(DID_A, "kitchen", "2026-01-01T00:00:00Z", List.of());
        var newer = manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", List.of());

        serve("/api/directory/recent", ex ->
            reply(ex, 200, "{\"count\":1,\"manifests\":[" + older.toJsonString() + "]}"));
        var server2 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server2.createContext("/api/directory/recent", ex -> {
            try {
                reply(ex, 200, "{\"count\":1,\"manifests\":[" + newer.toJsonString() + "]}");
            } finally { ex.close(); }
        });
        server2.start();
        var url2 = "http://127.0.0.1:" + server2.getAddress().getPort();

        try {
            var dir = new RendezvousZoneDirectory(() -> List.of(baseUrl, url2));
            var list = dir.recent(10);
            assertEquals(1, list.size(), "dedupes same DID across rendezvous");
            assertEquals("2026-04-19T00:00:00Z", list.get(0).refreshedAt(),
                "newer refreshedAt wins");
        } finally {
            server2.stop(0);
        }
    }

    @Test void emptyRendezvousList_returnsSensibleDefaults() {
        var dir = new RendezvousZoneDirectory(Collections::emptyList);
        assertTrue(dir.lookup(DID_A).isEmpty());
        assertTrue(dir.discoverByTag("x").isEmpty());
        assertTrue(dir.discoverByCapability("x").isEmpty());
        assertTrue(dir.recent(10).isEmpty());
        assertDoesNotThrow(() -> dir.publish(
            manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", List.of())));
    }

    @Test void supplierThrows_silentlySkipped() {
        var dir = new RendezvousZoneDirectory(() -> {
            throw new RuntimeException("boom");
        });
        assertDoesNotThrow(() -> dir.publish(
            manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", List.of())));
        assertTrue(dir.lookup(DID_A).isEmpty());
    }
}
