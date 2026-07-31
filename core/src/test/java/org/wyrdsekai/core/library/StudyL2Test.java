package org.wyrdsekai.core.library;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.*;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.crypto.ZoneSecrets;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.home.HomeRegistryActor;
import org.wyrdsekai.core.home.HomeStore;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.core.soul.BehavioralExtractor;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Study L2 features:
 * - Version tracking (edit + history)
 * - Agent consent per-collection (now backed by grants)
 * - Shared shelves
 * - Import/export
 * - Storage monitoring
 * - Knowledge provenance
 */
class StudyL2Test {

    private static Path tempDir;
    private static WyrdLuceneStore store;
    private static StudyService study;
    private static ActorTestKit testKit;
    private static final String USER = "did:key:z6MkUser001";
    private static final String OTHER = "did:key:z6MkUser002";
    private static final String COMPANION = "companion-ember";

    @BeforeAll
    static void setUp() throws Exception {
        tempDir = Files.createTempDirectory("study-l2-test-");
        store = new WyrdLuceneStore(tempDir.resolve("search"), 384);
        store.ensureAllCollections();
        // 0.5a — private-journal writes are encrypted fail-closed; the test
        // JVM needs a zone master (prod originates one at first boot).
        var zoneId = WyrdConfig.get().zoneId();
        if (!ZoneSecrets.service().has(zoneId)) {
            ZoneSecrets.service().generate(zoneId);
        }
        // HomeRegistryActor backs the collection-consent path. SQLite on disk.
        testKit = ActorTestKit.create("StudyL2Test",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbcUrl = SchemaInitializer.initialize(tempDir.resolve("home.db"));
        var homeStore = new HomeStore(jdbcUrl);
        var registry = testKit.spawn(HomeRegistryActor.create(homeStore));
        var client = new HomeClient(registry, testKit.system());
        study = new StudyService(store, client);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (testKit != null) testKit.shutdownTestKit();
        if (store != null) store.close();
    }

    // --- Version Tracking ---

    @Nested
    class VersionTrackingTests {

        @Test
        void edit_increments_version() throws Exception {
            var id = study.writeJournalEntry(USER, "Original content about gardening");
            Thread.sleep(100); // Allow Lucene searcher to refresh
            int newVersion = study.editItem(id, USER, "Updated content about indoor gardening");
            assertTrue(newVersion > 0, "Edit should succeed (got " + newVersion + " for id " + id + ")");
            assertEquals(2, newVersion);
        }

        @Test
        void edit_preserves_old_version() throws Exception {
            var id = study.writeJournalEntry(USER, "Original recipe notes about pasta");
            Thread.sleep(100);
            int v2 = study.editItem(id, USER, "Updated recipe notes with better pasta sauce");
            assertEquals(2, v2, "Should be version 2 after first edit");
            Thread.sleep(100);
            int v3 = study.editItem(id, USER, "Final recipe notes with cooking times for pasta");
            assertEquals(3, v3, "Should be version 3 after second edit");

            // The current version should have the latest content
            var current = store.getById(
                SearchCollections.STUDY, id);
            assertNotNull(current, "Current version should exist");
            assertTrue(current.content().contains("Final recipe"), "Should have latest content");

            // Archived version should exist
            var archived = store.getById(
                SearchCollections.STUDY, id + "_v2");
            assertNotNull(archived, "Archived v2 should exist");
        }

        @Test
        void edit_nonexistent_returns_negative() {
            int result = study.editItem("nonexistent-id-xyz", USER, "new content");
            assertEquals(-1, result);
        }
    }

    // --- Agent Consent ---

    @Nested
    class AgentConsentTests {

        @Test
        void journal_accessible_by_default() {
            assertTrue(study.hasAccess(USER, COMPANION, "journal"));
        }

        @Test
        void other_collections_not_accessible_by_default() {
            assertFalse(study.hasAccess(USER, COMPANION, "taxes-2025"));
        }

        @Test
        void grant_and_revoke_access() {
            study.grantAccess(USER, COMPANION, "taxes-2025");
            assertTrue(study.hasAccess(USER, COMPANION, "taxes-2025"));

            study.revokeAccess(USER, COMPANION, "taxes-2025");
            assertFalse(study.hasAccess(USER, COMPANION, "taxes-2025"));
        }

        @Test
        void companion_search_respects_access() {
            // Index a document in a private collection
            study.indexDocument(USER, "medical", "Blood test results",
                "Hemoglobin is 14.2, white blood cells normal.", "/docs/blood-test.pdf");
            study.commitDocuments();

            // Companion has no access to "medical"
            var results = study.searchAsCompanion(USER, COMPANION, "blood test", 5);
            boolean foundMedical = results.stream()
                .anyMatch(r -> r.content() != null && r.content().contains("Hemoglobin"));
            assertFalse(foundMedical, "Companion should NOT see medical docs without access");

            // Grant access
            study.grantAccess(USER, COMPANION, "medical");
            var granted = study.searchAsCompanion(USER, COMPANION, "blood test", 5);
            boolean foundAfterGrant = granted.stream()
                .anyMatch(r -> r.content() != null && r.content().contains("Hemoglobin"));
            assertTrue(foundAfterGrant, "Companion SHOULD see medical docs after grant");

            // Cleanup
            study.revokeAccess(USER, COMPANION, "medical");
        }

        @Test
        void private_journal_never_visible_to_companion() {
            study.writePrivateJournalEntry(USER, "I'm secretly worried about the test results");

            var results = study.searchAsCompanion(USER, COMPANION, "secretly worried", 5);
            boolean foundPrivate = results.stream()
                .anyMatch(r -> r.metadata() != null &&
                    "journal_private".equals(r.metadata().get("item_type")));
            assertFalse(foundPrivate, "Private journal entries should NEVER be visible to companion");
        }

        @Test
        void list_grants() {
            study.grantAccess(USER, COMPANION, "recipes");
            study.grantAccess(USER, COMPANION, "travel");

            var grants = study.listGrants(USER);
            assertTrue(grants.size() >= 2);
            assertTrue(grants.containsKey(COMPANION + ":recipes"));

            // Cleanup
            study.revokeAccess(USER, COMPANION, "recipes");
            study.revokeAccess(USER, COMPANION, "travel");
        }
    }

    // --- Shared Shelves ---

    @Nested
    class SharedShelvesTests {

        @Test
        void owner_always_has_access() {
            assertTrue(study.hasSharedAccess(USER, "any-collection", USER));
        }

        @Test
        void other_user_no_access_by_default() {
            assertFalse(study.hasSharedAccess(USER, "taxes", OTHER));
        }

        @Test
        void share_and_unshare() {
            study.shareCollection(USER, "recipes", OTHER);
            assertTrue(study.hasSharedAccess(USER, "recipes", OTHER));

            study.unshareCollection(USER, "recipes", OTHER);
            assertFalse(study.hasSharedAccess(USER, "recipes", OTHER));
        }

        @Test
        void shared_search_respects_access() throws Exception {
            study.indexDocument(USER, "shared-recipes-test", "Unique Sourdough Recipe X7",
                "Mix special flour, water, sea salt, and wild yeast starter. Ferment for exactly 14 hours.", "/recipes/sourdough-x7.md");
            study.commitDocuments();
            Thread.sleep(100);

            // Other user can't search without share
            var denied = study.searchSharedCollection(USER, "shared-recipes-test", OTHER, "special flour wild yeast", 5);
            assertTrue(denied.isEmpty(), "Should be denied without share");

            // Share and retry
            study.shareCollection(USER, "shared-recipes-test", OTHER);
            var allowed = study.searchSharedCollection(USER, "shared-recipes-test", OTHER, "special flour wild yeast", 5);
            assertFalse(allowed.isEmpty(), "Should find shared content after sharing");

            study.unshareCollection(USER, "shared-recipes-test", OTHER);
        }

        @Test
        void list_shares() {
            study.shareCollection(USER, "photos", OTHER);
            var shares = study.listShares(USER);
            assertTrue(shares.containsKey("photos:" + OTHER));
            study.unshareCollection(USER, "photos", OTHER);
        }
    }

    // --- Import / Export ---

    @Nested
    class ImportExportTests {

        @Test
        void export_and_reimport() throws Exception {
            // Index some documents
            study.indexDocument(USER, "export-test", "Article One", "Content of article one.", "/a1.txt");
            study.indexDocument(USER, "export-test", "Article Two", "Content of article two.", "/a2.txt");
            study.commitDocuments();

            // Export
            var exportDir = tempDir.resolve("export-test-output");
            int exported = study.exportCollection(USER, "export-test", exportDir);
            assertTrue(exported >= 2, "Should export at least 2 items");
            assertTrue(Files.exists(exportDir.resolve("pack.json")));
            assertTrue(Files.exists(exportDir.resolve("chunks/data.jsonl")));

            // Import into a different collection
            int imported = study.importCollection(USER, "reimported", exportDir);
            assertTrue(imported >= 2, "Should reimport at least 2 items");

            // Verify imported content is searchable
            var results = study.searchDocuments(USER, "reimported", "article one", 5);
            assertFalse(results.isEmpty(), "Reimported content should be searchable");
        }
    }

    // --- Storage Monitoring ---

    @Nested
    class StorageTests {

        @Test
        void disk_usage_reports_items() {
            // Ensure at least one item exists
            study.addNote(USER, "Storage test note for disk usage check");
            var usage = study.getDiskUsage(USER);
            long items = (long) usage.get("totalItems");
            assertTrue(items > 0, "Should report items indexed (got " + items + ")");
            assertTrue(usage.containsKey("estimatedBytes"));
            assertTrue(usage.containsKey("estimatedMB"));
        }
    }

    // --- Knowledge Provenance ---

    @Nested
    class ProvenanceTests {

        @Test
        void detects_library_citations() {
            assertTrue(BehavioralExtractor.hasKnowledgeProvenance(
                "According to Wikipedia, Tokyo is the capital of Japan."));
            assertTrue(BehavioralExtractor.hasKnowledgeProvenance(
                "I found in the Library that sourdough needs a starter."));
            assertTrue(BehavioralExtractor.hasKnowledgeProvenance(
                "[Source: WikiHow] Here's how to fix a faucet."));
        }

        @Test
        void ignores_normal_speech() {
            assertFalse(BehavioralExtractor.hasKnowledgeProvenance(
                "Hello, how are you today?"));
            assertFalse(BehavioralExtractor.hasKnowledgeProvenance(
                "I think we should go to the park."));
        }

        @Test
        void extracts_source_references() {
            var sources = BehavioralExtractor.extractProvenanceSources(
                "Based on [Source: WikiHow] and [Source: StackExchange DIY], here's the answer.");
            assertEquals(2, sources.size());
            assertTrue(sources.contains("WikiHow"));
            assertTrue(sources.contains("StackExchange DIY"));
        }

        @Test
        void extracts_chunk_ids() {
            var sources = BehavioralExtractor.extractProvenanceSources(
                "From medquad:1234 and wikipedia:5678, I learned that...");
            assertTrue(sources.stream().anyMatch(s -> s.contains("medquad:1234")));
            assertTrue(sources.stream().anyMatch(s -> s.contains("wikipedia:5678")));
        }
    }
}
