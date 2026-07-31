package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.naming.InMemoryZoneDirectory;
import org.wyrdsekai.core.naming.ZoneDirectory;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link DirectoryRoutes} via a real Javalin server on a
 * random port. Covers the happy path and error shapes for every
 * directory/{@code .well-known} endpoint that {@code Main.java}
 * registers.
 */
class DirectoryRoutesTest {

    private static final String DID_A =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_B =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AtomicReference<ZoneDirectory> directoryRef;
    private AtomicReference<ZoneManifestV1> manifestRef;
    private Javalin app;
    private HttpClient http;
    private String baseUrl;

    @BeforeEach void setUp() {
        directoryRef = new AtomicReference<>();
        manifestRef = new AtomicReference<>();
        var routes = new DirectoryRoutes(directoryRef::get, manifestRef::get);
        app = Javalin.create(routes::register).start("127.0.0.1", 0);
        baseUrl = "http://127.0.0.1:" + app.port();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach void tearDown() {
        if (app != null) app.stop();
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

    // ── .well-known/wyrd-zone ─────────────────────────────────────────

    @Test void wellKnownWyrdZone_404WhenNoLocalManifest() throws Exception {
        var resp = get("/.well-known/wyrd-zone");
        assertEquals(404, resp.statusCode());
    }

    @Test void wellKnownWyrdZone_returnsLocalManifest() throws Exception {
        manifestRef.set(manifest(DID_A, "kitchen", List.of("social")));
        var resp = get("/.well-known/wyrd-zone");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("content-type").orElse("")
            .contains("application/json"));
        var parsed = ZoneManifestV1.fromJsonString(resp.body());
        assertEquals(DID_A, parsed.did());
    }

    // ── .well-known/webfinger ─────────────────────────────────────────

    @Test void webfinger_missingResource_400() throws Exception {
        manifestRef.set(manifest(DID_A, "kitchen", List.of()));
        var resp = get("/.well-known/webfinger");
        assertEquals(400, resp.statusCode());
    }

    @Test void webfinger_nonAcctResource_400() throws Exception {
        manifestRef.set(manifest(DID_A, "kitchen", List.of()));
        var resp = get("/.well-known/webfinger?resource=https://example.com");
        assertEquals(400, resp.statusCode());
    }

    @Test void webfinger_noLocalManifest_404() throws Exception {
        var resp = get("/.well-known/webfinger?resource=acct:kitchen@host");
        assertEquals(404, resp.statusCode());
    }

    @Test void webfinger_wrongLabel_404() throws Exception {
        manifestRef.set(manifest(DID_A, "kitchen", List.of()));
        var resp = get("/.well-known/webfinger?resource=acct:garage@host");
        assertEquals(404, resp.statusCode());
    }

    @Test void webfinger_matchesLabel_returnsSelfLink() throws Exception {
        manifestRef.set(manifest(DID_A, "kitchen", List.of()));
        var resp = get("/.well-known/webfinger?resource=acct:kitchen@host");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("content-type").orElse("")
            .contains("application/jrd+json"));
        var node = MAPPER.readTree(resp.body());
        assertEquals("acct:kitchen@host", node.path("subject").asText());
        var self = node.path("links").get(0);
        assertEquals("self", self.path("rel").asText());
        assertTrue(self.path("href").asText().endsWith("/.well-known/wyrd-zone"));
    }

    @Test void webfinger_caseInsensitiveLabelMatch() throws Exception {
        manifestRef.set(manifest(DID_A, "kitchen", List.of()));
        var resp = get("/.well-known/webfinger?resource=acct:KITCHEN@host");
        assertEquals(200, resp.statusCode());
    }

    // ── /api/directory/recent ─────────────────────────────────────────

    @Test void recent_serviceUnavailable_whenDirectoryNotSet() throws Exception {
        var resp = get("/api/directory/recent");
        assertEquals(503, resp.statusCode());
    }

    @Test void recent_empty() throws Exception {
        directoryRef.set(new InMemoryZoneDirectory());
        var resp = get("/api/directory/recent");
        assertEquals(200, resp.statusCode());
        var node = MAPPER.readTree(resp.body());
        assertEquals(0, node.path("count").asInt());
    }

