package org.wyrdsekai.rendezvous;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the rendezvous process — spawns the real Javalin
 * server on a random port, hits it with an HTTP client, validates wire
 * shape. Catches issues unit tests can't: route registration, content
 * types, encoding, rate-limit responses, error shapes.
 */
class RendezvousMainTest {

    private static final String DID_A =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_B =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private RendezvousMain.Handle handle;
    private HttpClient http;
    private String baseUrl;

    @BeforeEach void setUp() {
        // Random port (0 → OS picks), tight rate limit for fast tests,
        // generous manifest cap, 1h TTL.
        handle = RendezvousMain.start(0, 1000, 50, 3600);
        baseUrl = "http://127.0.0.1:" + handle.port();
        http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    }

    @AfterEach void tearDown() {
        if (handle != null) handle.close();
    }

    private static ZoneManifestV1 manifest(String did, String label, List<String> tags) {
        return new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, did, label, "Display-" + label, null,
            "tagline", "description", tags, null, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-20T00:00:00Z", null);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(5))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(5))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test void health_returnsOk() throws Exception {
        var resp = get("/health");
        assertEquals(200, resp.statusCode());
        var node = MAPPER.readTree(resp.body());
        assertEquals("ok", node.path("status").asText());
        assertEquals(0, node.path("manifests").asInt());
    }

    @Test void publish_roundTrip() throws Exception {
        var m = manifest(DID_A, "kitchen", List.of("social"));
        var pub = post("/publish", m.toJsonString());
        assertEquals(200, pub.statusCode(), pub.body());
        var pubNode = MAPPER.readTree(pub.body());
        assertEquals("accepted", pubNode.path("status").asText());

        // GET /api/directory/{did}
        var get = get("/api/directory/" + URLEncoder.encode(DID_A,
            StandardCharsets.UTF_8));
        assertEquals(200, get.statusCode());
        var parsed = ZoneManifestV1.fromJsonString(get.body());
        assertEquals(DID_A, parsed.did());
        assertEquals("kitchen", parsed.zoneLabel());
    }

    @Test void publish_rateLimit_returns429() throws Exception {
        var m = manifest(DID_A, "kitchen", List.of());
        var first = post("/publish", m.toJsonString());
        assertEquals(200, first.statusCode());
        // Immediate second publish for same DID trips the rate limit (50ms window).
        var second = post("/publish", m.toJsonString());
        assertEquals(429, second.statusCode());
        assertTrue(second.body().contains("rate limit"));
    }

    @Test void publish_malformed_returns400() throws Exception {
        var resp = post("/publish", "{\"not_a_manifest\":true}");
        assertEquals(400, resp.statusCode());
    }

    @Test void publish_empty_returns400() throws Exception {
        var resp = post("/publish", "");
        assertEquals(400, resp.statusCode());
    }

    @Test void recent_returnsPublishedManifests() throws Exception {
        post("/publish", manifest(DID_A, "kitchen", List.of()).toJsonString());
        Thread.sleep(60);  // past rate-limit window
        post("/publish", manifest(DID_B, "garage",  List.of()).toJsonString());

        var resp = get("/api/directory/recent?limit=10");
        assertEquals(200, resp.statusCode());
        var node = MAPPER.readTree(resp.body());
        assertEquals(2, node.path("count").asInt());
        var manifests = node.path("manifests");
        assertTrue(manifests.isArray());
        assertEquals(2, manifests.size());
    }

    @Test void discoverByTag_filtersCorrectly() throws Exception {
        post("/publish", manifest(DID_A, "kitchen", List.of("social")).toJsonString());
        Thread.sleep(60);
        post("/publish", manifest(DID_B, "garage",  List.of("work")).toJsonString());

        var resp = get("/api/directory/tag/social");
        assertEquals(200, resp.statusCode());
        var node = MAPPER.readTree(resp.body());
        assertEquals(1, node.path("count").asInt());
        assertEquals(DID_A, node.path("dids").get(0).asText());
    }

