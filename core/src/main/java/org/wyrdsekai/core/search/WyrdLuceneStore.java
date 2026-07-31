package org.wyrdsekai.core.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.AppVersion;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.ModelAttribution;
import org.wyrdsekai.core.library.Provenance;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedded vector + text search store using Apache Lucene.
 * Adapted from CodePlane's LuceneVectorStore for Wyrdsekai's five collection types.
 * <p>
 * Zero external dependencies — no Docker, no Milvus, no etcd.
 * Each collection is a separate Lucene directory under dataDir/search/{collection}/.
 * <p>
 * Documents contain:
 *   - KnnFloatVectorField for dense vector search (HNSW, cosine similarity)
 *   - TextField for BM25 text search
 *   - StringField for metadata filtering (agentDid, roomId, zone, etc.)
 *   - StoredField for retrieval
 * <p>
 * Hybrid search: runs KNN + text query separately, combines with RRF (Reciprocal Rank Fusion).
 */
public class WyrdLuceneStore implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(WyrdLuceneStore.class);

    private static final String FIELD_ID = "id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_CONTENT_STORED = "content_stored";
    private static final String FIELD_VECTOR = "embedding";

    private final Path dataDir;
    private final int denseDim;
    private final int rrfK;
    private final Analyzer analyzer = new StandardAnalyzer();

    // Per-collection writer + searcher manager
    private final Map<String, IndexWriter> writers = new ConcurrentHashMap<>();
    private final Map<String, SearcherManager> searcherManagers = new ConcurrentHashMap<>();

    public WyrdLuceneStore(Path dataDir, int denseDim) {
        this(dataDir, denseDim, 60);
    }

    public WyrdLuceneStore(Path dataDir, int denseDim, int rrfK) {
        this.dataDir = dataDir.resolve("search");
        this.denseDim = denseDim;
        this.rrfK = rrfK;
        log.info("WyrdLuceneStore initialized at {} (dim={}, rrfK={})", this.dataDir, denseDim, rrfK);
        stampIndexMeta();
    }

    /**
     * Index self-description — {@code index-meta.json} beside the collections (pre-OSS
     * data-durability, 2026-07-09). Records the vector dimension + embedding model + app
     * version the index was CREATED with, so a dimension/model change is diagnosed at boot
     * with a named remedy instead of a runtime KNN exception buried mid-conversation (the
     * 384-pinned-index incident). Never fails the store.
     */
    private void stampIndexMeta() {
        var metaFile = dataDir.resolve("index-meta.json");
        try {
            Files.createDirectories(dataDir);
            var mapper = Json.mapper();
            if (Files.exists(metaFile)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = mapper.readValue(metaFile.toFile(), Map.class);
                int createdDim = meta.get("dense_dim") instanceof Number n ? n.intValue() : -1;
                if (createdDim > 0 && createdDim != denseDim) {
                    log.warn("Search index at {} was created with dim={} (model={}) but this node is "
                        + "configured for dim={}. Old vectors are inert (BM25 still works); run "
                        + "`wyrd embed-migrate --run` to re-embed, or move the index aside to rebuild.",
                        dataDir, createdDim, meta.getOrDefault("embedding_model", "?"), denseDim);
                }
            } else {
                var meta = new LinkedHashMap<String, Object>();
                meta.put("dense_dim", denseDim);
                meta.put("embedding_model", EmbeddingService.currentModelVersion());
                meta.put("created_by", AppVersion.get().version());
                meta.put("created_at", Instant.now().toString());
                mapper.writerWithDefaultPrettyPrinter().writeValue(metaFile.toFile(), meta);
            }
        } catch (Exception e) {
            log.debug("index-meta stamp failed (non-fatal): {}", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    //  Collection lifecycle
    // -----------------------------------------------------------------------

    /** Initialize all collections (creates directories + writers on first access). */
    public void ensureAllCollections() {
        for (String coll : SearchCollections.ALL) {
            try {
                getWriter(coll);
                log.debug("Collection '{}' ready", coll);
            } catch (IOException e) {
                log.error("Failed to initialize collection '{}'", coll, e);
            }
        }
    }

    /** Total document count for a collection. */
    public long totalCount(String collection) {
        try {
            var sm = getSearcherManager(collection);
            var searcher = sm.acquire();
            try {
                return searcher.getIndexReader().numDocs();
            } finally {
                sm.release(searcher);
            }
        } catch (IOException e) {
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    //  Soul Fragments
    // -----------------------------------------------------------------------

    public void insertFragment(String id, String agentDid, String fragmentType,
                               String content, List<Float> embedding,
                               long timestamp, float significance) {
        insertFragment(id, agentDid, fragmentType, content, embedding,
            timestamp, significance, 0L, 0L, null);
    }

    /**
     * Insert a soul fragment with bi-temporal fields for temporal queries.
     *
     * @param validFrom    epoch ms when this fact became true (0 = unknown)
     * @param supersededAt epoch ms when this fact was superseded (0 = current/active)
     * @param supersededBy ID of replacement fragment (null if current)
     */
    public void insertFragment(String id, String agentDid, String fragmentType,
                               String content, List<Float> embedding,
                               long timestamp, float significance,
                               long validFrom, long supersededAt, String supersededBy) {
        var doc = newDocument(id, content, embedding);
        doc.add(new StringField("agent_did", safe(agentDid), Field.Store.YES));
        doc.add(new StringField("fragment_type", safe(fragmentType), Field.Store.YES));
        doc.add(new StoredField("timestamp", timestamp));
        doc.add(new StoredField("significance", significance));
        // Bi-temporal fields (§)
        if (validFrom > 0) doc.add(new StoredField("valid_from", validFrom));
        if (supersededAt > 0) doc.add(new StoredField("superseded_at", supersededAt));
        if (supersededBy != null) doc.add(new StringField("superseded_by", supersededBy, Field.Store.YES));
        upsert(SearchCollections.SOUL_FRAGMENTS, id, doc);
    }

    /**
     * Search soul fragments for a specific agent.
     * Supports hybrid (text + vector), dense-only, or text-only via mode parameter.
     */
    public List<SearchResult> searchFragments(String agentDid, String queryText,
                                              List<Float> queryEmbedding, int topK) {
        return searchFragments(agentDid, queryText, queryEmbedding, topK, SearchMode.SET_UNION);
    }

    public List<SearchResult> searchFragments(String agentDid, String queryText,
                                              List<Float> queryEmbedding, int topK,
                                              SearchMode mode) {
        String filter = agentDid != null ? "agent_did == \"" + agentDid + "\"" : null;
        return doSearch(SearchCollections.SOUL_FRAGMENTS, queryText, queryEmbedding, topK, filter, mode);
    }

    /** Delete all fragments for an agent. */
    public long deleteFragmentsByAgent(String agentDid) {
        return deleteByField(SearchCollections.SOUL_FRAGMENTS, "agent_did", agentDid);
    }

    // -----------------------------------------------------------------------
    //  Library (MCP capability search — replaces FTS5)
    // -----------------------------------------------------------------------

    public void insertCapability(String id, String name, String protocol, String content,
                                 String capabilitiesTags, float trustScore) {
        var doc = newDocument(id, content, null); // no vector for library (text-only)
        doc.add(new StringField("name", safe(name), Field.Store.YES));
        doc.add(new StringField("protocol", safe(protocol), Field.Store.YES));
        doc.add(new StringField("capabilities_tags", safe(capabilitiesTags), Field.Store.YES));
        doc.add(new StoredField("trust_score", trustScore));
        upsert(SearchCollections.LIBRARY, id, doc);
    }

    /** Text search over capabilities — direct FTS5 replacement. */
    public List<SearchResult> searchCapabilities(String keyword, int limit) {
        return doSearch(SearchCollections.LIBRARY, keyword, null, limit, null, SearchMode.TEXT_ONLY);
    }

    /** Delete a capability by ID. */
    public long deleteCapability(String id) {
        return deleteById(SearchCollections.LIBRARY, id);
    }

    // -----------------------------------------------------------------------
    //  Memory Items (soul items, journal entries)
    // -----------------------------------------------------------------------

    public void insertMemoryItem(String id, String agentDid, String itemType, String content,
                                 List<Float> embedding, long timestamp, String roomId) {
        var doc = newDocument(id, content, embedding);
        doc.add(new StringField("agent_did", safe(agentDid), Field.Store.YES));
        doc.add(new StringField("item_type", safe(itemType), Field.Store.YES));
        doc.add(new StoredField("timestamp", timestamp));
        doc.add(new StringField("room_id", safe(roomId), Field.Store.YES));
        // Model attribution (2026-07-09): which LLM authored this memory — for post-OSS
        // corpus mining / regression debugging across model updates.
        doc.add(new StoredField("authoring_model", ModelAttribution.current()));
        upsert(SearchCollections.MEMORY_ITEMS, id, doc);
    }

    public List<SearchResult> searchMemory(String agentDid, String queryText,
                                           List<Float> queryEmbedding, int topK) {
        return searchMemory(agentDid, queryText, queryEmbedding, topK, SearchMode.SET_UNION);
    }

    public List<SearchResult> searchMemory(String agentDid, String queryText,
                                           List<Float> queryEmbedding, int topK,
                                           SearchMode mode) {
        String filter = agentDid != null ? "agent_did == \"" + agentDid + "\"" : null;
        return doSearch(SearchCollections.MEMORY_ITEMS, queryText, queryEmbedding, topK, filter, mode);
    }

    /** Delete all memory items for an agent. */
    public long deleteMemoryByAgent(String agentDid) {
        return deleteByField(SearchCollections.MEMORY_ITEMS, "agent_did", agentDid);
    }

    /** Delete memory items for an agent in a specific room. */
    public long deleteMemoryByRoom(String agentDid, String roomId) {
        try {
            var writer = getWriter(SearchCollections.MEMORY_ITEMS);
            var query = new BooleanQuery.Builder()
                .add(new TermQuery(new Term("agent_did", agentDid)), BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term("room_id", roomId)), BooleanClause.Occur.MUST)
                .build();
            long before = writer.getDocStats().numDocs;
            writer.deleteDocuments(query);
            writer.commit();
            refreshSearcher(SearchCollections.MEMORY_ITEMS);
            long after = writer.getDocStats().numDocs;
            return before - after;
        } catch (IOException e) {
            log.warn("Delete memory by room failed: {}", e.getMessage());
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    //  Room Content
    // -----------------------------------------------------------------------

    public void insertRoomContent(String id, String roomId, String zone, String name,
                                  String content, String objectNames) {
        var doc = newDocument(id, content, null); // room content is text-only search
        doc.add(new StringField("room_id", safe(roomId), Field.Store.YES));
        doc.add(new StringField("zone", safe(zone), Field.Store.YES));
        doc.add(new StringField("name", safe(name), Field.Store.YES));
        if (objectNames != null && !objectNames.isEmpty()) {
            doc.add(new TextField("object_names", objectNames, Field.Store.YES));
        }
        upsert(SearchCollections.ROOM_CONTENT, id, doc);
    }

    public List<SearchResult> searchRooms(String queryText, int topK) {
        return doSearch(SearchCollections.ROOM_CONTENT, queryText, null, topK, null, SearchMode.TEXT_ONLY);
    }

    /** Search rooms filtered by zone. */
    public List<SearchResult> searchRoomsByZone(String queryText, String zone, int topK) {
        String filter = zone != null ? "zone == \"" + zone + "\"" : null;
        return doSearch(SearchCollections.ROOM_CONTENT, queryText, null, topK, filter, SearchMode.TEXT_ONLY);
    }

    /** Delete all content for a room. */
    public long deleteRoomContent(String roomId) {
        return deleteByField(SearchCollections.ROOM_CONTENT, "room_id", roomId);
    }

    // -----------------------------------------------------------------------
    //  World DNA
    // -----------------------------------------------------------------------

    public void insertWorldDna(String id, String roomId, String dnaType, String content,
                               List<Float> embedding, float confidence) {
        var doc = newDocument(id, content, embedding);
        doc.add(new StringField("room_id", safe(roomId), Field.Store.YES));
        doc.add(new StringField("dna_type", safe(dnaType), Field.Store.YES));
        doc.add(new StoredField("confidence", confidence));
        upsert(SearchCollections.WORLD_DNA, id, doc);
    }

    public List<SearchResult> searchWorldDna(String queryText, List<Float> queryEmbedding, int topK) {
        return searchWorldDna(queryText, queryEmbedding, topK, SearchMode.SET_UNION);
    }

    public List<SearchResult> searchWorldDna(String queryText, List<Float> queryEmbedding,
                                             int topK, SearchMode mode) {
        return doSearch(SearchCollections.WORLD_DNA, queryText, queryEmbedding, topK, null, mode);
    }

    /** Delete world DNA for a room. */
    public long deleteWorldDnaByRoom(String roomId) {
        return deleteByField(SearchCollections.WORLD_DNA, "room_id", roomId);
    }

    // -----------------------------------------------------------------------
    //  Knowledge base (OPDS-K packs — Wikipedia, WikiHow, etc.)
    // -----------------------------------------------------------------------

    /**
     * Insert a knowledge chunk into the knowledge collection.
     *
     * @param id        Unique chunk ID (pack:index format)
     * @param packName  Source knowledge pack name
     * @param title     Chunk title (article title, section heading)
     * @param content   Text content of the chunk
     * @param source    Original source (URL, book title)
     * @param subject   LCSH subject terms (pipe-delimited, nullable)
     * @param embedding Pre-computed embedding vector (nullable — text-only if null)
     */
    public void insertKnowledge(String id, String packName, String title, String content,
                                 String source, String subject, List<Float> embedding) {
        insertKnowledge(id, packName, title, content, source, subject, embedding, null);
    }

    /**
     * Insert a knowledge chunk with first-class provenance.
     * Stores the trust tier as a filterable {@code StringField} for tier-gated
     * searches and the full provenance as JSON in {@code provenance_json} for
     * citation rendering ({@link #readKnowledgeChunk}).
     *
     * @param provenance Optional rich provenance — {@code null} means UNKNOWN tier
     */
    public void insertKnowledge(String id, String packName, String title, String content,
                                 String source, String subject, List<Float> embedding,
                                 Provenance provenance) {
        var doc = newDocument(id, content, embedding);
        doc.add(new StringField("pack", safe(packName), Field.Store.YES));
        doc.add(new StoredField("title", safe(title)));
        doc.add(new StoredField("source", safe(source)));
        if (subject != null && !subject.isBlank()) {
            doc.add(new TextField("subject", subject, Field.Store.YES));
        }
        // first-class provenance fields. Trust tier
        // as a filterable StringField so searches can gate by minimum tier.
        // Full provenance as JSON for citation rendering.
        var tier = provenance != null && provenance.trustTier() != null
            ? provenance.trustTier().name()
            : Provenance.TrustTier.UNKNOWN.name();
        doc.add(new StringField("trust_tier", tier, Field.Store.YES));
        if (provenance != null) {
            try {
                String json = Json.mapper().writeValueAsString(provenance);
                doc.add(new StoredField("provenance_json", json));
            } catch (Exception e) {
                log.debug("provenance_json serialize failed for {}: {}", id, e.getMessage());
            }
        }
        // #1038 — inserted-at timestamp (queryable via
        // LongPoint range) so the compact-library-index recipe's prune
        // step can age out chunks beyond the configured TTL. Chunks
        // inserted by older builds (no inserted_ms) are kept by the
        // prune query (only documents with the field are eligible).
        long now = System.currentTimeMillis();
        doc.add(new LongPoint("inserted_ms", now));
        doc.add(new StoredField("inserted_ms", now));
        // #1039 — embedding-model version stamp. The reembed
        // step uses this to find chunks whose stored embedding doesn't
        // match the currently-active model. Read from the bundled
        // PARAPHRASE_L12 instance (the v0.1 default); future versions
        // pass through whichever EmbeddingModel the store was wired with.
        doc.add(new StringField("embedding_model",
            EmbeddingModel.PARAPHRASE_L12.version(), Field.Store.YES));
        upsert(SearchCollections.KNOWLEDGE, id, doc);
    }

    /**
     * Read a knowledge chunk by id and return content, title, pack, and
     * provenance. Returns {@code null} if not found. Used by
     * {@link org.wyrdsekai.scripting.api.ItemWorldApiProvider#readKnowledgeChunk}.
     */
    public Map<String, Object> readKnowledgeChunk(String id) {
        var hit = getById(SearchCollections.KNOWLEDGE, id);
        if (hit == null) return null;
        var out = new LinkedHashMap<String, Object>();
        out.put("id", hit.id());
        out.put("text", hit.content());
        var meta = hit.metadata();
        if (meta != null) {
            if (meta.get("title") != null) out.put("title", String.valueOf(meta.get("title")));
            if (meta.get("pack") != null)  out.put("pack",  String.valueOf(meta.get("pack")));
            if (meta.get("source") != null) out.put("source", String.valueOf(meta.get("source")));
            if (meta.get("trust_tier") != null) {
                out.put("trustTier", String.valueOf(meta.get("trust_tier")));
            }
            if (meta.get("provenance_json") != null) {
                try {
                    var node = Json.mapper()
                        .readTree(String.valueOf(meta.get("provenance_json")));
                    out.put("provenance",
                        Json.mapper().convertValue(node, Map.class));
                } catch (Exception e) {
                    log.debug("provenance_json parse failed for {}: {}", id, e.getMessage());
                }
            }
        }
        return out;
    }

    /**
     * Search knowledge filtered to a minimum trust tier. Tiers ordered (most
     * trusted first): PAPER &gt; WIKI &gt; BOOK &gt; PERSONAL &gt; FEDERATED &gt;
     * BLOG &gt; FORUM &gt; UNKNOWN. Pass {@code null} to disable the filter.
     */
    public List<SearchResult> searchKnowledgeByTier(String queryText, List<Float> queryEmbedding,
                                                      Provenance.TrustTier minTier,
                                                      int topK) {
        if (minTier == null) {
            return searchKnowledge(queryText, queryEmbedding, topK);
        }
        // Build a compound filter listing every tier at-or-above minTier.
        var allowed = new ArrayList<String>();
        for (var t : Provenance.TrustTier.values()) {
            if (tierRank(t) <= tierRank(minTier)) allowed.add(t.name());
        }
        // doSearch's filter parser only handles "field == \"value\"" — for
        // multi-value alternatives we need a per-call query builder. Use
        // direct boolean composition.
        try {
            var sm = getSearcherManager(SearchCollections.KNOWLEDGE);
            var searcher = sm.acquire();
            try {
                var tierClause = new BooleanQuery.Builder();
                for (var t : allowed) {
                    tierClause.add(new TermQuery(new Term("trust_tier", t)),
                        BooleanClause.Occur.SHOULD);
                }
                tierClause.setMinimumNumberShouldMatch(1);

                Query base;
                if (queryEmbedding != null && !queryEmbedding.isEmpty()) {
                    base = new KnnFloatVectorQuery(FIELD_VECTOR, toFloatArray(queryEmbedding), topK * 4);
                } else if (queryText != null && !queryText.isBlank()) {
                    var parser = new QueryParser(FIELD_CONTENT, analyzer);
                    base = parser.parse(
                        QueryParser.escape(queryText));
                } else {
                    return List.of();
                }
                var combined = new BooleanQuery.Builder()
                    .add(base, BooleanClause.Occur.MUST)
                    .add(tierClause.build(), BooleanClause.Occur.FILTER)
                    .build();
                var topDocs = searcher.search(combined, topK);
                return extractResults(SearchCollections.KNOWLEDGE, searcher, topDocs);
            } finally {
                sm.release(searcher);
            }
        } catch (Exception e) {
            log.warn("searchKnowledgeByTier failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Rank: lower = more trusted. */
    private static int tierRank(Provenance.TrustTier t) {
        return switch (t) {
            case PAPER -> 0;
            case WIKI -> 1;
            case BOOK -> 2;
            case PERSONAL -> 3;
            case FEDERATED -> 4;
            case BLOG -> 5;
            case FORUM -> 6;
            case UNKNOWN -> 7;
        };
    }

    /** Search the knowledge base (hybrid: vector + text). */
    public List<SearchResult> searchKnowledge(String queryText, List<Float> queryEmbedding, int topK) {
        return searchKnowledge(queryText, queryEmbedding, topK, SearchMode.SET_UNION);
    }

    /** Search the knowledge base with explicit mode. */
    public List<SearchResult> searchKnowledge(String queryText, List<Float> queryEmbedding,
                                               int topK, SearchMode mode) {
        return doSearch(SearchCollections.KNOWLEDGE, queryText, queryEmbedding, topK, null, mode);
    }

    /** Search knowledge base filtered by pack name. */
    public List<SearchResult> searchKnowledgeByPack(String queryText, List<Float> queryEmbedding,
                                                     String packName, int topK) {
        String filter = packName != null ? "pack == \"" + packName + "\"" : null;
        return doSearch(SearchCollections.KNOWLEDGE, queryText, queryEmbedding, topK,
            filter, SearchMode.SET_UNION);
    }

    /** Text-only knowledge search (no embeddings needed). */
    public List<SearchResult> searchKnowledgeText(String queryText, int topK) {
        return doSearch(SearchCollections.KNOWLEDGE, queryText, null, topK, null, SearchMode.TEXT_ONLY);
    }

    /** Delete all knowledge chunks for a pack. */
    public long deleteKnowledgeByPack(String packName) {
        return deleteByField(SearchCollections.KNOWLEDGE, "pack", packName);
    }

    /** Count chunks in a specific pack. */
    public long countKnowledgeByPack(String packName) {
        return countByField(SearchCollections.KNOWLEDGE, "pack", packName);
    }

    /** Count total knowledge chunks across all packs. */
    public long countKnowledge() {
        return countAll(SearchCollections.KNOWLEDGE);
    }

    /**
     * Count chunks in any collection by name. Used by the
     * {@code compact-library-index} recipe's snapshot step (#1027/#1034)
     * to record pre- and post-compact chunk totals for the welfare gate
     * {@code chunk_delta_pct}.
     */
    public long countCollection(String collection) {
        return countAll(collection);
    }

    /**
     * #1038 — delete knowledge chunks inserted before the
     * configured cutoff (rolling TTL prune). Driven by the
     * {@code compact-library-index} recipe's {@code prune} step.
     *
     * <p>Chunks without an {@code inserted_ms} field (older builds, or
     * non-knowledge collections) are NOT eligible — the range query
     * only matches docs that carry the field.</p>
     *
     * <p>Returns the number of chunks deleted. Logs and returns 0 on
     * any I/O error (welfare-conservative — recipe stays in valid state).</p>
     */
    public long pruneKnowledgeOlderThan(long cutoffMs) {
        try {
            var writer = getWriter(SearchCollections.KNOWLEDGE);
            long before = writer.getDocStats().numDocs;
            // LongPoint.newRangeQuery is half-open on the upper bound;
            // (MIN, cutoffMs - 1) is "strictly less than cutoffMs."
            var query = LongPoint.newRangeQuery(
                "inserted_ms", Long.MIN_VALUE, cutoffMs - 1);
            writer.deleteDocuments(query);
            writer.commit();
            refreshSearcher(SearchCollections.KNOWLEDGE);
            long after = writer.getDocStats().numDocs;
            return before - after;
        } catch (IOException e) {
            log.warn("pruneKnowledgeOlderThan failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * #1039 — count chunks whose stored
     * {@code embedding_model} field doesn't match {@code targetVersion}.
     * Diagnostic-only — paired with {@link #reembedStaleKnowledgeChunks}
     * which performs the actual update.
     */
    public long countStaleEmbeddingChunks(String targetVersion) {
        if (targetVersion == null || targetVersion.isBlank()) return 0;
        try {
            var sm = getSearcherManager(SearchCollections.KNOWLEDGE);
            var searcher = sm.acquire();
            try {
                long total = searcher.getIndexReader().numDocs();
                // Stale = NOT matching the target version. Count via:
                //   stale = total - exact_match_count
                var matchQuery = new TermQuery(
                    new Term("embedding_model", targetVersion));
                long match = searcher.count(matchQuery);
                return total - match;
            } finally {
                sm.release(searcher);
            }
        } catch (IOException e) {
            log.warn("countStaleEmbeddingChunks failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Run Lucene {@code forceMerge(1)} on a collection. Used by the
     * compact recipe's merge step to consolidate segments after prune/
     * reembed. Idempotent; safe to call on an empty collection.
     */
    public boolean forceMergeCollection(String collection) {
        try {
            var writer = getWriter(collection);
            writer.forceMerge(1, /* doWait */ true);
            writer.commit();
            refreshSearcher(collection);
            return true;
        } catch (IOException e) {
            log.warn("forceMerge failed for {}: {}", collection, e.getMessage());
            return false;
        }
    }

    /** List distinct pack names with their chunk counts. */
    public Map<String, Long> listKnowledgePacks() {
        var packs = new LinkedHashMap<String, Long>();
        try {
            var sm = getSearcherManager(SearchCollections.KNOWLEDGE);
            var searcher = sm.acquire();
            try {
                // Collect the DISTINCT pack names across every segment first, then
                // count each pack exactly once. The pack term lives in each segment
                // that holds the pack's docs; the previous code ran a whole-index
                // count once per segment and summed them, so a pack spanning N
                // segments was reported at N× its true size (e.g. a freshly
                // re-installed pack with many unmerged segments). The count itself
                // is correct — only the per-segment summing was wrong.
                var names = new TreeSet<String>();
                var reader = searcher.getIndexReader();
                for (var leaf : reader.leaves()) {
                    var terms = leaf.reader().terms("pack");
                    if (terms == null) continue;
                    var iter = terms.iterator();
                    while (iter.next() != null) names.add(iter.term().utf8ToString());
                }
                for (var packName : names) {
                    packs.put(packName, (long) searcher.count(new TermQuery(new Term("pack", packName))));
                }
            } finally {
                sm.release(searcher);
            }
        } catch (IOException e) {
            log.warn("Failed to list knowledge packs: {}", e.getMessage());
        }
        return packs;
    }

    // -----------------------------------------------------------------------
    // public delete + tag-update entry points
    // -----------------------------------------------------------------------

    /**
     * Delete a single document by exact id from a collection. Returns the
     * number of documents removed (0 if id was not present).
     *
     * <p>Used by {@code world.library.delete}, {@code world.notes.delete},
     * {@code world.pinboard.unpin}. The private
     * {@link #deleteById(String, String)} stays internal; this is the
     * public-facing entry point.</p>
     */
    public long deletePublicById(String collection, String id) {
        return deleteById(collection, id);
    }

    /**
     * replace the {@code subject} (tags)
     * field on an existing knowledge chunk. Lucene doesn't support in-place
     * field update so this is a delete-then-reindex with the same id +
     * preserved content + new pipe-delimited tags, in a single writer
     * transaction (best-effort atomic — both updates flush in one commit).
     *
     * @param collection MUST be {@link SearchCollections#KNOWLEDGE}; other
     *                    collections aren't supported (no provenance fields).
     * @return Map with {@code ok} (true on success), {@code chunkId},
     *         {@code oldTags}, {@code newTags}; or {@code error} when the
     *         chunk is not found / collection unsupported.
     */
    public Map<String, Object> updateKnowledgeTags(String collection, String id,
                                                     List<String> newTags) {
        if (!SearchCollections.KNOWLEDGE.equals(collection)) {
            return Map.of("ok", false,
                "error", "tag updates only supported for knowledge collection");
        }
        if (id == null || id.isBlank()) {
            return Map.of("ok", false, "error", "blank chunkId");
        }
        var existing = getById(collection, id);
        if (existing == null) {
            return Map.of("ok", false, "error", "chunk not found");
        }
        var meta = existing.metadata();
        var oldTagsRaw = meta != null ? String.valueOf(meta.getOrDefault("subject", "")) : "";
        var oldTags = oldTagsRaw.isEmpty() ? List.<String>of()
            : Arrays.asList(oldTagsRaw.split("\\|"));
        var pack = meta != null ? String.valueOf(meta.getOrDefault("pack", "")) : "";
        var title = meta != null ? String.valueOf(meta.getOrDefault("title", "")) : "";
        var source = meta != null ? String.valueOf(meta.getOrDefault("source", "")) : "";
        var newSubject = newTags == null || newTags.isEmpty()
            ? "" : String.join("|", newTags);

        try {
            var writer = getWriter(collection);
            // Preserve the chunk's dense vector — a plain newDocument(...,null)
            // rebuild drops the KnnFloatVectorField, silently destroying the
            // chunk's embedding on every tag edit (it would no longer match dense
            // / hybrid search). Read the existing vector and re-add it.
            var vector = readVectorById(collection, id);
            var doc = newDocument(id, existing.content(), vector);
            if (!pack.isEmpty()) doc.add(new StringField("pack", pack, Field.Store.YES));
            if (!title.isEmpty()) doc.add(new StoredField("title", title));
            if (!source.isEmpty()) doc.add(new StoredField("source", source));
            if (!newSubject.isEmpty()) {
                doc.add(new TextField("subject", newSubject, Field.Store.YES));
            }
            var tier = meta != null ? String.valueOf(meta.getOrDefault("trust_tier", "UNKNOWN")) : "UNKNOWN";
            doc.add(new StringField("trust_tier", tier, Field.Store.YES));
            if (meta != null && meta.get("provenance_json") != null) {
                doc.add(new StoredField("provenance_json", String.valueOf(meta.get("provenance_json"))));
            }
            writer.updateDocument(new Term(FIELD_ID, id), doc);
            writer.commit();
            refreshSearcher(collection);
            return Map.of("ok", true,
                "chunkId", id,
                "oldTags", oldTags,
                "newTags", newTags == null ? List.of() : newTags);
        } catch (IOException e) {
            log.warn("updateKnowledgeTags({}/{}) failed: {}", collection, id, e.getMessage());
            return Map.of("ok", false, "error", "write failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    //  Library freshness seam (research-pack-freshness recipe) — read provenance
    //  + prune dead chunks by id. Side-channel: enumerate reads stored fields
    //  only and prune deletes by id, so NEITHER rewrites a document — the
    //  updateKnowledgeTags vector-loss trap is avoided entirely.
    // -----------------------------------------------------------------------

    /**
     * Enumerate knowledge chunks with their provenance for freshness auditing.
     * Returns up to {@code limit} entries, each a map of {@code id}, {@code pack},
     * {@code title}, {@code source}, {@code trust_tier}, {@code provenance_json}
     * (only the keys actually present on the doc). Stored-field read only — never
     * rewrites a document, so dense vectors are untouched.
     */
    public List<Map<String, Object>> enumerateKnowledgeProvenance(int limit) {
        var out = new ArrayList<Map<String, Object>>();
        if (limit <= 0) return out;
        try {
            var sm = getSearcherManager(SearchCollections.KNOWLEDGE);
            var searcher = sm.acquire();
            try {
                var topDocs = searcher.search(new MatchAllDocsQuery(), limit);
                var storedFields = searcher.storedFields();
                for (var sd : topDocs.scoreDocs) {
                    var doc = storedFields.document(sd.doc);
                    var entry = new LinkedHashMap<String, Object>();
                    for (var field : new String[]{FIELD_ID, "pack", "title", "source",
                            "trust_tier", "provenance_json"}) {
                        var val = doc.get(field);
                        if (val != null) entry.put(field, val);
                    }
                    if (!entry.isEmpty()) out.add(entry);
                }
            } finally {
                sm.release(searcher);
            }
        } catch (IOException e) {
            log.warn("enumerateKnowledgeProvenance failed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Delete knowledge chunks by id (dead/stale source prune). Batched into a
     * single commit. Returns the number of docs actually removed.
     */
    public long pruneKnowledgeByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        try {
            var writer = getWriter(SearchCollections.KNOWLEDGE);
            long before = writer.getDocStats().numDocs;
            var terms = new ArrayList<Term>(ids.size());
            for (var id : ids) {
                if (id != null && !id.isBlank()) terms.add(new Term(FIELD_ID, id));
            }
            if (terms.isEmpty()) return 0;
            writer.deleteDocuments(terms.toArray(new Term[0]));
            writer.commit();
            refreshSearcher(SearchCollections.KNOWLEDGE);
            long deleted = before - writer.getDocStats().numDocs;
            log.info("pruneKnowledgeByIds removed {} of {} requested chunks", deleted, ids.size());
            return deleted;
        } catch (IOException e) {
            log.warn("pruneKnowledgeByIds failed: {}", e.getMessage());
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    //  LCSH (Library of Congress Subject Headings)
    // -----------------------------------------------------------------------

    /** Insert an LCSH term. */
    public void insertLcsh(String id, String term, String broader, String narrower, String related) {
        var doc = new Document();
        doc.add(new StringField(FIELD_ID, id, Field.Store.YES));
        doc.add(new TextField(FIELD_CONTENT, term, Field.Store.NO));
        doc.add(new StoredField(FIELD_CONTENT_STORED, term));
        if (broader != null) doc.add(new StoredField("broader", broader));
        if (narrower != null) doc.add(new StoredField("narrower", narrower));
        if (related != null) doc.add(new StoredField("related", related));
        upsert(SearchCollections.LCSH, id, doc);
    }

    /** Search LCSH terms (text-only, no embeddings). */
    public List<SearchResult> searchLcsh(String queryText, int topK) {
        return doSearch(SearchCollections.LCSH, queryText, null, topK, null, SearchMode.TEXT_ONLY);
    }

    // -----------------------------------------------------------------------
    //  Study (per-user private content — journal, documents, pinboard)
    // -----------------------------------------------------------------------

    /**
     * Insert a Study item (journal entry, document chunk, pinboard reference, note).
     *
     * @param id         Unique item ID
     * @param userDid    Owner's DID (for filtering — only this user + their companion)
     * @param itemType   Type: "journal", "journal_private", "document", "pinboard", "note", "voice_memo"
     * @param title      Item title or first line
     * @param content    Full text content
     * @param collection Sub-collection name (e.g., "taxes-2025", "recipes", "default")
     * @param timestamp  Creation time (epoch millis)
     * @param version    Version number (for edit tracking)
     * @param embedding  Pre-computed embedding (nullable)
     */
    public void insertStudyItem(String id, String userDid, String itemType, String title,
                                 String content, String collection, long timestamp,
                                 int version, List<Float> embedding) {
        insertStudyItem(id, userDid, itemType, title, content, collection, timestamp,
            version, embedding, null, null, false);
    }

    /**
     * Study upsert carrying the CRDT sync columns. The
     * phone and the home-zone server are co-authoritative peers, so each item
     * carries a vector clock ({@code {deviceId → version}} as JSON), the device
     * that last wrote it, and a soft-delete tombstone. All three are Store.YES so
     * they round-trip through {@link SearchResult#metadata()} for the sync peer.
     * {@code vectorClockJson}/{@code lastModifiedBy} may be null on legacy
     * (non-sync) writes; {@code deleted} is always stored so absence reads false.
     */
    public void insertStudyItem(String id, String userDid, String itemType, String title,
                                 String content, String collection, long timestamp,
                                 int version, List<Float> embedding,
                                 String vectorClockJson, String lastModifiedBy, boolean deleted) {
        var doc = newDocument(id, content, embedding);
        doc.add(new StringField("user_did", safe(userDid), Field.Store.YES));
        doc.add(new StringField("item_type", safe(itemType), Field.Store.YES));
        doc.add(new StoredField("title", safe(title)));
        doc.add(new StringField("collection", safe(collection), Field.Store.YES));
        doc.add(new StoredField("timestamp", timestamp));
        doc.add(new StoredField("version", version));
        if (vectorClockJson != null) doc.add(new StoredField("vector_clock", vectorClockJson));
        if (lastModifiedBy != null) doc.add(new StringField("last_modified_by", lastModifiedBy, Field.Store.YES));
        doc.add(new StoredField("deleted", deleted ? 1 : 0));
        upsert(SearchCollections.STUDY, id, doc);
    }

    /** Hard-delete a Study item by id (CRDT tombstone that dominated local). */
    public long deleteStudyItem(String id) {
        return deleteById(SearchCollections.STUDY, id);
    }

    /** Search a user's Study (all types). */
    public List<SearchResult> searchStudy(String userDid, String queryText, int topK) {
        String filter = "user_did == \"" + userDid + "\"";
        return doSearch(SearchCollections.STUDY, queryText, null, topK, filter, SearchMode.TEXT_ONLY);
    }

    /** Search a user's Study filtered by item type. */
    public List<SearchResult> searchStudyByType(String userDid, String itemType,
                                                  String queryText, int topK) {
        // Combine user + type filter
        String filter = "user_did == \"" + userDid + "\" && item_type == \"" + itemType + "\"";
        return doSearch(SearchCollections.STUDY, queryText, null, topK, filter, SearchMode.TEXT_ONLY);
    }

    /** Search a user's Study filtered by sub-collection. */
    public List<SearchResult> searchStudyByCollection(String userDid, String collection,
                                                        String queryText, int topK) {
        String filter = "user_did == \"" + userDid + "\" && collection == \"" + collection + "\"";
        return doSearch(SearchCollections.STUDY, queryText, null, topK, filter, SearchMode.TEXT_ONLY);
    }

    /** List journal entries for a user (sorted by timestamp, most recent first). */
    public List<SearchResult> listJournal(String userDid, int limit) {
        // 2026-07-18: this used to pass "journal" as the QUERY TEXT (TEXT_ONLY),
        // so "recent entries" only matched entries that literally contained the
        // word "journal" — a write persisted fine but the read came back empty.
        // Recent-list is a match-all filtered by user + item_type, newest-first.
        return listStudyByTypeRecent(userDid, "journal", limit);
    }

    /**
     * Recent Study items of a given {@code item_type} for a user, newest-first.
     * Match-all + field filter (no query text), sorted in-memory by the stored
     * {@code timestamp} — the store has no sortable docvalues timestamp and a
     * user's journal/pins are small, so fetching the matches and sorting in Java
     * is correct and cheap. Returns at most {@code limit}.
     */
    public List<SearchResult> listStudyByTypeRecent(String userDid, String itemType, int limit) {
        if (limit <= 0) return List.of();
        String filter = "user_did == \"" + userDid + "\" && item_type == \"" + itemType + "\"";
        try {
            var sm = getSearcherManager(SearchCollections.STUDY);
            var searcher = sm.acquire();
            try {
                var query = applyFilter(new MatchAllDocsQuery(), filter);
                // #10 (2026-07-19 OSS hardening) — the in-memory recency sort is
                // only correct if it sees ALL candidates. The old `max(limit,
                // 1000)` fetched the first 1000 in Lucene DOC order (MatchAll gives
                // uniform scores), so with >1000 items of a type the newest could
                // be excluded before the sort and silently dropped. Fetch the true
                // match count (a personal Study is bounded), with a high, LOUD
                // safety ceiling so nothing is ever capped without a log line.
                int total = searcher.count(query);
                if (total == 0) return List.of();
                final int SCAN_CEILING = 100_000;
                int fetch = Math.min(total, SCAN_CEILING);
                if (total > SCAN_CEILING) {
                    log.warn("listStudyByTypeRecent({}, {}): {} items exceeds recency-scan "
                        + "ceiling {} — items beyond that are not considered for the newest-{} "
                        + "list until the index is pruned", userDid, itemType, total, SCAN_CEILING, limit);
                }
                var topDocs = searcher.search(query, fetch);
                var all = extractResults(SearchCollections.STUDY, searcher, topDocs);
                all.sort((a, b) -> Long.compare(tsOf(b), tsOf(a)));   // newest first
                return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
            } finally {
                sm.release(searcher);
            }
        } catch (IOException e) {
            log.warn("listStudyByTypeRecent({}, {}) failed: {}", userDid, itemType, e.getMessage());
            return List.of();
        }
    }

    /**
     * ALL Study items for a user, any type — match-all filtered by user_did only
     * (no query text, no type filter). The CRDT sync peer needs every item to
     * build its clock summary + deltas; a TEXT_ONLY {@code "*"} query matches the
     * literal token, not every doc, so it can't be used for enumeration.
     */
    public List<SearchResult> listAllStudy(String userDid, int limit) {
        if (limit <= 0) return List.of();
        String filter = "user_did == \"" + userDid + "\"";
        try {
            var sm = getSearcherManager(SearchCollections.STUDY);
            var searcher = sm.acquire();
            try {
                var query = applyFilter(new MatchAllDocsQuery(), filter);
                int total = searcher.count(query);
                if (total == 0) return List.of();
                int fetch = Math.min(total, Math.max(limit, 100_000));
                var topDocs = searcher.search(query, fetch);
                return extractResults(SearchCollections.STUDY, searcher, topDocs);
            } finally {
                sm.release(searcher);
            }
        } catch (IOException e) {
            log.warn("listAllStudy({}) failed: {}", userDid, e.getMessage());
            return List.of();
        }
    }

    private static long tsOf(SearchResult r) {
        var v = r.metadata() == null ? null : r.metadata().get("timestamp");
        if (v instanceof Number n) return n.longValue();
        try { return v == null ? 0L : Long.parseLong(v.toString()); }
        catch (NumberFormatException e) { return 0L; }
    }

    /** Count Study items for a user. */
    public long countStudyItems(String userDid) {
        return countByField(SearchCollections.STUDY, "user_did", userDid);
    }

    /** Delete all Study items for a user (right to be forgotten). */
    public long deleteStudyByUser(String userDid) {
        return deleteByField(SearchCollections.STUDY, "user_did", userDid);
    }

    /** Delete a specific Study sub-collection for a user. */
    public long deleteStudyCollection(String userDid, String collection) {
        // Delete by compound filter — need to iterate and filter
        try {
            var sm = getSearcherManager(SearchCollections.STUDY);
            var searcher = sm.acquire();
            try {
                var boolQuery = new BooleanQuery.Builder()
                    .add(new TermQuery(new Term("user_did", userDid)),
                        BooleanClause.Occur.MUST)
                    .add(new TermQuery(new Term("collection", collection)),
                        BooleanClause.Occur.MUST)
                    .build();
                var writer = getWriter(SearchCollections.STUDY);
                long before = writer.getDocStats().numDocs;
                writer.deleteDocuments(boolQuery);
                writer.commit();
                refreshSearcher(SearchCollections.STUDY);
                long after = writer.getDocStats().numDocs;
                return before - after;
            } finally {
                sm.release(searcher);
            }
        } catch (IOException e) {
            log.warn("Delete Study collection failed: {}", e.getMessage());
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    //  Utility: get by exact ID
    // -----------------------------------------------------------------------

    /** Get a single document by exact ID from any collection. */
    public SearchResult getById(String collection, String id) {
        try {
            var sm = getSearcherManager(collection);
            var searcher = sm.acquire();
            try {
                var topDocs = searcher.search(new TermQuery(new Term(FIELD_ID, id)), 1);
                if (topDocs.scoreDocs.length == 0) return null;
                var results = extractResults(collection, searcher, topDocs);
                return results.isEmpty() ? null : results.getFirst();
            } finally {
                sm.release(searcher);
            }
        } catch (IOException e) {
            log.warn("getById failed for {}/{}: {}", collection, id, e.getMessage());
            return null;
        }
    }

    /**
     * Read a document's stored dense vector by id, or {@code null} if the doc
     * has no vector / isn't found. Used to preserve embeddings across in-place
     * document rewrites (e.g. {@link #updateKnowledgeTags}) — a KnnFloatVectorField
     * isn't a retrievable stored field, so it must be read from the vector index
     * and re-added, or it is lost on rewrite. (Lucene 10 FloatVectorValues API.)
     */
    private List<Float> readVectorById(String collection, String id) {
        try {
            var sm = getSearcherManager(collection);
            var searcher = sm.acquire();
            try {
                var topDocs = searcher.search(new TermQuery(new Term(FIELD_ID, id)), 1);
                if (topDocs.scoreDocs.length == 0) return null;
                int globalDoc = topDocs.scoreDocs[0].doc;
                var reader = searcher.getIndexReader();
                var leafCtx = reader.leaves().get(
                    ReaderUtil.subIndex(globalDoc, reader.leaves()));
                int leafDoc = globalDoc - leafCtx.docBase;
                var fvv = leafCtx.reader().getFloatVectorValues(FIELD_VECTOR);
                if (fvv == null) return null;
                var iter = fvv.iterator();
                if (iter.advance(leafDoc) != leafDoc) return null;
                float[] v = fvv.vectorValue(iter.index());
                if (v == null) return null;
                var out = new ArrayList<Float>(v.length);
                for (float f : v) out.add(f);
                return out;
            } finally {
                sm.release(searcher);
            }
        } catch (IOException e) {
            log.warn("readVectorById failed for {}/{}: {}", collection, id, e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    //  Common search engine
    // -----------------------------------------------------------------------

    /** Search mode: HYBRID (RRF), DENSE_ONLY, TEXT_ONLY. */
    /**
     * Search modes:
     * - HYBRID: RRF (Reciprocal Rank Fusion) of dense + sparse results
     * - SET_UNION: Dense results keep native ranking, BM25-only matches appended
     *   (Omni-SimpleMem finding: score fusion across heterogeneous spaces disrupts semantic ordering)
     * - DENSE_ONLY: HNSW vector search only
     * - TEXT_ONLY: BM25 text search only
     */
    public enum SearchMode { HYBRID, SET_UNION, DENSE_ONLY, TEXT_ONLY }

    /** Unified search result across all collections. */
    public record SearchResult(String id, String content, String source,
                               Map<String, Object> metadata, float score) {}

    /** Flush and commit all writers. Call after bulk operations. */
    public void commitAll() {
        for (var entry : writers.entrySet()) {
            try {
                entry.getValue().commit();
                refreshSearcher(entry.getKey());
            } catch (IOException e) {
                log.warn("Commit failed for '{}': {}", entry.getKey(), e.getMessage());
            }
        }
    }

    @Override
    public void close() throws IOException {
        for (var sm : searcherManagers.values()) {
            try { sm.close(); } catch (Exception ignore) {}
        }
        for (var writer : writers.values()) {
            try {
                writer.commit();
                writer.close();
            } catch (Exception ignore) {}
        }
        writers.clear();
        searcherManagers.clear();
        log.info("WyrdLuceneStore closed");
    }

    // -----------------------------------------------------------------------
    //  Internal: document building, upserting, searching
    // -----------------------------------------------------------------------

    private Document newDocument(String id, String content, List<Float> embedding) {
        var doc = new Document();
        doc.add(new StringField(FIELD_ID, id, Field.Store.YES));
        // TextField for BM25 search (analyzed, not stored — use content_stored for retrieval)
        if (content != null && !content.isEmpty()) {
            doc.add(new TextField(FIELD_CONTENT, content, Field.Store.NO));
            doc.add(new StoredField(FIELD_CONTENT_STORED, content));
        }
        // KnnFloatVectorField for dense vector search. Dimension-guarded: Lucene requires one
        // dimension per vector field per index — a doc with a different dim is REJECTED at index
        // time ("Inconsistency of field data structures"), which used to fail the whole insert
        // when the embedding model changed (MiniLM 384d → bge-m3 1024d) or a classifier-dim
        // vector leaked in (second-node 2026-07-09). Better to index BM25-only than lose the memory.
        if (embedding != null && !embedding.isEmpty()) {
            if (embedding.size() == denseDim) {
                doc.add(new KnnFloatVectorField(FIELD_VECTOR, toFloatArray(embedding),
                    VectorSimilarityFunction.COSINE));
            } else {
                log.warn("Embedding dim {} != index dim {} for '{}' — indexing text-only",
                    embedding.size(), denseDim, id);
            }
        }
        return doc;
    }

    private void upsert(String collection, String id, Document doc) {
        try {
            var writer = getWriter(collection);
            try {
                writer.updateDocument(new Term(FIELD_ID, id), doc);
            } catch (RuntimeException e) {
                // Legacy-index guard: an existing index whose vector field was pinned at a
                // previous model's dimension rejects new-dim docs at index time
                // ("Inconsistency of field data structures"). Losing the memory is worse
                // than losing its vector — strip the vector and retry text-only. Run
                // `wyrd embed-migrate --run` to re-emit vectors after a model switch.
                if (doc.getField(FIELD_VECTOR) != null) {
                    log.warn("Vector insert rejected for '{}' in '{}' ({}) — retrying text-only",
                        id, collection, e.getMessage());
                    doc.removeFields(FIELD_VECTOR);
                    writer.updateDocument(new Term(FIELD_ID, id), doc);
                } else {
                    throw e;
                }
            }
            refreshSearcher(collection);
            // Commit to disk periodically (not every insert)
            if (writer.getDocStats().numDocs % 100 == 0) {
                writer.commit();
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Insert failed for '{}' in '{}': {}", id, collection, e.getMessage());
        }
    }

    private long deleteById(String collection, String id) {
        return deleteByField(collection, FIELD_ID, id);
    }

    private long deleteByField(String collection, String field, String value) {
        try {
            var writer = getWriter(collection);
            long before = writer.getDocStats().numDocs;
            writer.deleteDocuments(new TermQuery(new Term(field, value)));
            writer.commit();
            refreshSearcher(collection);
            long after = writer.getDocStats().numDocs;
            long deleted = before - after;
            if (deleted > 0) {
                log.debug("Deleted {} docs from '{}' ({}={})", deleted, collection, field, value);
            }
            return deleted;
        } catch (IOException e) {
            log.warn("Delete by field failed: {}", e.getMessage());
            return 0;
        }
    }

    private long countByField(String collection, String field, String value) {
        try {
            var sm = getSearcherManager(collection);
            var searcher = sm.acquire();
            try {
                return searcher.count(new TermQuery(new Term(field, value)));
            } finally {
                sm.release(searcher);
            }
        } catch (IOException e) {
            log.warn("Count by field failed: {}", e.getMessage());
            return 0;
        }
    }

    private long countAll(String collection) {
        try {
            var sm = getSearcherManager(collection);
            var searcher = sm.acquire();
            try {
                return searcher.getIndexReader().numDocs();
            } finally {
                sm.release(searcher);
            }
        } catch (IOException e) {
            log.warn("Count all failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Unified search dispatcher. Selects dense, sparse, or hybrid depending on
     * what inputs are available and the requested mode.
     */
    private List<SearchResult> doSearch(String collection, String queryText,
                                        List<Float> queryEmbedding, int topK,
                                        String filter, SearchMode mode) {
        try {
            // Graceful degradation: if no embedding provided, fall back to text-only
            boolean hasText = queryText != null && !queryText.isBlank();
            boolean hasVector = queryEmbedding != null && !queryEmbedding.isEmpty();

            SearchMode effectiveMode = mode;
            if (effectiveMode == SearchMode.HYBRID || effectiveMode == SearchMode.SET_UNION) {
                if (!hasVector) effectiveMode = SearchMode.TEXT_ONLY;
                else if (!hasText) effectiveMode = SearchMode.DENSE_ONLY;
            }
            if (effectiveMode == SearchMode.DENSE_ONLY && !hasVector) return List.of();
            if (effectiveMode == SearchMode.TEXT_ONLY && !hasText) return List.of();

            return switch (effectiveMode) {
                case DENSE_ONLY -> denseSearch(collection, queryEmbedding, topK, filter);
                case TEXT_ONLY -> sparseSearch(collection, queryText, topK, filter);
                case HYBRID -> hybridSearch(collection, queryText, queryEmbedding, topK, filter);
                case SET_UNION -> setUnionSearch(collection, queryText, queryEmbedding, topK, filter);
            };
        } catch (IOException e) {
            log.warn("Search failed on '{}': {}", collection, e.getMessage());
            return List.of();
        }
    }

    private List<SearchResult> denseSearch(String collection, List<Float> queryEmbedding,
                                           int topK, String filter) throws IOException {
        // Dimension guard: a query vector whose dim differs from the index's vector field throws
        // IllegalArgumentException — a RuntimeException that used to escape doSearch's IOException
        // catch and kill the WHOLE search (BM25 leg included). That's how recall returned empty on
        // second-node even though the fact's terms were in the index (embedding-model dim drift,
        // 2026-07-09). Degrade to no-dense-results; SET_UNION/HYBRID then ride the sparse leg.
        if (queryEmbedding != null && queryEmbedding.size() != denseDim) {
            log.warn("Query embedding dim {} != index dim {} on '{}' — skipping dense leg",
                queryEmbedding.size(), denseDim, collection);
            return List.of();
        }
        var sm = getSearcherManager(collection);
        var searcher = sm.acquire();
        try {
            float[] vec = toFloatArray(queryEmbedding);
            var knnQuery = new KnnFloatVectorQuery(FIELD_VECTOR, vec, topK);
            var finalQuery = applyFilter(knnQuery, filter);
            var topDocs = searcher.search(finalQuery, topK);
            return extractResults(collection, searcher, topDocs);
        } catch (RuntimeException e) {
            // Any other dense-leg failure must not sink the sparse leg either.
            log.warn("Dense search failed on '{}' ({}) — skipping dense leg",
                collection, e.getMessage());
            return List.of();
        } finally {
            sm.release(searcher);
        }
    }

    private List<SearchResult> sparseSearch(String collection, String queryText,
                                            int topK, String filter) throws IOException {
        var sm = getSearcherManager(collection);
        var searcher = sm.acquire();
        try {
            var parser = new QueryParser(FIELD_CONTENT, analyzer);
            var textQuery = parser.parse(
                QueryParser.escape(queryText));
            var finalQuery = applyFilter(textQuery, filter);
            var topDocs = searcher.search(finalQuery, topK);
            return extractResults(collection, searcher, topDocs);
        } catch (ParseException e) {
            log.warn("Query parse error: {}", e.getMessage());
            return List.of();
        } finally {
            sm.release(searcher);
        }
    }

    private List<SearchResult> hybridSearch(String collection, String queryText,
                                            List<Float> queryEmbedding, int topK,
                                            String filter) throws IOException {
        var denseResults = denseSearch(collection, queryEmbedding, topK, filter);
        var sparseResults = sparseSearch(collection, queryText, topK, filter);

        if (denseResults.isEmpty()) return sparseResults;
        if (sparseResults.isEmpty()) return denseResults;

        // RRF: score = 1/(k + rank_dense) + 1/(k + rank_sparse)
        var scores = new LinkedHashMap<String, Double>();
        var resultMap = new LinkedHashMap<String, SearchResult>();

        for (int i = 0; i < denseResults.size(); i++) {
            var r = denseResults.get(i);
            String key = r.id();
            scores.merge(key, 1.0 / (rrfK + i + 1), Double::sum);
            resultMap.putIfAbsent(key, r);
        }
        for (int i = 0; i < sparseResults.size(); i++) {
            var r = sparseResults.get(i);
            String key = r.id();
            scores.merge(key, 1.0 / (rrfK + i + 1), Double::sum);
            resultMap.putIfAbsent(key, r);
        }

        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(e -> {
                var orig = resultMap.get(e.getKey());
                return new SearchResult(orig.id(), orig.content(), orig.source(),
                    orig.metadata(), e.getValue().floatValue());
            })
            .toList();
    }

    /**
     * Set-union merge (Omni-SimpleMem discovery): dense results keep their native
     * semantic ranking, BM25-only matches are appended. Unlike RRF which fuses scores
     * across heterogeneous spaces (disrupting semantic ordering), set-union preserves
     * the dense ranking integrity while still capturing BM25-only keyword matches.
     */
    private List<SearchResult> setUnionSearch(String collection, String queryText,
                                               List<Float> queryEmbedding, int topK,
                                               String filter) throws IOException {
        var denseResults = denseSearch(collection, queryEmbedding, topK, filter);
        var sparseResults = sparseSearch(collection, queryText, topK, filter);

        if (denseResults.isEmpty()) return sparseResults;
        if (sparseResults.isEmpty()) return denseResults;

        // Dense results first (preserve native semantic ranking)
        var seen = new HashSet<String>();
        var merged = new ArrayList<SearchResult>();

        for (var r : denseResults) {
            seen.add(r.id());
            merged.add(r);
        }

        // Append BM25-only matches (not in dense results)
        for (var r : sparseResults) {
            if (!seen.contains(r.id()) && merged.size() < topK) {
                seen.add(r.id());
                merged.add(r);
            }
        }

        return merged;
    }

    // -----------------------------------------------------------------------
    //  Internal: index management
    // -----------------------------------------------------------------------

    private IndexWriter getWriter(String collection) throws IOException {
        return writers.computeIfAbsent(collection, coll -> {
            try {
                var collPath = dataDir.resolve(coll);
                var dir = FSDirectory.open(collPath);

                // Clear stale write.lock from crashed/killed previous server instance
                var lockFile = collPath.resolve("write.lock");
                if (Files.exists(lockFile)) {
                    try {
                        dir.obtainLock(IndexWriter.WRITE_LOCK_NAME).close();
                    } catch (LockObtainFailedException e) {
                        // Lock is genuinely held by another process — don't force
                        throw new RuntimeException("IndexWriter lock held by another process for " + coll, e);
                    } catch (Exception e) {
                        // Lock was stale — obtainLock succeeded and we closed it, now we can open normally
                        log.info("Cleared stale write lock for collection '{}'", coll);
                    }
                }

                var config = new IndexWriterConfig(analyzer);
                config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
                config.setRAMBufferSizeMB(32.0);
                config.setCommitOnClose(true);
                var writer = new IndexWriter(dir, config);
                log.info("Lucene IndexWriter opened for collection '{}' at {}",
                    coll, dataDir.resolve(coll));
                return writer;
            } catch (IOException e) {
                throw new RuntimeException("Failed to open IndexWriter for " + coll, e);
            }
        });
    }

    private SearcherManager getSearcherManager(String collection) throws IOException {
        return searcherManagers.computeIfAbsent(collection, coll -> {
            try {
                var writer = getWriter(coll);
                return new SearcherManager(writer, null);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create SearcherManager for " + coll, e);
            }
        });
    }

    private void refreshSearcher(String collection) {
        var sm = searcherManagers.get(collection);
        if (sm != null) {
            try { sm.maybeRefresh(); } catch (IOException ignore) {}
        }
    }

    private Query applyFilter(
            Query baseQuery, String filter) {
        if (filter == null || filter.isBlank()) return baseQuery;

        var builder = new BooleanQuery.Builder();
        builder.add(baseQuery, BooleanClause.Occur.MUST);

        for (String clause : filter.split("&&")) {
            clause = clause.trim();
            if (clause.contains("==")) {
                var parts = clause.split("==", 2);
                String field = parts[0].trim();
                String value = parts[1].trim().replaceAll("^\"|\"$", "");
                builder.add(new TermQuery(new Term(field, value)), BooleanClause.Occur.FILTER);
            }
        }
        return builder.build();
    }

    private List<SearchResult> extractResults(String collection,
                                              IndexSearcher searcher,
                                              TopDocs topDocs) throws IOException {
        var results = new ArrayList<SearchResult>();
        var storedFields = searcher.storedFields();
        for (var scoreDoc : topDocs.scoreDocs) {
            var doc = storedFields.document(scoreDoc.doc);
            String content = doc.get(FIELD_CONTENT_STORED);
            if (content == null) content = "";

            String id = doc.get(FIELD_ID);

            // Build source: prefer name > room_id > id
            String source = doc.get("name");
            if (source == null) source = doc.get("room_id");
            if (source == null) source = id;

            // Build metadata map from all stored fields
            var metadata = new LinkedHashMap<String, Object>();
            for (var field : doc.getFields()) {
                if (field.stringValue() != null) {
                    metadata.put(field.name(), field.stringValue());
                } else if (field.numericValue() != null) {
                    metadata.put(field.name(), field.numericValue());
                }
            }

            results.add(new SearchResult(id, content, source, metadata, scoreDoc.score));
        }
        return results;
    }

    private static float[] toFloatArray(List<Float> floats) {
        float[] arr = new float[floats.size()];
        for (int i = 0; i < floats.size(); i++) {
            arr[i] = floats.get(i);
        }
        return arr;
    }

    private static String safe(String s) { return s != null ? s : ""; }
}
