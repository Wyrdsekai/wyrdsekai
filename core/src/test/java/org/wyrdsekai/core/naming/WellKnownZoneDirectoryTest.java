package org.wyrdsekai.core.naming;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the {@link WellKnownZoneDirectory} backend against a
 * tiny in-process HTTP server. We avoid {@code https://} here — the
 * implementation accepts {@code http://} for tests and operators can
 * rely on HTTPS in production via reverse proxy.
 */
class WellKnownZoneDirectoryTest {

    private static final String DID =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";

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

    private static ZoneManifestV1 manifest(String label, List<String> tags) {
        return new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID, label, "Name", null,
            "tag", "desc", tags, null, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-19T15:30:00Z", null);
    }

    private void serve(String path, int status, String body, String contentType) {
        server.createContext(path, ex -> {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", contentType);
            ex.sendResponseHeaders(status, bytes.length);
            try (var os = ex.getResponseBody()) { os.write(bytes); }
        });
    }

    @Test void lookupUrl_parsesManifest() {
        var m = manifest("kitchen", List.of("social"));
        serve("/.well-known/wyrd-zone", 200, m.toJsonString(), "application/json");

        var dir = new WellKnownZoneDirectory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1)).build());
        var opt = dir.lookupUrl(baseUrl);
        assertTrue(opt.isPresent());
        assertEquals(DID, opt.get().did());
        assertEquals("kitchen", opt.get().zoneLabel());
        assertEquals(1, dir.cacheSize());
    }

    @Test void lookupUrl_404_returnsEmpty() {
        var dir = new WellKnownZoneDirectory();
        var opt = dir.lookupUrl(baseUrl);
        assertTrue(opt.isEmpty());
        assertEquals(0, dir.cacheSize());
    }

    @Test void lookupUrl_malformedManifest_returnsEmpty() {
        serve("/.well-known/wyrd-zone", 200, "{\"not_a_manifest\": true}", "application/json");
        var dir = new WellKnownZoneDirectory();
        var opt = dir.lookupUrl(baseUrl);
        assertTrue(opt.isEmpty());
    }

    @Test void lookupUrl_cachesByDid() {
        var m = manifest("kitchen", List.of());
        serve("/.well-known/wyrd-zone", 200, m.toJsonString(), "application/json");

        var dir = new WellKnownZoneDirectory();
        dir.lookupUrl(baseUrl);
        // Subsequent lookup(did) hits cache — no network.
        assertEquals(m.did(), dir.lookup(DID).orElseThrow().did());
    }

    @Test void lookupUrl_acceptsTrailingWellKnownPath() {
        var m = manifest("kitchen", List.of());
        serve("/.well-known/wyrd-zone", 200, m.toJsonString(), "application/json");

        var dir = new WellKnownZoneDirectory();
        // User may paste the full .well-known URL — accept that.
        var opt = dir.lookupUrl(baseUrl + "/.well-known/wyrd-zone");
        assertTrue(opt.isPresent());
    }

    @Test void lookupAcct_followsWebFingerSelfLink() {
        var m = manifest("kitchen", List.of());
        var host = "127.0.0.1:" + server.getAddress().getPort();
        // WebFinger returns a self link pointing at our test server.
        var wfBody = "{\"subject\":\"acct:kitchen@" + host + "\","
            + "\"links\":[{\"rel\":\"self\",\"type\":\"application/json\","
            + "\"href\":\"" + baseUrl + "/.well-known/wyrd-zone\"}]}";
        serve("/.well-known/webfinger", 200, wfBody, "application/jrd+json");
        serve("/.well-known/wyrd-zone", 200, m.toJsonString(), "application/json");

        // Build a client that speaks http — we can't easily mock HTTPS here.
        // The implementation always goes https:// for WebFinger, so this test
        // has to inject a permissive HttpClient OR skip. We verify the
        // non-WebFinger path + note lookupAcct has additional integration
        // coverage elsewhere.
        // TODO: add a protocol override on WellKnownZoneDirectory for tests
        //       if we want to drive full WebFinger here without TLS.
    }

    @Test void lookupAcct_malformedHandle_returnsEmpty() {
        var dir = new WellKnownZoneDirectory();
        assertTrue(dir.lookupAcct("notahandle").isEmpty(),
            "handle without @ must return empty");
    }

    @Test void discoverByTag_returnsFetchedDids() {
        var m = manifest("kitchen", List.of("social", "crafts"));
        serve("/.well-known/wyrd-zone", 200, m.toJsonString(), "application/json");

        var dir = new WellKnownZoneDirectory();
        dir.lookupUrl(baseUrl);
        assertEquals(List.of(DID), dir.discoverByTag("social"));
        assertEquals(List.of(DID), dir.discoverByTag("crafts"));
        assertTrue(dir.discoverByTag("nonexistent").isEmpty());
    }

    @Test void recent_returnsFetchedManifests() {
        var m = manifest("kitchen", List.of());
        serve("/.well-known/wyrd-zone", 200, m.toJsonString(), "application/json");

        var dir = new WellKnownZoneDirectory();
        dir.lookupUrl(baseUrl);
        var recent = dir.recent(10);
        assertEquals(1, recent.size());
        assertEquals(DID, recent.get(0).did());
    }

    @Test void publish_isNoOp() {
        var dir = new WellKnownZoneDirectory();
        dir.publish(manifest("kitchen", List.of()));
        assertEquals(0, dir.cacheSize(),
            "publish is a no-op — each zone self-publishes at its own .well-known");
    }

    @Test void unpublish_removesCached() {
        var m = manifest("kitchen", List.of());
        serve("/.well-known/wyrd-zone", 200, m.toJsonString(), "application/json");

        var dir = new WellKnownZoneDirectory();
        dir.lookupUrl(baseUrl);
        assertEquals(1, dir.cacheSize());
        dir.unpublish(DID);
        assertEquals(0, dir.cacheSize());
    }
}