    @Test void discoverByCapability_returnsMatchingDids() throws Exception {
        var caps = new ZoneManifestV1.Capabilities(
            null,
            List.of(new ZoneManifestV1.PublicRoom("library", "The Library", "Books.")),
            List.of(), Map.of(), Map.of());
        var m = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID_A, "kitchen", "K", null,
            "t", "d", List.of(), caps, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-20T00:00:00Z", null);
        post("/publish", m.toJsonString());

        var resp = get("/api/directory/capability/library");
        assertEquals(200, resp.statusCode());
        var node = MAPPER.readTree(resp.body());
        assertEquals(1, node.path("count").asInt());
    }

    @Test void search_returnsRankedHits() throws Exception {
        post("/publish", manifest(DID_A, "kitchen", List.of("social")).toJsonString());
        Thread.sleep(60);
        post("/publish", manifest(DID_B, "garage",  List.of("work")).toJsonString());

        var resp = get("/api/directory/search?q=kitchen");
        assertEquals(200, resp.statusCode());
        var node = MAPPER.readTree(resp.body());
        // "kitchen" matches DID_A's zoneLabel; search returns a ranked hit list.
        assertTrue(node.path("count").asInt() >= 1);
        var mode = node.path("mode").asText();
        assertTrue("keyword".equals(mode) || "hybrid".equals(mode),
            "mode should be keyword or hybrid, got: " + mode);
    }

    @Test void search_emptyQuery_returns400() throws Exception {
        var resp = get("/api/directory/search?q=");
        assertEquals(400, resp.statusCode());
    }

    @Test void tombstone_removesEntry() throws Exception {
        post("/publish", manifest(DID_A, "kitchen", List.of("social")).toJsonString());
        assertEquals(200, get("/api/directory/" + URLEncoder.encode(DID_A,
            StandardCharsets.UTF_8)).statusCode());

        var ts = post("/tombstone", "{\"did\":\"" + DID_A + "\"}");
        assertEquals(200, ts.statusCode());
        assertTrue(ts.body().contains("tombstoned"));

        assertEquals(404, get("/api/directory/" + URLEncoder.encode(DID_A,
            StandardCharsets.UTF_8)).statusCode());
    }

    @Test void unknown_did_returns404() throws Exception {
        var resp = get("/api/directory/did%3Awyrd%3Az6MkNope111111111111111111111111111111111111");
        assertEquals(404, resp.statusCode());
    }

    @Test void recent_limitRespected() throws Exception {
        post("/publish", manifest(DID_A, "kitchen", List.of()).toJsonString());
        Thread.sleep(60);
        post("/publish", manifest(DID_B, "garage",  List.of()).toJsonString());

        var resp = get("/api/directory/recent?limit=1");
        var node = MAPPER.readTree(resp.body());
        assertEquals(1, node.path("count").asInt());
    }

    @Test void endToEnd_publishNotifiesSubscriber() throws Exception {
        // This exercises the SubscriptionHub wiring without needing a real
        // SSE client — we register directly on the hub and verify the
        // change listener delivered the event when the publish route
        // flowed through store → listener → hub.
        var received = new CopyOnWriteArrayList<String>();
        var sink = new SubscriptionHub.SseSink() {
            private final long id = SubscriptionHub.nextSinkId();
            @Override public long id() { return id; }
            @Override public void send(String e, String d) { received.add(e + ":" + d); }
            @Override public void close() { /* no-op */ }
            @Override public boolean isClosed() { return false; }
        };
        handle.hub().subscribe(sink, new SubscriptionHub.Filter("social", null));

        post("/publish", manifest(DID_A, "kitchen", List.of("social")).toJsonString());

        // Dispatch is synchronous inside publish — no need to poll.
        assertEquals(1, received.size(), "SSE subscriber received the publish event");
        assertTrue(received.get(0).startsWith("added:"));
        assertTrue(received.get(0).contains(DID_A));
    }
}
