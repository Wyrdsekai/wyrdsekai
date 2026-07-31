package org.wyrdsekai.core.search;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.library.CapabilityRecord;
import org.wyrdsekai.core.library.CapabilityRecord.*;
import org.wyrdsekai.core.library.LibraryStore;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying the LuceneLibraryAdapter produces comparable results
 * to FTS5 (LibraryStore) for library capability search. Both backends are indexed
 * with the same data; search results are compared for overlap.
 * <p>
 * These tests exercise the adapter layer behavior, not raw Lucene operations
 * (those are covered by WyrdLuceneStoreTest).
 */
@Tag("integration")
class LuceneLibraryIntegrationTest {

    private static final int DIM = 8; // small dim for test speed — library is text-only anyway

    @TempDir
    Path tempDir;

    private WyrdLuceneStore luceneStore;
    private LuceneLibraryAdapter adapter;
    private LibraryStore fts5Store;

    @BeforeEach
    void setUp() throws Exception {
        luceneStore = new WyrdLuceneStore(tempDir, DIM);
        luceneStore.ensureAllCollections();
        adapter = new LuceneLibraryAdapter(luceneStore);

        // In-memory SQLite for FTS5
        fts5Store = new LibraryStore(":memory:");
    }

    @AfterEach
    void tearDown() throws Exception {
        luceneStore.close();
        fts5Store.close();
    }

    // --- Helpers ---

    /** Build a CapabilityRecord with the given fields (sensible defaults for the rest). */
    private static CapabilityRecord cap(String id, String name, String description,
                                        List<String> tags, CapabilityProtocol protocol) {
        return new CapabilityRecord(
            id, name, "1.0.0", description,
            CognitiveLayer.PERCEIVE, tags,
            CapabilitySource.MANUAL, protocol,
            0.8f, VerificationStatus.UNVERIFIED,
            null, null, null,
            "test-provider", null, 0,
            false, null, Instant.now(), null
        );
    }

    /** Index the same record into both FTS5 and Lucene. */
    private void indexBoth(CapabilityRecord record) throws SQLException {
        fts5Store.upsertCapability(record);
        adapter.index(record);
    }

    /** Extract IDs from Lucene adapter results. */
    private static Set<String> luceneIds(List<CapabilityRecord> results) {
        return results.stream().map(CapabilityRecord::id).collect(Collectors.toSet());
    }

    /** Extract IDs from FTS5 results. */
    private static Set<String> fts5Ids(List<CapabilityRecord> results) {
        return results.stream().map(CapabilityRecord::id).collect(Collectors.toSet());
    }

    // -----------------------------------------------------------------------
    //  Tests
    // -----------------------------------------------------------------------

    @Test
    void luceneSearchMatchesFts5ForSimpleKeyword() throws Exception {
        var records = List.of(
            cap("c1", "file-reader", "Read files from the local filesystem", List.of("io", "filesystem"), CapabilityProtocol.SERVICE),
            cap("c2", "web-search", "Search the web using a search engine", List.of("web", "search"), CapabilityProtocol.SERVICE),
            cap("c3", "email-sender", "Send email messages via SMTP", List.of("email", "communication"), CapabilityProtocol.ROOM_SCRIPT)
        );
        for (var r : records) indexBoth(r);
        luceneStore.commitAll();

        var fts5Results = fts5Store.search("filesystem", 10);
        var luceneResults = adapter.search("filesystem", 10);

        assertFalse(fts5Results.isEmpty(), "FTS5 should find 'filesystem'");
        assertFalse(luceneResults.isEmpty(), "Lucene should find 'filesystem'");

        // Same items found (order may differ due to different ranking algorithms)
        assertEquals(fts5Ids(fts5Results), luceneIds(luceneResults),
            "FTS5 and Lucene should return the same capability IDs for 'filesystem'");
    }

    @Test
    void luceneSearchMatchesFts5ForMultipleWords() throws Exception {
        var records = List.of(
            cap("c1", "json-parser", "Parse JSON documents and extract fields",
                List.of("json", "parsing"), CapabilityProtocol.ROOM_SCRIPT),
            cap("c2", "yaml-parser", "Parse YAML configuration files",
                List.of("yaml", "parsing"), CapabilityProtocol.ROOM_SCRIPT),
            cap("c3", "csv-exporter", "Export data to CSV format",
                List.of("csv", "export"), CapabilityProtocol.ROOM_SCRIPT)
        );
        for (var r : records) indexBoth(r);
        luceneStore.commitAll();

        // FTS5 multi-word: "json parse" matches "json-parser" description
        var fts5Results = fts5Store.search("json parse", 10);
        var luceneResults = adapter.search("json parse", 10);

        // Both should find json-parser; Lucene StandardAnalyzer tokenizes similarly
        assertFalse(luceneResults.isEmpty(), "Lucene should find results for 'json parse'");

        // At minimum, both should include c1 (json-parser)
        assertTrue(luceneIds(luceneResults).contains("c1"),
            "Lucene should find json-parser for 'json parse'");
        // FTS5 may or may not match the same way due to different tokenization,
        // but if it does, the overlap should be non-empty
        if (!fts5Results.isEmpty()) {
            var overlap = new HashSet<>(fts5Ids(fts5Results));
            overlap.retainAll(luceneIds(luceneResults));
            assertFalse(overlap.isEmpty(),
                "FTS5 and Lucene results should overlap for 'json parse'");
        }
    }