    @Test void recent_withPublishedManifests() throws Exception {
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of()));
        dir.publish(manifest(DID_B, "garage", List.of()));
        directoryRef.set(dir);

        var resp = get("/api/directory/recent");
        assertEquals(200, resp.statusCode());
        var node = MAPPER.readTree(resp.body());
        assertEquals(2, node.path("count").asInt());
    }

    @Test void recent_limitClamped() throws Exception {
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of()));
        dir.publish(manifest(DID_B, "garage", List.of()));
        directoryRef.set(dir);

        var resp = get("/api/directory/recent?limit=1");
        var node = MAPPER.readTree(resp.body());
        assertEquals(1, node.path("count").asInt());
    }

    // ── /api/directory/tag/{tag} ──────────────────────────────────────

    @Test void tag_returnsMatchingDids() throws Exception {
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of("social", "craft")));
        dir.publish(manifest(DID_B, "garage", List.of("work")));
        directoryRef.set(dir);

        var resp = get("/api/directory/tag/social");
        assertEquals(200, resp.statusCode());
        var node = MAPPER.readTree(resp.body());
        assertEquals("social", node.path("tag").asText());
        assertEquals(1, node.path("count").asInt());
        assertEquals(DID_A, node.path("dids").get(0).asText());
    }

    @Test void tag_unknownReturnsEmpty() throws Exception {
        directoryRef.set(new InMemoryZoneDirectory());
        var resp = get("/api/directory/tag/nope");
        assertEquals(200, resp.statusCode());
        var node = MAPPER.readTree(resp.body());
        assertEquals(0, node.path("count").asInt());
    }

    // ── /api/directory/capability/{name} ──────────────────────────────

    @Test void capability_returnsMatchingDids() throws Exception {
        var caps = new ZoneManifestV1.Capabilities(
            null,
            List.of(new ZoneManifestV1.PublicRoom("library", "The Library", "Books.")),
            List.of(), Map.of(), Map.of());
        var m = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID_A, "kitchen", "K", null,
            "t", "d", List.of(), caps, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-20T00:00:00Z", null);
        var dir = new InMemoryZoneDirectory();
        dir.publish(m);
        directoryRef.set(dir);

        var resp = get("/api/directory/capability/library");
        assertEquals(200, resp.statusCode());
        var node = MAPPER.readTree(resp.body());
        assertEquals("library", node.path("capability").asText());
        assertEquals(1, node.path("count").asInt());
    }

    // ── /api/directory/known-manifests ────────────────────────────────

    @Test void knownManifests_returnsSameShapeAsRecent() throws Exception {
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of()));
        directoryRef.set(dir);

        var resp = get("/api/directory/known-manifests?hops=2&limit=5");
        assertEquals(200, resp.statusCode());
        var node = MAPPER.readTree(resp.body());
        assertEquals(2, node.path("hops").asInt());
        assertEquals(1, node.path("count").asInt());
        assertTrue(node.path("manifests").isArray());
    }

    @Test void knownManifests_invalidHops_defaultsToOne() throws Exception {
        directoryRef.set(new InMemoryZoneDirectory());
        var resp = get("/api/directory/known-manifests?hops=notanumber");
        assertEquals(200, resp.statusCode());
        var node = MAPPER.readTree(resp.body());
        assertEquals(1, node.path("hops").asInt());
    }

    // ── /api/directory/{did} ──────────────────────────────────────────

    @Test void lookup_404WhenUnknown() throws Exception {
        directoryRef.set(new InMemoryZoneDirectory());
        var resp = get("/api/directory/"
            + URLEncoder.encode(DID_A, StandardCharsets.UTF_8));
        assertEquals(404, resp.statusCode());
    }

    @Test void lookup_returnsManifest() throws Exception {
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of()));
        directoryRef.set(dir);

        var resp = get("/api/directory/"
            + URLEncoder.encode(DID_A, StandardCharsets.UTF_8));
        assertEquals(200, resp.statusCode());
        var parsed = ZoneManifestV1.fromJsonString(resp.body());
        assertEquals(DID_A, parsed.did());
    }
}
