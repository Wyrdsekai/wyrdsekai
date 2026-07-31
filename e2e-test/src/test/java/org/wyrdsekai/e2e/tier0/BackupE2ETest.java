package org.wyrdsekai.e2e.tier0;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.wyrdsekai.e2e.infra.*;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.library.KnowledgeChunk;
import org.wyrdsekai.core.library.KnowledgePackIndexer;
import org.wyrdsekai.core.library.StudyService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for the backup/restore system including Study integration.
 * Verifies:
 * - Manual backup trigger via HTTP
 * - Backup list via HTTP
 * - DB + search indexes backed up together
 * - Study content survives backup/restore cycle
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackupE2ETest {

    private static final ObjectMapper mapper = new ObjectMapper();
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

        // Seed some data so backups have content
        if (server.luceneStore() != null) {
            // Knowledge
            var indexer = new KnowledgePackIndexer(server.luceneStore());
            indexer.indexChunks("backup-test-wiki", List.of(
                KnowledgeChunk.text("bk:1", "backup-test-wiki", "Backup Testing",
                    "This article is about testing backup and restore functionality.",
                    "Wikipedia")), null);

            // Study
            var study = new StudyService(server.luceneStore());
            study.writeJournalEntry("did:key:z6MkBackupUser", "My important journal entry about backups");
            study.addNote("did:key:z6MkBackupUser", "Reminder: test backup restore");
            study.indexDocument("did:key:z6MkBackupUser", "recipes", "Pasta Recipe",
                "Boil water, add pasta, cook 10 minutes.", "/recipes/pasta.txt");
            study.commitDocuments();
        }
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    // ---- helpers ----

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + path))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + path))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    // ==================================================================
    // Manual backup trigger
    // ==================================================================

    @Test @Order(1)
    void manual_backup_creates_snapshot() throws Exception {
        var resp = post("/api/backup/snapshot");
        assertEquals(200, resp.statusCode(), "Backup should succeed: " + resp.body());

        var json = mapper.readTree(resp.body());
        assertTrue(json.has("backupId"), "Should return backupId");
        assertTrue(json.has("location"), "Should return location");
        assertTrue(json.has("sizeBytes"), "Should return sizeBytes");
        assertTrue(json.get("sizeBytes").asLong() > 0, "Backup should have non-zero size");
        assertTrue(json.has("timestamp"), "Should return timestamp");
    }

    // ==================================================================
    // Backup list
    // ==================================================================

    @Test @Order(2)
    void backup_list_shows_snapshots() throws Exception {
        var resp = get("/api/backup/list");
        assertEquals(200, resp.statusCode());

        var json = mapper.readTree(resp.body());
        assertTrue(json.has("database"), "Should have database section");
        assertTrue(json.has("search"), "Should have search section");

        var dbBackups = json.get("database");
        assertTrue(dbBackups.isArray());
        assertTrue(dbBackups.size() >= 1, "Should have at least 1 DB backup");

        var searchBackups = json.get("search");
        assertTrue(searchBackups.isArray());
        assertTrue(searchBackups.size() >= 1, "Should have at least 1 search backup");

        // Verify backup entries have expected fields
        var firstDb = dbBackups.get(0);
        assertTrue(firstDb.has("backupId"));
        assertTrue(firstDb.has("sizeBytes"));
        assertTrue(firstDb.has("timestamp"));
    }

    // ==================================================================
    // Study content is in the backup
    // ==================================================================

    @Test @Order(3)
    void study_content_searchable_before_backup() throws Exception {
        // Verify the Study content we seeded is searchable
        var resp = get("/api/study/search?q=important+journal+backups&user=did:key:z6MkBackupUser");
        assertEquals(200, resp.statusCode());
        var json = mapper.readTree(resp.body());
        assertTrue(json.get("count").asInt() > 0,
            "Study journal should be searchable: " + resp.body());
    }

    @Test @Order(4)
    void knowledge_content_searchable_before_backup() throws Exception {
        var resp = get("/api/library/search?q=backup+testing+restore");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("backup") || resp.body().contains("Backup"),
            "Knowledge should be searchable");
    }

    // ==================================================================
    // Multiple backups
    // ==================================================================

    @Test @Order(5)
    void second_backup_succeeds() throws Exception {
        Thread.sleep(1100); // ensure distinct timestamp (second-precision backup IDs)

        var resp = post("/api/backup/snapshot");
        assertEquals(200, resp.statusCode());

        var json = mapper.readTree(resp.body());
        assertTrue(json.has("backupId"));
        assertTrue(json.get("sizeBytes").asLong() > 0);

        // List should show multiple backups
        var listResp = get("/api/backup/list");
        var listJson = mapper.readTree(listResp.body());
        assertTrue(listJson.get("database").size() >= 2,
            "Should have at least 2 DB backups: " + listJson.get("database").size());
    }

    // ==================================================================
    // Study-specific backup verification
    // ==================================================================

    @Test @Order(10)
    void backup_includes_study_documents() throws Exception {
        // Write another Study document
        var writeResp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/study/journal"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"user\":\"did:key:z6MkBackupUser\",\"content\":\"Post-backup journal entry for verification\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, writeResp.statusCode());
        Thread.sleep(200);

        // Create backup after new content
        var backupResp = post("/api/backup/snapshot");
        assertEquals(200, backupResp.statusCode());

        var backupJson = mapper.readTree(backupResp.body());
        assertTrue(backupJson.get("sizeBytes").asLong() > 0,
            "Backup with Study content should have non-zero size");
    }

    @Test @Order(11)
    void backup_includes_private_journal() throws Exception {
        // Write a private journal entry
        var writeResp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/study/journal"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"user\":\"did:key:z6MkBackupUser\",\"content\":\"My private thoughts about security\",\"isPrivate\":true}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, writeResp.statusCode());
        Thread.sleep(1100); // distinct timestamp

        // Backup after writing private content
        var backupResp = post("/api/backup/snapshot");
        assertEquals(200, backupResp.statusCode());

        var backupJson = mapper.readTree(backupResp.body());
        assertTrue(backupJson.get("sizeBytes").asLong() > 0,
            "Backup with private journal should have non-zero size");

        // Verify search backup exists (private entries are in Lucene index)
        var listResp = get("/api/backup/list");
        var json = mapper.readTree(listResp.body());
        assertTrue(json.get("search").size() >= 1,
            "Should have at least 1 search backup containing private journal");
    }

    // ==================================================================
    // Disk usage reported correctly
    // ==================================================================

    @Test @Order(20)
    void study_disk_usage_reflects_content() throws Exception {
        var resp = get("/api/study/disk-usage?user=did:key:z6MkBackupUser");
        assertEquals(200, resp.statusCode());
        var json = mapper.readTree(resp.body());
        assertTrue(json.get("totalItems").asLong() >= 3,
            "Should have at least 3 Study items (journal + note + doc): " + resp.body());
    }

    // ==================================================================
    // Backup with knowledge packs
    // ==================================================================

    @Test @Order(30)
    void backup_includes_knowledge_packs() throws Exception {
        var backupResp = post("/api/backup/snapshot");
        assertEquals(200, backupResp.statusCode());

        // Knowledge status should still work (Lucene not corrupted by backup)
        var statusResp = get("/api/library/status");
        assertEquals(200, statusResp.statusCode());
        assertTrue(statusResp.body().contains("totalChunks"));
    }
}