    @Test
    void luceneSearchReturnsEmptyForNoMatch() throws Exception {
        var record = cap("c1", "file-reader", "Read files from disk",
            List.of("io"), CapabilityProtocol.SERVICE);
        indexBoth(record);
        luceneStore.commitAll();

        var luceneResults = adapter.search("quantum entanglement", 10);
        assertTrue(luceneResults.isEmpty(), "Lucene should return empty for non-matching keyword");

        var fts5Results = fts5Store.search("quantum", 10);
        assertTrue(fts5Results.isEmpty(), "FTS5 should also return empty");
    }

    @Test
    void luceneBulkIndexAndSearch() throws Exception {
        // Generate 60 capabilities across different domains
        var records = new ArrayList<CapabilityRecord>();
        var domains = List.of("filesystem", "network", "database", "security", "ml-inference",
            "image-processing", "audio-transcription", "text-generation", "code-analysis", "monitoring");

        for (int i = 0; i < 60; i++) {
            String domain = domains.get(i % domains.size());
            var r = cap(
                "cap-" + i,
                domain + "-tool-" + i,
                "A " + domain + " capability that performs task number " + i +
                    ". Useful for " + domain + " operations in the household.",
                List.of(domain, "tool", "batch-" + (i / 10)),
                CapabilityProtocol.ROOM_SCRIPT
            );
            records.add(r);
        }

        // Bulk index into Lucene
        int indexed = adapter.bulkIndex(records);
        assertEquals(60, indexed);

        // Also index into FTS5
        fts5Store.upsertCapabilities(records);

        // Search for a specific domain
        var luceneResults = adapter.search("database", 20);
        assertFalse(luceneResults.isEmpty(), "Lucene should find database capabilities");
        assertTrue(luceneResults.size() <= 20, "Should respect limit");

        // All results should be database-related
        for (var r : luceneResults) {
            assertTrue(r.name().contains("database") || r.description().contains("database"),
                "Result should be database-related: " + r.name());
        }

        // Verify Lucene total count
        assertEquals(60, luceneStore.totalCount(SearchCollections.LIBRARY));
    }

    @Test
    void luceneRemoveAndVerifyGone() throws Exception {
        var record = cap("cap-removable", "temp-tool", "A temporary tool for testing removal",
            List.of("temp"), CapabilityProtocol.ROOM_SCRIPT);
        adapter.index(record);
        luceneStore.commitAll();

        // Verify it's searchable
        var beforeRemove = adapter.search("temporary removal", 10);
        assertFalse(beforeRemove.isEmpty(), "Should find before removal");
        assertTrue(luceneIds(beforeRemove).contains("cap-removable"));

        // Remove
        adapter.remove("cap-removable");

        // Verify it's gone
        var afterRemove = adapter.search("temporary removal", 10);
        assertFalse(luceneIds(afterRemove).contains("cap-removable"),
            "Should not find removed capability");
        assertEquals(0, luceneStore.totalCount(SearchCollections.LIBRARY));
    }

    @Test
    void luceneSearchRanksRelevantHigher() throws Exception {
        // c1 has "filesystem" in both name and description — most relevant
        var c1 = cap("c1", "filesystem-manager",
            "Comprehensive filesystem management tool for reading, writing, and organizing filesystem operations",
            List.of("filesystem", "io", "storage"), CapabilityProtocol.SERVICE);

        // c2 mentions filesystem once
        var c2 = cap("c2", "backup-tool",
            "Create backups of important data, including filesystem snapshots",
            List.of("backup", "storage"), CapabilityProtocol.ROOM_SCRIPT);

        // c3 is unrelated
        var c3 = cap("c3", "weather-service",
            "Fetch current weather data from external APIs",
            List.of("weather", "api"), CapabilityProtocol.SERVICE);

        for (var r : List.of(c1, c2, c3)) {
            adapter.index(r);
        }
        luceneStore.commitAll();

        var results = adapter.search("filesystem", 10);
        assertFalse(results.isEmpty(), "Should find filesystem results");

        // The first result should be c1 (filesystem in name + description = highest TF)
        assertEquals("c1", results.getFirst().id(),
            "Most relevant result (filesystem-manager) should rank first");

        // c3 (weather) should not appear
        assertFalse(luceneIds(results).contains("c3"),
            "Unrelated capability should not appear in results");
    }
}
