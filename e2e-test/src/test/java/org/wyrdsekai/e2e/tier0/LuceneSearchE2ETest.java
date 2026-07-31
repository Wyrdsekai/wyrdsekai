package org.wyrdsekai.e2e.tier0;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 E2E tests verifying WyrdLuceneStore works alongside a running Wyrdsekai server.
 * <p>
 * WyrdLuceneStore is wired into TestServerBootstrap — foundation room descriptions are
 * indexed on seed via ZoneGuardian.SetLuceneStore, and the HTTP search endpoint is
 * registered at GET /api/search.
 */
@Tag("integration")
class LuceneSearchE2ETest {

    private static final int DIM = 384; // Match production config (all-minilm)

    private static TestServerBootstrap server;

    @TempDir
    static Path tempDir;

    private WyrdLuceneStore store;

    @BeforeAll
    static void startServer() throws Exception {
        // Boot a minimal server (no inference) — we just need the server running
        // to verify Lucene can coexist without resource conflicts
        server = new TestServerBootstrap(List.of());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    @BeforeEach
    void setUp() {
        // Each test gets its own subdirectory to avoid leftover data from prior tests
        var testDir = tempDir.resolve("lucene-" + UUID.randomUUID().toString().substring(0, 8));
        store = new WyrdLuceneStore(testDir, DIM);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (store != null) store.close();
    }

    // -----------------------------------------------------------------------
    //  Tests
    // -----------------------------------------------------------------------

    @Test
    void luceneStoreInitializesOnServerStart() {
        // Verify WyrdLuceneStore can initialize all collections while the server is running.
        // This tests that Lucene's FSDirectory + IndexWriter don't conflict with the
        // Pekko ActorSystem, SQLite WAL, or Javalin's Jetty server.
        store.ensureAllCollections();

        // Verify all collections are created (5 original + knowledge + lcsh + study = 8)
        assertEquals(8, SearchCollections.ALL.length, "Should have 8 collection types");
        for (String coll : SearchCollections.ALL) {
            assertEquals(0, store.totalCount(coll),
                "Collection '" + coll + "' should start empty");
        }

        // Verify each collection accepts inserts without errors
        store.insertCapability("test-cap", "test-tool", "SERVICE",
            "A test capability for E2E verification", "test,e2e", 0.5f);
        store.insertFragment("test-frag", "did:key:test", "trait",
            "test trait", null, System.currentTimeMillis(), 0.5f);
        store.insertMemoryItem("test-mem", "did:key:test", "journal",
            "test journal entry", null, System.currentTimeMillis(), "room:nexus");
        store.insertRoomContent("test-room", "room:nexus", "foundation", "The Nexus",
            "A shimmering hub of connections", "crystal");
        store.insertWorldDna("test-dna", "room:nexus", "interaction_style",
            "formal and reverent", null, 0.8f);
        store.commitAll();

        // Verify each collection has exactly 1 document
        assertEquals(1, store.totalCount(SearchCollections.LIBRARY));
        assertEquals(1, store.totalCount(SearchCollections.SOUL_FRAGMENTS));
        assertEquals(1, store.totalCount(SearchCollections.MEMORY_ITEMS));
        assertEquals(1, store.totalCount(SearchCollections.ROOM_CONTENT));
        assertEquals(1, store.totalCount(SearchCollections.WORLD_DNA));
    }

    @Test
    void roomContentIndexedOnSeed() {
        // WyrdLuceneStore is wired into TestServerBootstrap via ZoneGuardian.SetLuceneStore.
        // Foundation rooms (nexus, terminal, vault, docks, bridge) are indexed on seed.
        var serverStore = server.luceneStore();
        assertNotNull(serverStore, "Server should have a WyrdLuceneStore");

        // Flush any pending writes so they are visible to searchers
        serverStore.commitAll();

        // Foundation rooms should be indexed — at least 5 (nexus, terminal, vault, docks, bridge)
        var count = serverStore.totalCount(SearchCollections.ROOM_CONTENT);
        assertTrue(count >= 5,
            "At least 5 foundation rooms should be indexed, got: " + count);

        // Search for The Nexus by description keywords
        var results = serverStore.searchRooms("shimmering hub connections", 10);
        assertFalse(results.isEmpty(),
            "Foundation room 'The Nexus' should be searchable after seeding");

        var nexusResult = results.getFirst();
        assertEquals("The Nexus", nexusResult.metadata().get("name"));

        // Nexus Crystal object should be indexed in object_names
        var crystalResults = serverStore.searchRooms("crystal", 10);
        assertFalse(crystalResults.isEmpty(),
            "Should find rooms matching 'crystal' (Nexus Crystal object)");
    }

    @Test
    void searchEndpointReturnsResults() throws Exception {
        // GET /api/search?q=nexus should return results from the server's WyrdLuceneStore.
        // Foundation rooms are already indexed on seed.
        var http = HttpClient.newHttpClient();
        var mapper = new ObjectMapper();

        // Search for rooms containing "nexus"
        var req = HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/search?q=shimmering+hub"))
            .GET().build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "Search endpoint should return 200");

        var body = mapper.readTree(resp.body());
        assertTrue(body.has("results"), "Response should have 'results' field");
        assertTrue(body.has("query"), "Response should have 'query' field");
        assertTrue(body.has("collection"), "Response should have 'collection' field");
        assertEquals("room_content", body.get("collection").asText(),
            "Default collection should be room_content");

        var results = body.get("results");
        assertFalse(results.isEmpty(), "Should find foundation rooms for 'shimmering hub'");

        // Verify missing q parameter returns 400
        var badReq = HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/search"))
            .GET().build();
        var badResp = http.send(badReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, badResp.statusCode(), "Missing q should return 400");
    }

