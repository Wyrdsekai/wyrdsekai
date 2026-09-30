package org.wyrdsekai.e2e.tier0;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.wyrdsekai.e2e.infra.*;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.library.StudyService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for Study L2 features:
 * - Version tracking (edit + history via HTTP)
 * - Agent consent (grant/revoke/search-as-companion via HTTP)
 * - Shared shelves (share/unshare/cross-user search via HTTP)
 * - Import/export (export + reimport via HTTP)
 * - Storage monitoring (disk usage via HTTP)
 *
 * <p>Since 0.5.0 a Study is reached with its owner's own login: every request here carries
 * a person's session token and the server takes the owner from it. Naming someone else's
 * Study in a body or query field is refused (403). Export and import are the home machine's
 * operator's, from loopback with the operator token.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StudyL2E2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String COMPANION = "companion-ember";
    private static final String USER_A_NAME = "studya";
    private static final String USER_A_PASS = "studyapass";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;
    private static HttpClient http;
    private static StudyService studyService;

    // Person A is the household's steward, person B a member A invited. USER_A / USER_B are
    // the identities the server files their things under (from their own logins).
    private static String tokenA;
    private static String tokenB;
    private static String USER_A;
    private static String USER_B;

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

        // Direct service access for pre-indexing test data
        studyService = new StudyService(server.luceneStore());

        tokenA = TestUsers.registerStewardToken(server.baseUrl(), USER_A_NAME, USER_A_PASS, "Study A");
        tokenB = TestUsers.inviteAndRedeem(server.baseUrl(), tokenA, "studyb", "studybpass", "Study B")
            .get("token").asText();
        USER_A = TestUsers.personId(server.baseUrl(), tokenA);
        USER_B = TestUsers.personId(server.baseUrl(), tokenB);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    // ---- helpers ----

    /** As person A (the default caller of these tests). */
    private HttpResponse<String> get(String path) throws Exception {
        return get(path, tokenA);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + path))
            .header("Authorization", "Bearer " + token)
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return post(path, json, tokenA);
    }

    private HttpResponse<String> post(String path, String json, String token) throws Exception {
        return http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + path))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String json) throws Exception {
        return put(path, json, tokenA);
    }

    private HttpResponse<String> put(String path, String json, String token) throws Exception {
        return http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + path))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .PUT(HttpRequest.BodyPublishers.ofString(json))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    // ==================================================================
    // Version Tracking
    // ==================================================================

    @Test @Order(1)
    void version_edit_via_http() throws Exception {
        // Write a journal entry
        var writeResp = post("/api/study/journal",
            """
            {"user":"%s","content":"Original gardening notes about tomatoes"}
            """.formatted(USER_A));
        assertEquals(200, writeResp.statusCode());
        var writeJson = mapper.readTree(writeResp.body());
        var itemId = writeJson.get("id").asText();
        assertNotNull(itemId);

        Thread.sleep(200); // Lucene refresh

        // Edit it
        var editResp = put("/api/study/item/" + itemId,
            """
            {"user":"%s","content":"Updated gardening notes about heirloom tomatoes"}
            """.formatted(USER_A));
        assertEquals(200, editResp.statusCode());
        var editJson = mapper.readTree(editResp.body());
        assertEquals(2, editJson.get("version").asInt(), "Should be version 2 after edit");

        // 0.5.0: B cannot edit A's item by naming A's Study in the body.
        var foreign = put("/api/study/item/" + itemId,
            """
            {"user":"%s","content":"B rewrites A's notes"}
            """.formatted(USER_A), tokenB);
        assertEquals(403, foreign.statusCode(), "naming another person's Study is refused");
        // Nor without a login at all.
        var anonymous = http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/study/journal?user=" + USER_A))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode(), "a Study needs a login");
    }

    @Test @Order(2)
    void version_edit_nonexistent_returns_404() throws Exception {
        var resp = put("/api/study/item/nonexistent-xyz-999",
            """
            {"user":"%s","content":"new content"}
            """.formatted(USER_A));
        assertEquals(404, resp.statusCode());
    }

    @Test @Order(3)
    void version_tracks_multiple_edits() throws Exception {
        // Write + edit twice — verify version numbers increment correctly
        var writeResp = post("/api/study/journal",
            """
            {"user":"%s","content":"History test original version one"}
            """.formatted(USER_A));
        var itemId = mapper.readTree(writeResp.body()).get("id").asText();
        Thread.sleep(200);

        var edit1 = put("/api/study/item/" + itemId,
            """
            {"user":"%s","content":"History test updated version two"}
            """.formatted(USER_A));
        assertEquals(200, edit1.statusCode());
        assertEquals(2, mapper.readTree(edit1.body()).get("version").asInt());
        Thread.sleep(200);

        var edit2 = put("/api/study/item/" + itemId,
            """
            {"user":"%s","content":"History test final version three"}
            """.formatted(USER_A));
        assertEquals(200, edit2.statusCode());
        assertEquals(3, mapper.readTree(edit2.body()).get("version").asInt());

        // History endpoint returns at least the current version
        var histResp = get("/api/study/item/" + itemId + "/history?user=" + USER_A);
        assertEquals(200, histResp.statusCode());
    }

    // ==================================================================
    // Agent Consent
    // ==================================================================

    @Test @Order(10)
    void consent_private_journal_never_visible() throws Exception {
        // Write a private journal entry
        post("/api/study/journal",
            """
            {"user":"%s","content":"My secret medical results are concerning","isPrivate":true}
            """.formatted(USER_A));
        Thread.sleep(200);

        // Search as companion — should NOT find private entries
        var resp = get("/api/study/consent/search?user=" + USER_A
            + "&companion=" + COMPANION + "&q=secret+medical");
        assertEquals(200, resp.statusCode());
        var json = mapper.readTree(resp.body());
        var results = json.get("results");
        for (int i = 0; i < results.size(); i++) {
            var meta = results.get(i).get("metadata");
            if (meta != null && meta.has("item_type")) {
                assertNotEquals("journal_private", meta.get("item_type").asText(),
                    "Private journal should NEVER be visible to companion");
            }
        }
    }

    @Test @Order(11)
    void consent_grant_and_revoke_via_http() throws Exception {
        // Index a document in a private collection
        studyService.indexDocument(USER_A, "medical-reports", "Blood Test Results",
            "Hemoglobin level is 14.2 and white blood cells are normal range.",
            "/docs/bloodwork.pdf");
        studyService.commitDocuments();
        Thread.sleep(200);

        // Companion has no access — search returns nothing
        var deniedResp = get("/api/study/consent/search?user=" + USER_A
            + "&companion=" + COMPANION + "&q=hemoglobin+blood");
        var deniedJson = mapper.readTree(deniedResp.body());
        assertEquals(0, deniedJson.get("count").asInt(),
            "Companion should not see medical docs without grant");

        // Grant access
        var grantResp = post("/api/study/consent/grant",
            """
            {"user":"%s","companion":"%s","collection":"medical-reports"}
            """.formatted(USER_A, COMPANION));
        assertEquals(200, grantResp.statusCode());
        assertTrue(mapper.readTree(grantResp.body()).get("granted").asBoolean());

        // Now companion can see it
        var allowedResp = get("/api/study/consent/search?user=" + USER_A
            + "&companion=" + COMPANION + "&q=hemoglobin+blood");
        var allowedJson = mapper.readTree(allowedResp.body());
        assertTrue(allowedJson.get("count").asInt() > 0,
            "Companion SHOULD see medical docs after grant");

        // Revoke
        var revokeResp = post("/api/study/consent/revoke",
            """
            {"user":"%s","companion":"%s","collection":"medical-reports"}
            """.formatted(USER_A, COMPANION));
        assertEquals(200, revokeResp.statusCode());
        assertTrue(mapper.readTree(revokeResp.body()).get("revoked").asBoolean());

        // Denied again
        var redeniedResp = get("/api/study/consent/search?user=" + USER_A
            + "&companion=" + COMPANION + "&q=hemoglobin+blood");
        assertEquals(0, mapper.readTree(redeniedResp.body()).get("count").asInt(),
            "Companion should not see medical docs after revoke");
    }

    @Test @Order(12)
    void consent_list_grants_via_http() throws Exception {
        // Grant two collections
        post("/api/study/consent/grant",
            """
            {"user":"%s","companion":"%s","collection":"recipes"}
            """.formatted(USER_A, COMPANION));
        post("/api/study/consent/grant",
            """
            {"user":"%s","companion":"%s","collection":"travel"}
            """.formatted(USER_A, COMPANION));

        var resp = get("/api/study/consent?user=" + USER_A);
        assertEquals(200, resp.statusCode());
        var json = mapper.readTree(resp.body());
        assertTrue(json.get("count").asInt() >= 2, "Should list at least 2 grants");

        // Cleanup
        post("/api/study/consent/revoke",
            """
            {"user":"%s","companion":"%s","collection":"recipes"}
            """.formatted(USER_A, COMPANION));
        post("/api/study/consent/revoke",
            """
            {"user":"%s","companion":"%s","collection":"travel"}
            """.formatted(USER_A, COMPANION));
    }

    @Test @Order(13)
    void consent_journal_accessible_by_default() throws Exception {
        // Write a shared journal entry
        post("/api/study/journal",
            """
            {"user":"%s","content":"Had a great conversation about philosophy today"}
            """.formatted(USER_A));
        Thread.sleep(200);

        // Companion can search journal without explicit grant (default access)
        var resp = get("/api/study/consent/search?user=" + USER_A
            + "&companion=" + COMPANION + "&q=philosophy+conversation");
        var json = mapper.readTree(resp.body());
        assertTrue(json.get("count").asInt() > 0,
            "Companion should see shared journal by default");
    }

    // ==================================================================
    // Shared Shelves
    // ==================================================================

    @Test @Order(20)
    void shared_shelves_access_control() throws Exception {
        // User A indexes docs
        studyService.indexDocument(USER_A, "shared-recipes", "Secret Pasta Recipe",
            "Mix semolina flour with eggs and roll into thin sheets. Add truffle oil.",
            "/recipes/pasta.md");
        studyService.commitDocuments();
        Thread.sleep(200);

        // User B cannot search User A's collection
        var deniedResp = get("/api/study/share/search?owner=" + USER_A
            + "&collection=shared-recipes&requester=" + USER_B + "&q=semolina+truffle", tokenB);
        assertEquals(200, deniedResp.statusCode());
        assertEquals(0, mapper.readTree(deniedResp.body()).get("count").asInt(),
            "User B should NOT see User A's collection without share");

        // Share it
        var shareResp = post("/api/study/share",
            """
            {"owner":"%s","collection":"shared-recipes","target":"%s"}
            """.formatted(USER_A, USER_B));
        assertEquals(200, shareResp.statusCode());
        assertTrue(mapper.readTree(shareResp.body()).get("shared").asBoolean());

        // Now User B can search
        var allowedResp = get("/api/study/share/search?owner=" + USER_A
            + "&collection=shared-recipes&requester=" + USER_B + "&q=semolina+truffle", tokenB);
        assertTrue(mapper.readTree(allowedResp.body()).get("count").asInt() > 0,
            "User B SHOULD see shared collection after share");

        // Unshare
        var unshareResp = post("/api/study/unshare",
            """
            {"owner":"%s","collection":"shared-recipes","target":"%s"}
            """.formatted(USER_A, USER_B));
        assertEquals(200, unshareResp.statusCode());

        // Denied again
        var redeniedResp = get("/api/study/share/search?owner=" + USER_A
            + "&collection=shared-recipes&requester=" + USER_B + "&q=semolina+truffle", tokenB);
        assertEquals(0, mapper.readTree(redeniedResp.body()).get("count").asInt(),
            "User B should NOT see collection after unshare");

        // 0.5.0: the requester is whoever is logged in; A cannot search as B.
        var impersonated = get("/api/study/share/search?owner=" + USER_A
            + "&collection=shared-recipes&requester=" + USER_B + "&q=semolina+truffle", tokenA);
        assertEquals(403, impersonated.statusCode(), "searching as someone else is refused");
    }

    @Test @Order(21)
    void shared_shelves_owner_always_has_access() throws Exception {
        studyService.indexDocument(USER_A, "my-notes", "Important Meeting Notes",
            "Discussed quarterly goals and hiring pipeline.",
            "/notes/meeting.md");
        studyService.commitDocuments();
        Thread.sleep(200);

        // Owner can always search their own collection
        var resp = get("/api/study/share/search?owner=" + USER_A
            + "&collection=my-notes&requester=" + USER_A + "&q=quarterly+goals");
        assertTrue(mapper.readTree(resp.body()).get("count").asInt() > 0,
            "Owner should always have access to their own collection");
    }

    @Test @Order(22)
    void shared_shelves_list_shares() throws Exception {
        post("/api/study/share",
            """
            {"owner":"%s","collection":"photos","target":"%s"}
            """.formatted(USER_A, USER_B));

        var resp = get("/api/study/shares?user=" + USER_A);
        assertEquals(200, resp.statusCode());
        var json = mapper.readTree(resp.body());
        assertTrue(json.get("count").asInt() >= 1, "Should list at least 1 share");
        assertTrue(resp.body().contains("photos"), "Should list 'photos' share");

        // Cleanup
        post("/api/study/unshare",
            """
            {"owner":"%s","collection":"photos","target":"%s"}
            """.formatted(USER_A, USER_B));
    }

    // ==================================================================
    // Import / Export
    // ==================================================================

    @Test @Order(30)
    void export_and_reimport_via_http() throws Exception {
        // Index test docs
        studyService.indexDocument(USER_A, "export-e2e", "Article Alpha",
            "Content of article alpha about distributed systems.", "/a1.txt");
        studyService.indexDocument(USER_A, "export-e2e", "Article Beta",
            "Content of article beta about consensus algorithms.", "/a2.txt");
        studyService.commitDocuments();
        Thread.sleep(200);

        // Export and import are the home machine's operator's (OPERATOR in ApiPolicy): the
        // operator token from loopback, naming whose collection.
        var operator = TestServerBootstrap.operatorToken();
        var exportResp = post("/api/study/export",
            """
            {"user":"%s","collection":"export-e2e"}
            """.formatted(USER_A), operator);
        assertEquals(200, exportResp.statusCode());
        var exportJson = mapper.readTree(exportResp.body());
        assertTrue(exportJson.get("exported").asInt() >= 2,
            "Should export at least 2 items: " + exportResp.body());
        var exportPath = exportJson.get("path").asText();
        assertNotNull(exportPath);

        // Import into a different collection. Build the body via the mapper so the
        // export path is JSON-escaped — on Windows it contains backslashes
        // (C:\Users\...) which break a hand-formatted JSON string ("\U" is an
        // illegal escape → 500). Map-encoding is platform-safe.
        var importResp = post("/api/study/import",
            mapper.writeValueAsString(Map.of(
                "user", USER_A, "collection", "reimported-e2e", "path", exportPath)), operator);
        assertEquals(200, importResp.statusCode());
        var importJson = mapper.readTree(importResp.body());
        assertTrue(importJson.get("imported").asInt() >= 2,
            "Should import at least 2 items: " + importResp.body());
        // A person's own login does not reach the operator's routes.
        assertEquals(403, post("/api/study/export",
            """
            {"user":"%s","collection":"export-e2e"}
            """.formatted(USER_A)).statusCode());

        // Search reimported content
        Thread.sleep(200);
        var searchResp = get("/api/study/search?q=distributed+systems&user=" + USER_A);
        assertTrue(searchResp.body().contains("distributed") || searchResp.body().contains("alpha"),
            "Reimported content should be searchable");
    }

    // ==================================================================
    // Storage Monitoring
    // ==================================================================

    @Test @Order(40)
    void disk_usage_via_http() throws Exception {
        // Ensure some content exists
        studyService.addNote(USER_A, "Disk usage test note for E2E verification");
        Thread.sleep(100);

        var resp = get("/api/study/disk-usage?user=" + USER_A);
        assertEquals(200, resp.statusCode());
        var json = mapper.readTree(resp.body());
        assertTrue(json.get("totalItems").asLong() > 0,
            "Should report items indexed: " + resp.body());
        assertTrue(json.has("estimatedBytes"), "Should have estimatedBytes");
        assertTrue(json.has("estimatedMB"), "Should have estimatedMB");
    }

    // ==================================================================
    // Validation
    // ==================================================================

    // The owner comes from the login now, so these name no one (naming a stranger is a 403,
    // checked above); the missing field is what they are about.

    @Test @Order(50)
    void consent_missing_params_returns_400() throws Exception {
        var resp = get("/api/study/consent/search?companion=y");
        assertEquals(400, resp.statusCode());
    }

    @Test @Order(51)
    void share_missing_params_returns_400() throws Exception {
        var resp = post("/api/study/share", """
            {"collection":"y"}
            """);
        assertEquals(400, resp.statusCode());
    }

    @Test @Order(52)
    void disk_usage_missing_user_returns_400() throws Exception {
        // A person asking gets their own (disk_usage_via_http); the machine's operator must
        // name whose.
        var resp = get("/api/study/disk-usage", TestServerBootstrap.operatorToken());
        assertEquals(400, resp.statusCode());
    }

    @Test @Order(53)
    void edit_missing_content_returns_400() throws Exception {
        var resp = put("/api/study/item/some-id", """
            {}
            """);
        assertEquals(400, resp.statusCode());
    }

    // ==================================================================
    // Telnet: Study L2 interactions
    // ==================================================================

    @Test @Order(60)
    void telnet_private_journal_entry() throws Exception {
        // Person A (registered in setUp; open registration closes after the steward).
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.login(USER_A_NAME, USER_A_PASS);
            tc.waitForText("Study", TIMEOUT);
            Thread.sleep(500);

            // Write a journal entry via telnet
            tc.sendLine("journal My private thoughts about the project direction");
            var result = tc.waitForLine(
                l -> l.contains("journal") || l.contains("Journal") || l.contains("saved"),
                TIMEOUT);
            assertNotNull(result, "Should confirm journal entry was saved via telnet");
        }
    }
}
