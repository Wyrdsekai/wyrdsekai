package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.issue.IssueService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * exercises the /api/issues surface on a real
 * Javalin server: file → list → get → export → close round trip, prefix
 * addressing, and the 404/400/503 edges. This is the integration contract
 * the `wyrd issue` CLI and the phone clients rely on.
 */
class IssueRoutesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private Javalin app;
    private HttpClient http;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        IssueService.reset();
        IssueService.init(tmp.resolve("issues"), null, null);
        app = Javalin.create(cfg -> new IssueRoutes().register(cfg.routes))
            .start("127.0.0.1", 0);
        baseUrl = "http://127.0.0.1:" + app.port();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
        IssueService.reset();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void fileListGetExportCloseRoundTrip() throws Exception {
        var resp = post("/api/issues",
            "{\"text\":\"the companion confabulated my birthday\","
            + "\"reporter\":\"operator\",\"surface\":\"phone\"}");
        assertEquals(201, resp.statusCode(), resp.body());
        var filed = MAPPER.readTree(resp.body());
        assertEquals("filed", filed.get("status").asText());
        var id = filed.get("issue").get("id").asText();

        var list = MAPPER.readTree(get("/api/issues").body());
        assertEquals(1, list.get("count").asInt());
        assertEquals("phone", list.get("issues").get(0).get("surface").asText());

        // Full bundle by unique prefix.
        var one = MAPPER.readTree(get("/api/issues/" + id.substring(0, 4)).body());
        assertEquals("issue", one.get("kind").asText());
        assertTrue(one.has("build"));

        var export = get("/api/issues/" + id + "/export");
        assertEquals(200, export.statusCode());
        assertTrue(export.body().contains("confabulated my birthday"));
        assertTrue(export.headers().firstValue("Content-Type").orElse("")
            .contains("text/markdown"));

        var close = post("/api/issues/" + id + "/close", "");
        assertEquals(200, close.statusCode());
        assertEquals(0, MAPPER.readTree(get("/api/issues").body()).get("count").asInt());
        assertEquals(1, MAPPER.readTree(get("/api/issues?status=all").body())
            .get("count").asInt());
    }

    @Test
    void feedbackKindIsHonoured() throws Exception {
        var resp = post("/api/issues",
            "{\"kind\":\"feedback\",\"text\":\"voice felt stiff today\"}");
        assertEquals(201, resp.statusCode());
        var issue = MAPPER.readTree(resp.body()).get("issue");
        assertEquals("feedback", issue.get("kind").asText());
        assertFalse(issue.has("logTail"), "feedback never captures logs");
    }

    @Test
    void badAndMissingInputs() throws Exception {
        assertEquals(400, post("/api/issues", "{\"reporter\":\"x\"}").statusCode());
        assertEquals(400, post("/api/issues", "not json").statusCode());
        assertEquals(404, get("/api/issues/zzzz").statusCode());
        assertEquals(404, post("/api/issues/zzzz/close", "").statusCode());
        assertEquals(404, get("/api/issues/zzzz/export").statusCode());
    }

    @Test
    void uninitializedServiceIs503() throws Exception {
        IssueService.reset();
        assertEquals(503, post("/api/issues", "{\"text\":\"x\"}").statusCode());
        assertEquals(503, get("/api/issues").statusCode());
    }
}
