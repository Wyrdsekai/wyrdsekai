package org.wyrdsekai.core.search;

import org.wyrdsekai.core.library.CapabilityRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Adapter that bridges WyrdLuceneStore to the LibraryActor's search interface.
 * Replaces SQLite FTS5 MATCH queries with Lucene BM25 text search.
 * <p>
 * The LibraryActor calls {@code store.search(keyword, limit)} which runs FTS5.
 * This adapter provides the same contract backed by Lucene, allowing the
 * LibraryActor to use either backend. FTS5 remains default; Lucene is opt-in
 * via configuration for migration safety.
 * <p>
 * Usage in LibraryActor:
 * <pre>
 *   // Either:
 *   var results = libraryStore.search(keyword, limit);   // FTS5 (default)
 *   // Or:
 *   var results = luceneAdapter.search(keyword, limit);  // Lucene
 * </pre>
 */
public final class LuceneLibraryAdapter {

    private static final Logger log = LoggerFactory.getLogger(LuceneLibraryAdapter.class);

    private final WyrdLuceneStore luceneStore;

    public LuceneLibraryAdapter(WyrdLuceneStore luceneStore) {
        this.luceneStore = luceneStore;
    }

    /**
     * Full-text search using Lucene BM25 (same contract as LibraryStore.search).
     * Returns CapabilityRecord stubs with id, name, description, and tags populated
     * from Lucene stored fields. Full records should be hydrated from LibraryStore
     * if needed for complete field access.
     */
    public List<CapabilityRecord> search(String keyword, int limit) {
        try {
            var results = luceneStore.searchCapabilities(keyword, limit);
            return results.stream()
                .map(LuceneLibraryAdapter::toCapabilityStub)
                .toList();
        } catch (Exception e) {
            log.error("Lucene library search failed for '{}': {}", keyword, e.getMessage());
            return List.of();
        }
    }

    /**
     * Index a capability record into Lucene.
     * Call this when registering or updating capabilities to keep the index in sync.
     */
    public void index(CapabilityRecord record) {
        String content = buildSearchableContent(record);
        String tags = record.tags() != null ? String.join(",", record.tags()) : "";
        String protocol = record.protocol() != null ? record.protocol().name() : "";
        luceneStore.insertCapability(
            record.id(), record.name(), protocol, content, tags, record.trustScore());
    }

    /** Remove a capability from the Lucene index. */
    public void remove(String id) {
        luceneStore.deleteCapability(id);
    }

    /** Bulk index all capabilities (for initial migration from FTS5). */
    public int bulkIndex(List<CapabilityRecord> records) {
        int count = 0;
        for (var record : records) {
            index(record);
            count++;
        }
        luceneStore.commitAll();
        log.info("Bulk indexed {} capabilities into Lucene", count);
        return count;
    }

    // -----------------------------------------------------------------------

    private static String buildSearchableContent(CapabilityRecord record) {
        var sb = new StringBuilder();
        if (record.name() != null) sb.append(record.name()).append(" ");
        if (record.description() != null) sb.append(record.description()).append(" ");
        if (record.tags() != null) sb.append(String.join(" ", record.tags()));
        return sb.toString().trim();
    }

    private static CapabilityRecord toCapabilityStub(WyrdLuceneStore.SearchResult result) {
        var meta = result.metadata();
        String id = result.id();
        String name = meta.getOrDefault("name", "").toString();
        String content = result.content();
        String tagsStr = meta.getOrDefault("capabilities_tags", "").toString();
        List<String> tags = tagsStr.isEmpty() ? List.of() : Arrays.asList(tagsStr.split(","));
        String protocolStr = meta.getOrDefault("protocol", "").toString();
        CapabilityRecord.CapabilityProtocol protocol = parseProtocol(protocolStr);
        float trustScore = -1.0f;
        Object ts = meta.get("trust_score");
        if (ts instanceof Number n) trustScore = n.floatValue();

        // Return a stub record — enough for search display, not full CRUD
        return new CapabilityRecord(
            id, name, null, content,
            null, tags,
            null, protocol,
            trustScore, null, null, null, null,
            null, null, 0,
            false, null, null, null
        );
    }

    private static CapabilityRecord.CapabilityProtocol parseProtocol(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return CapabilityRecord.CapabilityProtocol.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
