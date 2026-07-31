package org.wyrdsekai.e2e.tier0;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.wyrdsekai.e2e.infra.*;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.library.KnowledgeChunk;
import org.wyrdsekai.core.library.KnowledgePackIndexer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for the Library knowledge system:
 * - Knowledge search via telnet (Library room)
 * - Knowledge search via HTTP API
 * - Pack management via HTTP API
 * - Study journal via telnet
 * - Study search via HTTP API
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LibraryKnowledgeE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;
    private static HttpClient http;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Acknowledged.", 30, 20);

        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock",
                new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();

        http = HttpClient.newHttpClient();

        // Pre-index some test knowledge so searches have results
        if (server.luceneStore() != null) {
            var indexer = new KnowledgePackIndexer(server.luceneStore());
            indexer.indexChunks("test-wiki", List.of(
                KnowledgeChunk.text("tw:1", "test-wiki", "Sourdough Bread",
                    "Sourdough bread is made by fermenting dough using wild lactobacillaceae and yeast. It has a distinctive tangy flavor from lactic acid produced during fermentation.",
                    "Wikipedia"),
                KnowledgeChunk.text("tw:2", "test-wiki", "Quantum Computing",
                    "Quantum computing uses quantum mechanical phenomena such as superposition and entanglement to perform computation. Qubits can exist in multiple states simultaneously.",
                    "Wikipedia"),
                KnowledgeChunk.text("tw:3", "test-wiki", "Japanese Cuisine",
                    "Japanese cuisine encompasses the regional and traditional foods of Japan. It is based on rice with miso soup and other dishes emphasizing seasonal ingredients.",
                    "Wikipedia")
            ), null);
        }
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    // --- HTTP API Tests ---

    @Test @Order(1)
    void http_knowledge_search_returns_results() throws Exception {
        var resp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/library/search?q=sourdough+bread"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("sourdough") || resp.body().contains("Sourdough"),
            "Should find sourdough article: " + resp.body());
    }

    @Test @Order(2)
    void http_knowledge_search_empty_query_rejected() throws Exception {
        var resp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/library/search?q="))
            .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(400, resp.statusCode());
    }

    @Test @Order(3)
    void http_packs_list() throws Exception {
        var resp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/library/packs"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("test-wiki"), "Should list the test-wiki pack");
    }

    @Test @Order(4)
    void http_knowledge_status() throws Exception {
        var resp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/library/status"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("totalChunks"));
    }

    @Test @Order(5)
    void http_available_packs() throws Exception {
        var resp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/library/available"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("simple-wikipedia"), "Should list available packs");
    }

    @Test @Order(6)
    void http_pack_remove_and_verify() throws Exception {
        // Remove the test pack
        var resp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/library/packs/test-wiki"))
            .DELETE().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("removed"));

        // Re-index for subsequent tests
        if (server.luceneStore() != null) {
            var indexer = new KnowledgePackIndexer(server.luceneStore());
            indexer.indexChunks("test-wiki", List.of(
                KnowledgeChunk.text("tw:1", "test-wiki", "Sourdough Bread",
                    "Sourdough bread is made by fermenting dough.", "Wikipedia")
            ), null);
        }
    }

    // --- Study HTTP API Tests ---

    @Test @Order(7)
    void http_study_write_and_search_journal() throws Exception {
        // Write a journal entry
        var writeResp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/study/journal"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"user\":\"test-user\",\"content\":\"Had a wonderful dinner with friends tonight\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, writeResp.statusCode());
        assertTrue(writeResp.body().contains("journal:"));

        // Search for it
        var searchResp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/study/search?q=dinner+friends&user=test-user"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, searchResp.statusCode());
        assertTrue(searchResp.body().contains("dinner") || searchResp.body().contains("wonderful"),
            "Should find journal entry");
    }

    @Test @Order(8)
    void http_study_status() throws Exception {
        var resp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/study/status?user=test-user"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("totalItems"));
    }

    // --- Telnet Tests ---

    @Test @Order(9)
    void http_knowledge_search_quantum() throws Exception {
        // Verify a different search term works
        var resp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/library/search?q=quantum+computing+qubits"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("quantum") || resp.body().contains("Quantum"),
            "Should find quantum computing article");
    }

    @Test @Order(10)
    void telnet_study_journal_write() throws Exception {
        // Create a user first
        http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"journaluser\",\"password\":\"pass123\",\"displayName\":\"Journal User\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());

        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.login("journaluser", "pass123");
            tc.waitForText("Study", TIMEOUT);
            Thread.sleep(500);

            // Write a journal entry
            tc.sendLine("journal Today I explored the world of Wyrdsekai for the first time");
            var result = tc.waitForLine(
                l -> l.contains("journal") || l.contains("Journal") || l.contains("saved"),
                TIMEOUT);
            assertNotNull(result, "Should confirm journal entry was saved");
        }
    }

    @Test @Order(11)
    void telnet_study_dashboard_shows_status() throws Exception {
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.login("journaluser", "pass123");
            tc.waitForText("Study", TIMEOUT);
            Thread.sleep(500);

            tc.sendLine("look at dashboard");
            var result = tc.waitForLine(
                l -> l.contains("crystal") || l.contains("telemetry") || l.contains("Knowledge")
                    || l.contains("dashboard"),
                TIMEOUT);
            assertNotNull(result, "Dashboard crystal should show system status");
        }
    }
}
