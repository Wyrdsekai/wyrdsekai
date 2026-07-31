package org.wyrdsekai.core.naming;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FederatedZoneDirectoryTest {

    private static final String DID_A =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_B =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";

    private HttpServer server;
    private String baseUrl;

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
            ZoneManifestV1.SCHEMA_VERSION, did, label, "Name", null,
            "tag", "desc", tags, null, null, null, 0,
            "2026-01-15T00:00:00Z", refreshed, null);
    }

    /** Serve a canned /api/directory/recent response containing the given manifests. */
    private void serveRecent(ZoneManifestV1... manifests) {
        var bodyBuilder = new StringBuilder("{\"count\":")
            .append(manifests.length)
            .append(",\"manifests\":[");
        for (int i = 0; i < manifests.length; i++) {
            if (i > 0) bodyBuilder.append(',');
            bodyBuilder.append(manifests[i].toJsonString());
        }
        bodyBuilder.append("]}");
        var body = bodyBuilder.toString();
        server.createContext("/api/directory/recent", ex -> {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (var os = ex.getResponseBody()) { os.write(bytes); }
        });
    }

    @Test void pullOnce_ingestsManifestsFromPeer() {
        var m = manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", List.of("social"));
        serveRecent(m);

        var dir = new FederatedZoneDirectory(() -> List.of(baseUrl));
        dir.pullOnce();

        assertEquals(1, dir.cacheSize());
        assertEquals(m.did(), dir.lookup(DID_A).orElseThrow().did());
        assertEquals(List.of(DID_A), dir.discoverByTag("social"));
    }

    @Test void pullOnce_multiPeer_mergesAll() throws IOException {
        var m1 = manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", List.of("social"));
        var m2 = manifest(DID_B, "garage", "2026-04-19T00:00:00Z", List.of("work"));

        // Peer A serves m1, peer B serves m2 — start a second small server.
        server.createContext("/api/directory/recent", ex -> {
            var body = "{\"count\":1,\"manifests\":[" + m1.toJsonString() + "]}";
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (var os = ex.getResponseBody()) { os.write(bytes); }
        });

        var server2 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server2.createContext("/api/directory/recent", ex -> {
            var body = "{\"count\":1,\"manifests\":[" + m2.toJsonString() + "]}";
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (var os = ex.getResponseBody()) { os.write(bytes); }
        });
        server2.start();
        var url2 = "http://127.0.0.1:" + server2.getAddress().getPort();

        try {
            var dir = new FederatedZoneDirectory(() -> List.of(baseUrl, url2));
            dir.pullOnce();
            assertEquals(2, dir.cacheSize());
            assertTrue(dir.lookup(DID_A).isPresent());
            assertTrue(dir.lookup(DID_B).isPresent());
        } finally {
            server2.stop(0);
        }
    }

    @Test void pullOnce_deadPeer_skippedSilently() {
        var m = manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", List.of());
        serveRecent(m);

        // One live peer + one dead URL on a port where nothing is listening.
        var dir = new FederatedZoneDirectory(
            () -> List.of(baseUrl, "http://127.0.0.1:1"));
        dir.pullOnce();

        assertEquals(1, dir.cacheSize(),
            "dead peer must not block ingest from live peer");
    }

    @Test void pullOnce_supplierThrows_silentlySkipped() {
        var dir = new FederatedZoneDirectory(() -> {
            throw new RuntimeException("boom");
        });
        // Must not throw.
        assertDoesNotThrow(dir::pullOnce);
        assertEquals(0, dir.cacheSize());
    }

    @Test void pullOnce_malformedManifest_skippedKeepOthers() throws IOException {
        var good = manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", List.of());
        var body = "{\"count\":2,\"manifests\":["
            + good.toJsonString()
            + ",{\"nope\":true,\"schema_version\":\"wrong\"}"
            + "]}";
        server.createContext("/api/directory/recent", ex -> {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (var os = ex.getResponseBody()) { os.write(bytes); }
        });
        var dir = new FederatedZoneDirectory(() -> List.of(baseUrl));
        dir.pullOnce();
        assertEquals(1, dir.cacheSize(),
            "malformed manifest skipped; good one kept");
    }

    @Test void publish_isNoOp() {
        var dir = new FederatedZoneDirectory(() -> List.of(baseUrl));
        dir.publish(manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", List.of()));
        assertEquals(0, dir.cacheSize(),
            "publish is a no-op — this backend only ingests from peers");
    }

    @Test void supplierCalledEachPullTick() {
        var counter = new AtomicInteger();
        var m = manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", List.of());
        serveRecent(m);

        var dir = new FederatedZoneDirectory(() -> {
            counter.incrementAndGet();
            return List.of(baseUrl);
        });
        dir.pullOnce();
        dir.pullOnce();
        dir.pullOnce();
        assertEquals(3, counter.get(),
            "supplier must be consulted each tick so operators can add/remove peers at runtime");
    }

    @Test void recent_sortedByRefreshedAt() {
        var older = manifest(DID_A, "kitchen", "2026-01-01T00:00:00Z", List.of());
        var newer = manifest(DID_B, "garage", "2026-04-19T00:00:00Z", List.of());
        serveRecent(older, newer);

        var dir = new FederatedZoneDirectory(() -> List.of(baseUrl));
        dir.pullOnce();
        var list = dir.recent(10);
        assertEquals(2, list.size());
        assertEquals(DID_B, list.get(0).did(), "newest first");
    }
}