    @Test
    void luceneSearchWorksAlongsideRunningServer() {
        // Verify that Lucene search operations work correctly while the server
        // is handling actor messages, WebSocket connections, and SQLite queries.
        // This is a concurrency/resource-conflict smoke test.
        store.ensureAllCollections();

        // Index foundation room descriptions (manually, since not wired to seeding)
        store.insertRoomContent("r-nexus", "nexus", "foundation", "The Nexus",
            "A shimmering hub of connections — the heart of Wyrdsekai. " +
            "The Nexus Crystal pulses with the rhythm of the world.",
            "crystal");
        store.insertRoomContent("r-terminal", "terminal", "foundation", "The Terminal",
            "Glowing command interfaces line the walls. " +
            "Agents interact with the world through text commands here.",
            "interface command");
        store.insertRoomContent("r-vault", "vault", "foundation", "The Vault",
            "A secure chamber for storing precious items. " +
            "Wards protect the contents from unauthorized access.",
            "wards chest");
        store.insertRoomContent("r-docks", "docks", "foundation", "The Docks",
            "A misty harbor where travelers arrive from distant zones. " +
            "Foreign agents dock here before entering the household.",
            "harbor ship");
        store.insertRoomContent("r-bridge", "bridge", "foundation", "The Bridge",
            "The command center for zone administration. " +
            "Stewards manage household operations from this vantage point.",
            "console steward");
        store.commitAll();

        // Search for rooms while server is running
        var nexusResults = store.searchRooms("crystal shimmering", 5);
        assertFalse(nexusResults.isEmpty(), "Should find The Nexus by 'crystal shimmering'");
        assertEquals("r-nexus", nexusResults.getFirst().id());

        var vaultResults = store.searchRooms("secure items wards", 5);
        assertFalse(vaultResults.isEmpty(), "Should find The Vault by 'secure items wards'");
        assertEquals("r-vault", vaultResults.getFirst().id());

        // Zone filtering should work
        var foundationRooms = store.searchRoomsByZone("zone", "foundation", 10);
        // This might return all rooms since all are in "foundation" zone
        // and "zone" appears in some descriptions
        // More targeted: search for specific content
        var docksResults = store.searchRoomsByZone("harbor travelers", "foundation", 5);
        assertFalse(docksResults.isEmpty(), "Should find The Docks within foundation zone");
        assertEquals("r-docks", docksResults.getFirst().id());

        // Verify total count after all operations
        assertEquals(5, store.totalCount(SearchCollections.ROOM_CONTENT),
            "All 5 foundation rooms should be indexed");
    }
}
