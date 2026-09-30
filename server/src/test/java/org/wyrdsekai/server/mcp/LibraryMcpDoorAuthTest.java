package org.wyrdsekai.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.library.LibraryProvider;
import org.wyrdsekai.core.library.LibraryReaders;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POST /mcp/library served anyone, and library_submit took the patron from the body
 * (2026-09-28 audit). Now every request proves who it is with a reader token or a household
 * session, and the patron is the one proved.
 */
@Tag("integration")
class LibraryMcpDoorAuthTest {

    private static final ObjectMapper M = new ObjectMapper();
    @TempDir Path dir;
    private WyrdLuceneStore store;
    private Javalin app;
    private String url;
    private String readerToken;
    private String memberSession;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        store = new WyrdLuceneStore(dir.resolve("idx"), 384);
        store.ensureAllCollections();
        store.insertKnowledge("wiki:1", "simple-wikipedia", "Obsidian",
            "Obsidian is a naturally occurring volcanic glass.", "simple-wikipedia", "geology", null);
        store.commitAll();
        var readers = new LibraryReaders(dir.resolve("data"));
        readerToken = readers.issue("the lab", "did:key:zLab", LibraryReaders.Level.read);
        var auth = new AuthService(SchemaInitializer.initialize(dir.resolve("world.db")));
        auth.register("sam", "password1", "Sam").orElseThrow();              // first user: steward
        memberSession = auth.register("kai", "password2", "Kai").orElseThrow().token();
        var registry = new McpToolRegistry(false);
        LibraryTools.register(registry, new LibraryProvider(store, "zone-1", "Test library", "did:key:zMia",
            pack -> "simple-wikipedia".equals(pack)));
        var door = new McpEndpoint(null, registry, "/mcp/library", LibraryTools.callers(readers, auth));
        app = Javalin.create(cfg -> door.register(cfg.routes)).start("127.0.0.1", 0);
        url = "http://127.0.0.1:" + app.port() + "/mcp/library";
    }

    @AfterEach
    void tearDown() throws Exception {
        app.stop();
        store.close();
    }

    private HttpResponse<String> call(String token, String tool, String arguments) throws Exception {
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
            + tool + "\",\"arguments\":" + arguments + "}}";
        var b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void without_an_identity_the_door_refuses() throws Exception {
        assertEquals(401, call(null, "library_ask", "{\"question\":\"obsidian\"}").statusCode());
        assertEquals(401, call("not-a-token", "library_ask", "{\"question\":\"obsidian\"}").statusCode());
        var submit = call(null, "library_submit",
            "{\"claim\":\"x\",\"sources\":[{\"locator\":\"https://example.org\"}],\"patron\":{\"did\":\"did:key:zMia\"}}");
        assertEquals(401, submit.statusCode(), "no self-asserted patron gets a draft in");
    }

    @Test
    void a_reader_token_reads_and_cannot_write() throws Exception {
        var ask = M.readTree(call(readerToken, "library_ask", "{\"question\":\"obsidian\",\"k\":3}").body());
        var result = ask.path("result");
        assertFalse(result.path("isError").asBoolean(false), ask.toString());
        var pkg = M.readTree(result.path("content").get(0).path("text").asText());
        assertEquals("wiki:1", pkg.path("entries").get(0).path("id").asText());

        var submit = M.readTree(call(readerToken, "library_submit",
            "{\"claim\":\"x\",\"sources\":[{\"locator\":\"https://example.org\"}]}").body()).path("result");
        assertTrue(submit.path("isError").asBoolean());
        assertEquals("forbidden", submit.path("data").path("code").asText());
    }

    @Test
    void a_household_session_is_the_patron_and_may_submit() throws Exception {
        var submit = M.readTree(call(memberSession, "library_submit",
            "{\"claim\":\"Obsidian is volcanic glass\",\"sources\":[{\"locator\":\"https://example.org/obsidian\"}]}")
            .body()).path("result");
        assertFalse(submit.path("isError").asBoolean(false), submit.toString());
        var entry = M.readTree(submit.path("content").get(0).path("text").asText());
        assertEquals("draft", entry.path("state").asText());

        var impostor = M.readTree(call(memberSession, "library_submit",
            "{\"claim\":\"y\",\"sources\":[{\"locator\":\"https://example.org\"}],\"patron\":{\"did\":\"did:key:zMia\"}}")
            .body()).path("result");
        assertEquals("forbidden", impostor.path("data").path("code").asText(),
            "a session cannot name someone else as the patron");
    }
}
