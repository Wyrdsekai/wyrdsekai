package org.wyrdsekai.e2e.tier2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.issue.Issue;
import org.wyrdsekai.core.issue.IssueService;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tier-2 e2e — the full server bootstrap serves the
 * /api/issues surface, and a report filed via the same IssueService the
 * slash-command surfaces call is visible through REST. This proves the
 * wiring end-to-end: bootstrap init → service singleton → REST → export.
 */
@Tag("tier2")
class IssueCaptureE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;
    private static HttpClient http;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("noted.", 20, 15);

        var client = new InferenceClient(wireMock.baseUrl());
        var backend = new InferenceBackend.LlamaServer(
            "wiremock", client, 10, List.of(), null);

        server = new TestServerBootstrap(List.of(backend), PortAllocator.allocate(),
            List.of());
        server.start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
        IssueService.reset();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
            .timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void restRoundTripOnFullBootstrap() throws Exception {
        var resp = post("/api/issues",
            "{\"text\":\"shakedown probe issue\",\"reporter\":\"operator\",\"surface\":\"rest\"}");
        assertEquals(201, resp.statusCode(), resp.body());
        var id = MAPPER.readTree(resp.body()).get("issue").get("id").asText();

        var list = MAPPER.readTree(get("/api/issues").body());
        assertTrue(list.get("count").asInt() >= 1);

        var export = get("/api/issues/" + id + "/export");
        assertEquals(200, export.statusCode());
        assertTrue(export.body().contains("shakedown probe issue"));

        assertEquals(200, post("/api/issues/" + id + "/close", "").statusCode());
    }

    @Test
    void slashSurfaceServicePathIsVisibleOverRest() throws Exception {
        // The SSH/telnet/WS handlers call IssueService directly — file via
        // the same path the surfaces use and assert REST sees it.
        var svc = IssueService.get();
        assertNotNull(svc, "bootstrap must init the issue service");
        var filed = svc.file(Issue.KIND_ISSUE, "filed via surface path",
            "operator", "ssh", null, null);

        var one = MAPPER.readTree(get("/api/issues/" + filed.id()).body());
        assertEquals("ssh", one.get("surface").asText());
        assertEquals("filed via surface path", one.get("text").asText());
    }
}
