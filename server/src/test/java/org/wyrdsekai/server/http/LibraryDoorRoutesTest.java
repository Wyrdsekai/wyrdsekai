package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.library.LibraryProvider;
import org.wyrdsekai.core.library.LibraryReaders;
import org.wyrdsekai.core.library.LibraryWebhook;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The household's library as a peer librarian speaks it: JSON routes at /v1 with a bearer token
 * (contract 1.5 peers ask over /v1/ask, one hop), and the door where a librarian pushes its changes.
 */
class LibraryDoorRoutesTest {

    private static final ObjectMapper M = new ObjectMapper();
    @TempDir Path dir;
    private WyrdLuceneStore store;
    private Javalin app;
    private String base;
    private LibraryReaders readers;
    private String writerToken, readerToken;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach void setUp() throws Exception {
        store = new WyrdLuceneStore(dir.resolve("idx"), 384);
        store.ensureAllCollections();
        store.insertKnowledge("wiki:1", "simple-wikipedia", "Obsidian", "Obsidian is a naturally occurring volcanic glass.", "simple-wikipedia", "geology", null);
        store.insertKnowledge("shelf:1", "study-share-books", "A private page", "Something from the steward's own shelf.", "study-share-books", "fiction", null);
        store.commitAll();
        readers = new LibraryReaders(dir.resolve("data"));
        writerToken = readers.issue("alice's librarian", "did:key:zAlice", LibraryReaders.Level.write);
        readerToken = readers.issue("the lab", "", LibraryReaders.Level.read);
        var provider = new LibraryProvider(store, "zone-1", "Test library", "did:key:zMia", pack -> "simple-wikipedia".equals(pack));
        var door = new LibraryDoorRoutes(provider, readers);
        var hooks = new LibraryWebhookRoutes(store, () -> List.of(Map.entry("did:key:zMia", "mia")), id -> "researchzosho".equals(id) ? "s3cret" : null);
        app = Javalin.create(cfg -> { door.register(cfg.routes); hooks.register(cfg.routes); }).start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach void tearDown() throws Exception { app.stop(); store.close(); }

    private HttpResponse<String> post(String route, String body, String token, Map<String, String> headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + route)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void a_peer_asks_over_v1_with_its_token_and_gets_the_package_shape() throws Exception {
        var r = post("/v1/ask", "{\"question\":\"obsidian\",\"k\":3,\"peers\":\"none\",\"patron\":{\"runtime\":\"researchzosho\"}}", writerToken, Map.of());
        assertEquals(200, r.statusCode(), r.body());
        var j = M.readTree(r.body());
        assertEquals("zone-1", j.path("library_id").asText());
        assertEquals("1.0", j.path("contract").asText());
        assertEquals("wiki:1", j.path("entries").get(0).path("id").asText());
        var status = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/status")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, status.statusCode());
        assertEquals("Test library", M.readTree(status.body()).path("library_name").asText());
    }

    @Test
    void the_license_gate_still_decides_what_leaves_and_absence_is_honest() throws Exception {
        var r = post("/v1/ask", "{\"question\":\"private page steward\"}", null, Map.of());
        assertEquals(200, r.statusCode());
        var j = M.readTree(r.body());
        assertTrue(j.path("holds_nothing").asBoolean(false), r.body());
        assertEquals(0, j.path("entries").size());
    }

    @Test
    void identity_is_proved_not_asserted() throws Exception {
        assertEquals(403, post("/v1/ask", "{\"question\":\"obsidian\"}", "not-a-token", Map.of()).statusCode(), "a bad token is refused, not downgraded");
        assertEquals(403, post("/v1/ask", "{\"question\":\"obsidian\",\"patron\":{\"did\":\"did:key:zSomeone\"}}", null, Map.of()).statusCode(), "a did without a token is refused");
        assertEquals(403, post("/v1/ask", "{\"question\":\"obsidian\",\"patron\":{\"did\":\"did:key:zOther\"}}", writerToken, Map.of()).statusCode(), "a did the token does not prove is refused");
        assertEquals(200, post("/v1/ask", "{\"question\":\"obsidian\",\"patron\":{\"did\":\"did:key:zAlice\",\"runtime\":\"researchzosho\"}}", writerToken, Map.of()).statusCode(), "the token's own did is fine");
        var submitBody = "{\"claim\":\"Obsidian is a naturally occurring volcanic glass formed from lava.\",\"claim_type\":\"extraction\",\"sources\":[{\"locator\":\"https://example.org/obsidian\"}]}";
        assertEquals(403, post("/v1/submit", submitBody, null, Map.of()).statusCode(), "anonymous cannot submit");
        assertEquals(403, post("/v1/submit", submitBody, readerToken, Map.of()).statusCode(), "a reader cannot submit");
        var ok = post("/v1/submit", submitBody, writerToken, Map.of());
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals("draft", M.readTree(ok.body()).path("state").asText());
        assertEquals(422, post("/v1/submit", "{\"claim\":\"A claim long enough to be refused for lacking sources.\",\"sources\":[]}", writerToken, Map.of()).statusCode());
        assertEquals(400, post("/v1/ask", "not json", null, Map.of()).statusCode());
        assertEquals(404, post("/v1/get", "{\"id\":\"finding:nope\"}", null, Map.of()).statusCode());
    }

    @Test
    void a_pushed_change_is_verified_and_a_bad_signature_is_ignored() throws Exception {
        var body = "{\"library_id\":\"lib_x\",\"library_name\":\"The Stacks\",\"change\":{\"seq\":7,\"at\":\"2026-09-08T10:00:00Z\",\"kind\":\"finding\",\"id\":\"F-0007-x\",\"event\":\"state:accepted→retired\",\"detail\":\"\"}}";
        var sig = "sha256=" + LibraryWebhook.hmac("s3cret", body.getBytes(StandardCharsets.UTF_8));
        var ok = post("/api/library/webhook/researchzosho", body, null, Map.of(LibraryWebhook.SIGNATURE_HEADER, sig));
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals(7, M.readTree(ok.body()).path("seq").asInt());
        assertEquals(401, post("/api/library/webhook/researchzosho", body, null, Map.of(LibraryWebhook.SIGNATURE_HEADER, "sha256=" + "0".repeat(64))).statusCode());
        assertEquals(401, post("/api/library/webhook/researchzosho", body, null, Map.of()).statusCode(), "unsigned: refused");
        assertEquals(404, post("/api/library/webhook/unknown", body, null, Map.of(LibraryWebhook.SIGNATURE_HEADER, sig)).statusCode(), "no subscription, no door");
        assertEquals(400, post("/api/library/webhook/researchzosho", "{\"hello\":1}", null,
            Map.of(LibraryWebhook.SIGNATURE_HEADER, "sha256=" + LibraryWebhook.hmac("s3cret", "{\"hello\":1}".getBytes(StandardCharsets.UTF_8)))).statusCode());
    }
}
