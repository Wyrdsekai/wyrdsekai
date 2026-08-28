package org.wyrdsekai.core.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.wyrdsekai.core.crypto.PrivateJournalCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.AppVersion;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.ModelAttribution;
import org.wyrdsekai.core.library.KnowledgePackRegistry;
import org.wyrdsekai.core.library.Provenance;

import java.io.Closeable;
import java.util.stream.Collectors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;
import org.apache.lucene.search.DocIdSetIterator;

/**
 * Embedded vector + text search store using Apache Lucene.
 * Adapted from CodeZaiku's LuceneVectorStore for Wyrdsekai's five collection types.
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
    private volatile boolean closed = false;

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
        upsert(SearchCollections.KNOWLEDGE, id,
            knowledgeDoc(id, packName, title, content, source, subject, embedding, provenance),
            false);
    }

    /**
     * Bulk variant of {@link #insertKnowledge}: no per-document searcher
     * refresh, no interleaved commit. A pack indexer inserting millions of
     * chunks pays for an NRT reader reopen (a segment flush) on EVERY
     * document through the normal path — the household node measured
     * ~170 chunks/s on a 13.7M-chunk shelf publish, almost all of it
     * refresh and fsync. The caller owns visibility: nothing is promised
     * searchable until its next {@link #commitAll()}.
     */
    public void insertKnowledgeBulk(String id, String packName, String title, String content,
                                     String source, String subject, List<Float> embedding,
                                     Provenance provenance) {
        upsert(SearchCollections.KNOWLEDGE, id,
            knowledgeDoc(id, packName, title, content, source, subject, embedding, provenance),
            true);
    }

    private Document knowledgeDoc(String id, String packName, String title, String content,
                                   String source, String subject, List<Float> embedding,
                                   Provenance provenance) {
        var doc = newDocument(id, content, embedding);
        doc.add(new StringField("pack", safe(packName), Field.Store.YES));
        doc.add(new StoredField("title", safe(title)));
        addChunkOrder(doc, title);
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
        return doc;
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
                // Same lookup-pack exclusion as the default path — this branch
                // builds its query directly and would otherwise be the one door
                // dictionary rows still walked through.
                for (var lookup : KnowledgePackRegistry.lookupPackNames()) {
                    tierClause.add(new TermQuery(new Term("pack", lookup)),
                        BooleanClause.Occur.MUST_NOT);
                }

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
        return doSearch(SearchCollections.KNOWLEDGE, queryText, queryEmbedding, topK,
            defaultKnowledgeFilter(), mode);
    }

    /**
     * DICTIONARIES ARE A LOOKUP SURFACE, NOT A BROWSING CORPUS. The bundled
     * FreeDict + JMdict packs (~286k three-word headword rows) live in this
     * same index, and BM25's length normalization makes a gloss unbeatable
     * for any short query — live 2026-08-24, "glass tide" returned Spanish
     * tide-glossary rows above the household's actual Glass Tide passages,
     * the fairy-tale tool wove tales about vocabulary, and the RelevanceFloor
     * could not save it because a gloss for the queried word is genuinely
     * relevant. Exclusion at QUERY time, not post-filter: the rows would
     * otherwise crowd out every topK slot before a filter could run.
     * {@link #searchKnowledgeByPack} stays unfiltered — the explicit door for
     * tooling that really wants a dictionary.
     */
    private static volatile String cachedKnowledgeFilter;
    private static String defaultKnowledgeFilter() {
        var f = cachedKnowledgeFilter;
        if (f == null) {
            var names = KnowledgePackRegistry.lookupPackNames();
            f = names.isEmpty() ? "" : names.stream()
                .map(n -> "pack != \"" + n + "\"")
                .collect(Collectors.joining(" && "));
            cachedKnowledgeFilter = f;
        }
        return f.isEmpty() ? null : f;
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
        return doSearch(SearchCollections.KNOWLEDGE, queryText, null, topK,
            defaultKnowledgeFilter(), SearchMode.TEXT_ONLY);
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
        addChunkOrder(doc, title);
        doc.add(new StringField("collection", safe(collection), Field.Store.YES));
        doc.add(new StoredField("timestamp", timestamp));
        doc.add(new StoredField("version", version));
        if (vectorClockJson != null) doc.add(new StoredField("vector_clock", vectorClockJson));
        if (lastModifiedBy != null) doc.add(new StringField("last_modified_by", lastModifiedBy, Field.Store.YES));
        doc.add(new StoredField("deleted", deleted ? 1 : 0));
        upsert(SearchCollections.STUDY, id, doc);
    }

    /**
     * Rewrite the OWNER of every Study document from one identity to another.
     *
     * <p>The index-side half of a person rebind. Re-ingesting is not available
     * as a mechanism: a household that migrates will not still have the source
     * files, and nobody is re-extracting their library because an identity key
     * changed. So the owner field is rewritten in place, document by document.</p>
     *
     * <p><b>Resumable by construction</b> — each batch is committed before the
     * next is read, and documents already carrying {@code toOwner} are simply
     * not matched by the query. Re-running after an interrupted pass continues
     * where it stopped. On a 13.7M-chunk Study running on a machine that
     * hard-halts, that is not a theoretical requirement.</p>
     *
     * @param fromOwner    the owner string currently stored
     * @param toOwner      the person DID to store instead
     * @param batchSize    documents per commit
     * @param progress     called with the running total after each batch
     * @return number of documents rewritten
     */
    public long rewriteStudyOwner(String fromOwner, String toOwner, int batchSize,
                                  LongConsumer progress) {
        if (fromOwner == null || toOwner == null || fromOwner.equals(toOwner)) return 0;
        long total = 0;
        long vectorsDropped = 0;
        long privateSkipped = 0;
        try {
            var sm = getSearcherManager(SearchCollections.STUDY);
            while (true) {
                var searcher = sm.acquire();
                var rebuilt = new ArrayList<Document>();
                var ids = new ArrayList<String>();
                try {
                    var q = new TermQuery(new Term("user_did", safe(fromOwner)));
                    var hits = searcher.search(q, batchSize);
                    if (hits.scoreDocs.length == 0) break;

                    var storedFields = searcher.storedFields();
                    for (var sd : hits.scoreDocs) {
                        var old = storedFields.document(sd.doc);
                        var id = old.get(FIELD_ID);
                        if (id == null) continue;

                        // A document CANNOT be round-tripped through the reader:
                        // storedFields returns only STORED fields, so the analyzed
                        // content field and the vector are absent. Copying them
                        // would produce a document that is present but unsearchable.
                        // Rebuild through the same construction path as a normal
                        // insert instead, from the stored values.
                        var content = old.get(FIELD_CONTENT_STORED);
                        var itemType = old.get("item_type");

                        // PRIVATE JOURNAL ENTRIES CANNOT BE FIELD-REWRITTEN.
                        // PrivateJournalCipher uses the owner string as both the
                        // HKDF purpose and the GCM AAD, so an entry encrypted
                        // under one identity can only ever be decrypted as that
                        // identity. Moving the owner without re-encrypting would
                        // leave the person's most private writing permanently
                        // unreadable — and it is original data, recoverable from
                        // nowhere. Decrypt with the old identity, re-encrypt with
                        // the new, inside the same batch.
                        if ("journal_private".equals(itemType) && content != null) {
                            try {
                                var plain = PrivateJournalCipher.decryptIfNeeded(fromOwner, content);
                                content = PrivateJournalCipher.encrypt(toOwner, plain);
                            } catch (RuntimeException ce) {
                                // Fail CLOSED: skip rather than risk writing
                                // plaintext or an entry nobody can open.
                                privateSkipped++;
                                log.error("Private journal '{}' could NOT be re-encrypted {} -> {}: "
                                    + "{} — left untouched, re-run after fixing the zone key",
                                    id, fromOwner, toOwner, ce.toString());
                                continue;
                            }
                        }

                        var doc = newDocument(id, content, null);
                        if (old.get(FIELD_VECTOR) == null && content != null) vectorsDropped++;

                        doc.add(new StringField("user_did", safe(toOwner), Field.Store.YES));
                        copyStored(old, doc, "item_type", true);
                        copyStored(old, doc, "collection", true);
                        copyStored(old, doc, "last_modified_by", true);
                        copyStored(old, doc, "title", false);
                        copyStored(old, doc, "vector_clock", false);
                        var ts = old.get("timestamp");
                        doc.add(new StoredField("timestamp", ts == null ? 0L : Long.parseLong(ts)));
                        var ver = old.get("version");
                        doc.add(new StoredField("version", ver == null ? 1 : Integer.parseInt(ver)));
                        var del = old.get("deleted");
                        doc.add(new StoredField("deleted", del == null ? 0 : Integer.parseInt(del)));
                        // Chunk order — same hand-copied-field-list hole that let
                        // the vector write-back strip the adjacency backfill.
                        addChunkOrder(doc, old.get("title"));

                        rebuilt.add(doc);
                        ids.add(id);
                    }
                } finally {
                    sm.release(searcher);
                }
                if (rebuilt.isEmpty()) break;

                var writer = getWriter(SearchCollections.STUDY);
                for (int i = 0; i < rebuilt.size(); i++) {
                    writer.updateDocument(new Term(FIELD_ID, ids.get(i)), rebuilt.get(i));
                }
                writer.commit();
                refreshSearcher(SearchCollections.STUDY);

                total += rebuilt.size();
                if (progress != null) progress.accept(total);
            }
            log.info("Study owner rewrite complete: {} -> {} ({} documents)",
                fromOwner, toOwner, total);
            if (privateSkipped > 0) {
                log.error("Study owner rewrite left {} PRIVATE journal entries under the old "
                    + "identity because they could not be re-encrypted. They are not lost, but "
                    + "they are not readable as {} either — fix the zone key and re-run.",
                    privateSkipped, toOwner);
            }
            if (vectorsDropped > 0) {
                log.warn("Study owner rewrite dropped embeddings on {} documents — Lucene does "
                    + "not expose stored vectors for round-trip. Run `wyrd embed-migrate --run` "
                    + "to re-emit them; BM25 retrieval is unaffected.", vectorsDropped);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Study owner rewrite stopped after {} documents ({} -> {}): {} "
                + "— re-run to resume", total, fromOwner, toOwner, e.toString());
        }
        return total;
    }

    /** Carry one stored field across a document rebuild, if present. */
    private static void copyStored(Document from, Document to, String field, boolean indexed) {
        var v = from.get(field);
        if (v == null) return;
        if (indexed) to.add(new StringField(field, v, Field.Store.YES));
        else to.add(new StoredField(field, v));
    }

    /** Hard-delete a Study item by id (CRDT tombstone that dominated local). */
    public long deleteStudyItem(String id) {
        return deleteById(SearchCollections.STUDY, id);
    }

    /** Search a user's Study (all types). */
    public List<SearchResult> searchStudy(String userDid, String queryText, int topK) {
        return searchStudy(userDid, queryText, null, topK);
    }

    /**
     * Search a user's Study, semantically reranked when a query vector is given
     * (2026-08-05).
     *
     * <p>Study holds full-text ingests — a Calibre run puts millions of chunks
     * here — and those chunks are indexed for BM25 without stored vectors, so
     * plain TEXT_ONLY returns them in keyword order and nothing else. Passing a
     * query embedding switches on {@link SearchMode#SPARSE_RERANK}: BM25 supplies
     * recall, the semantic pass supplies the ordering. Passing null keeps the old
     * TEXT_ONLY behaviour exactly, so existing callers are unaffected.
     */
    public List<SearchResult> searchStudy(String userDid, String queryText,
                                           List<Float> queryEmbedding, int topK) {
        String filter = "user_did == \"" + userDid + "\"";
        var mode = queryEmbedding == null ? SearchMode.TEXT_ONLY : SearchMode.SPARSE_RERANK;
        return doSearch(SearchCollections.STUDY, queryText, queryEmbedding, topK, filter, mode);
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
        return searchStudyByCollection(userDid, collection, queryText, null, topK);
    }

    /** Sub-collection search with optional semantic rerank — see {@link
     *  #searchStudy(String, String, List, int)}. This is the one that matters for
     *  a book collection: constrain to the collection, then rerank inside it. */
    public List<SearchResult> searchStudyByCollection(String userDid, String collection,
                                                        String queryText,
                                                        List<Float> queryEmbedding, int topK) {
        String filter = "user_did == \"" + userDid + "\" && collection == \"" + collection + "\"";
        var mode = queryEmbedding == null ? SearchMode.TEXT_ONLY : SearchMode.SPARSE_RERANK;
        return doSearch(SearchCollections.STUDY, queryText, queryEmbedding, topK, filter, mode);
    }

    /**
     * Full scan of one Study sub-collection — every document, no search cap.
     *
     * <p>Exists for {@code share-collection} (2026-08-25): the old pack export
     * fetched via a broad search with a 10,000-item limit, which would have
     * silently truncated the home node's 74,697-volume shelf. A share that
     * drops two-thirds of the library without saying so is worse than no
     * share. Pages with {@code searchAfter} so memory stays flat at any size.
     *
     * @return number of documents delivered to the consumer
     */
    public int scanStudyCollection(String userDid, String collection,
                                   java.util.function.Consumer<SearchResult> consumer) {
        try {
            var sm = getSearcherManager(SearchCollections.STUDY);
            var searcher = sm.acquire();
            try {
                var q = new BooleanQuery.Builder()
                    .add(new TermQuery(new Term("user_did", safe(userDid))),
                        BooleanClause.Occur.FILTER)
                    .add(new TermQuery(new Term("collection", safe(collection))),
                        BooleanClause.Occur.FILTER)
                    .build();
                int total = 0;
                ScoreDoc after = null;
                while (true) {
                    var page = after == null
                        ? searcher.search(q, 500)
                        : searcher.searchAfter(after, q, 500);
                    if (page.scoreDocs.length == 0) break;
                    for (var r : extractResults(SearchCollections.STUDY, searcher, page)) {
                        consumer.accept(r);
                        total++;
                    }
                    after = page.scoreDocs[page.scoreDocs.length - 1];
                }
                return total;
            } finally {
                sm.release(searcher);
            }
        } catch (IOException e) {
            log.warn("scanStudyCollection failed for '{}': {}", collection, e.getMessage());
            return 0;
        }
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
    public enum SearchMode { HYBRID, SET_UNION, DENSE_ONLY, TEXT_ONLY, SPARSE_RERANK }

    /**
     * SPARSE_RERANK tuning (2026-08-05). First stage pulls this multiple of topK
     * from BM25 before the semantic pass; the floor keeps small topK requests from
     * reranking a candidate set too thin to reorder meaningfully.
     */
    private static final int RERANK_CANDIDATE_FACTOR = 6;
    private static final int RERANK_MIN_CANDIDATES = 24;
    private static final int RERANK_MAX_CANDIDATES = 64;

    /**
     * Hard wall-clock budget for the semantic pass (2026-08-05).
     *
     * <p>Learned live: {@link EmbeddingService} exposes only single-text
     * {@code embed()} — there is no batch call — and the household embedder is
     * bge-m3 (568M). The first version reranked up to 300 candidates by calling
     * embed() once each, sequentially, and a study search over the 13.7M-chunk
     * book corpus simply never returned (curl gave up at 90s). Candidate counts
     * alone cannot make that safe, because embed latency varies with host, model
     * and load. So the pass is bounded by TIME: embed until the deadline, rank
     * what got scored, and append the rest in BM25 order. Worst case degrades to
     * plain keyword search; it can never hang the caller.
     */
    private static final long RERANK_BUDGET_MS = 2_500L;

    /**
     * Characters of each candidate fed to the reranking encoder (2026-08-05).
     *
     * <p>Measured on second-node: study chunks average <b>2,910 chars ≈ 727 tokens</b>,
     * and bge-m3 runs full self-attention over all of them — ~1.25s per passage,
     * so the 2.5s budget scored just <b>2 of 60</b> candidates and the semantic
     * half of the two-stage design was effectively inert.
     *
     * <p>A relevance judgement does not need the whole passage: a prefix
     * establishes topic. Attention is quadratic in sequence length, so cutting
     * ~727 tokens to ~150 buys far more than the 5x the token count suggests.
     * This trades a little tail-sensitivity (a passage whose relevance only
     * appears in its last paragraph ranks lower than it should) for a rerank
     * that actually runs — the right trade when the alternative is 58 of 60
     * results in raw keyword order.
     */
    private static final int RERANK_EMBED_CHARS = 600;

    /**
     * Query-time vector cache for reranking, keyed by result id.
     *
     * <p>Deliberately in-memory rather than written back into the index: a search
     * must not mutate the index it is reading (writer contention, and a read path
     * that silently writes is the kind of thing that is impossible to reason about
     * later). Bounded — evicts oldest on overflow. Warm queries pay nothing; cold
     * ones pay one embed per candidate.
     */
    private static final int RERANK_CACHE_MAX = 50_000;
    private final Map<String, List<Float>> rerankVectorCache =
        Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, List<Float>> e) {
                return size() > RERANK_CACHE_MAX;
            }
        });

    /**
     * Does this document carry a persisted vector yet?
     *
     * <p>Operational visibility for the write-back: "how warm is this index?" was
     * a question that could only be answered by attaching a probe to the live
     * node (13,746,741 Study documents, zero vectors). It should be askable from
     * inside.</p>
     */
    public boolean hasStoredVector(String collection, String docId) {
        return storedVector(collection, docId) != null;
    }

    /**
     * A document's persisted vector, if the index carries one.
     *
     * <p>The whole point of write-back: once a chunk has been embedded, reading
     * it back costs a term lookup instead of a model call. Returns null when the
     * document has no vector — which is every Study chunk until write-back has
     * touched it.</p>
     */
    private List<Float> storedVector(String collection, String docId) {
        if (collection == null || docId == null) return null;
        try {
            var sm = getSearcherManager(collection);
            var searcher = sm.acquire();
            try {
                var hits = searcher.search(new TermQuery(new Term(FIELD_ID, docId)), 1);
                if (hits.scoreDocs.length == 0) return null;
                return readVector(searcher.getIndexReader(), hits.scoreDocs[0].doc);
            } finally {
                sm.release(searcher);
            }
        } catch (Exception e) {
            return null;   // never let a cache probe break a search
        }
    }

    /**
     * Persist vectors the rerank just computed, so no chunk is ever embedded twice.
     *
     * <p><b>The measurement that motivates this.</b> The live Study index holds
     * <b>13,746,741 documents and zero vectors</b> — it was built BM25-only. So
     * every semantic rerank embeds its candidates from scratch, every query,
     * forever: 64 chunks, ~48s, and a rerank cache that reports {@code 0 cached}
     * because each query draws a different BM25 top-N. Capping the work (the
     * budget fix) traded recall for latency; this removes the trade instead, by
     * making the second encounter with a chunk free.</p>
     *
     * <p>Lucene cannot update one field, so each document is rebuilt through the
     * same construction path as a normal insert — {@code storedFields()} returns
     * only STORED fields, and copying them would produce a document that is
     * present but unsearchable. Every stored field a Study item carries is
     * carried across explicitly; a document missing any of them is <b>skipped</b>
     * rather than written back thinner than it was.</p>
     *
     * <p>Best-effort and bounded: it writes only what the rerank already paid to
     * compute, so the index warms along the paths the household actually reads.
     * A failure here costs a re-embed next time and nothing else.</p>
     *
     * @return how many documents gained a vector
     */
    public int writeBackVectors(String collection, Map<String, List<Float>> vectors) {
        if (collection == null || vectors == null || vectors.isEmpty()) return 0;
        int written = 0;
        int skipped = 0;
        try {
            var sm = getSearcherManager(collection);
            var searcher = sm.acquire();
            var rebuilt = new ArrayList<Document>();
            var ids = new ArrayList<String>();
            try {
                var storedFields = searcher.storedFields();
                for (var e : vectors.entrySet()) {
                    if (e.getValue() == null || e.getValue().size() != denseDim) { skipped++; continue; }
                    var hits = searcher.search(new TermQuery(new Term(FIELD_ID, e.getKey())), 1);
                    if (hits.scoreDocs.length == 0) { skipped++; continue; }
                    var old = storedFields.document(hits.scoreDocs[0].doc);
                    var content = old.get(FIELD_CONTENT_STORED);
                    // No stored content means no searchable document could be
                    // rebuilt — leave the original alone rather than replace it
                    // with a hollow one carrying a vector.
                    if (content == null || content.isEmpty()) { skipped++; continue; }

                    var doc = newDocument(e.getKey(), content, e.getValue());
                    copyStored(old, doc, "user_did", true);
                    copyStored(old, doc, "item_type", true);
                    copyStored(old, doc, "collection", true);
                    copyStored(old, doc, "last_modified_by", true);
                    copyStored(old, doc, "title", false);
                    copyStored(old, doc, "vector_clock", false);
                    copyLongStored(old, doc, "timestamp");
                    var ver = old.get("version");
                    doc.add(new StoredField("version", ver == null ? 1 : Integer.parseInt(ver)));
                    var del = old.get("deleted");
                    doc.add(new StoredField("deleted", del == null ? 0 : Integer.parseInt(del)));
                    // Chunk order, or this write-back UNDOES the adjacency backfill.
                    //
                    // Live 2026-08-09, within two hours of shipping both features:
                    // the backfill placed all 13.7M documents at 11:50; at 13:04 a
                    // rerank warmed its cache, this rebuild ran with the field list
                    // above — written before doc_group/part existed — and quietly
                    // stripped the placement from the exact chunks people query
                    // most. The neighbour read then reported doc_group='null' on a
                    // document the raw index had carried correctly an hour earlier.
                    //
                    // Every hand-copied field list in this file is a copy of this
                    // bug waiting for the next new field.
                    addChunkOrder(doc, old.get("title"));
                    rebuilt.add(doc);
                    ids.add(e.getKey());
                }
            } finally {
                sm.release(searcher);
            }
            if (rebuilt.isEmpty()) return 0;
            var writer = getWriter(collection);
            for (int i = 0; i < rebuilt.size(); i++) {
                writer.updateDocument(new Term(FIELD_ID, ids.get(i)), rebuilt.get(i));
            }
            writer.commit();
            refreshSearcher(collection);
            written = rebuilt.size();
        } catch (IOException | RuntimeException e) {
            log.debug("Vector write-back on '{}' failed ({}) — chunks stay unembedded",
                collection, e.toString());
            return 0;
        }
        log.info("Vector write-back on '{}': {} document(s) now carry an embedding{}",
            collection, written, skipped > 0 ? " (" + skipped + " skipped)" : "");
        return written;
    }

    /**
     * The rerank's embedding for a document, if it already has one.
     *
     * <p>Exposed so a later stage does not pay to embed text this store just
     * embedded. Measured live 2026-08-08: the Study leg spent 48s reranking 64
     * candidates, then the merged-result relevance floor spent another 7.4s
     * re-embedding the survivors — the same passages, a second time, seconds
     * apart. Reusing the vector also keeps the two stages scoring on identical
     * numbers instead of two independent embeddings of truncated text.</p>
     *
     * @return the cached vector, or null if this document has not been scored
     */
    public List<Float> cachedRerankVector(String docId) {
        return docId == null ? null : rerankVectorCache.get(docId);
    }

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
        closed = true;
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

    /**
     * Re-point every document owned by one agent identity to another.
     *
     * <p><b>Why this is separate from {@code rewriteStudyOwner}.</b> That one
     * rewrites {@code user_did} on the Study collection and accepts dropping
     * embeddings — at 13.7M documents, re-emitting them is its own job. An
     * agent's own memory is a few hundred documents, and dropping their vectors
     * would quietly cost her semantic recall of everything she remembered under
     * the old identity. So this one <b>recovers the vectors</b> and carries them
     * across.</p>
     *
     * <p>Lucene will not let you round-trip a document through the reader —
     * {@code storedFields()} returns only STORED fields, so the analyzed content
     * field and the vector are absent and a copied document is present but
     * unsearchable. The content is rebuilt through {@link #newDocument} from the
     * stored copy, and the vector is read from the per-leaf float vector values,
     * which IS exposed even though the stored document doesn't carry it.</p>
     *
     * <p>Needed because a companion's memories live under {@code agent_did} in
     * {@code memory_items} and {@code soul_fragments}, nothing re-indexes them
     * from SQL, and folding two identities in {@code world.db} alone would leave
     * every memory from the folded identity indexed under a DID that no longer
     * answers — there, and invisible to her.</p>
     *
     * @param collection {@code memory_items} or {@code soul_fragments}
     * @return how many documents were re-pointed
     */
    public long rewriteAgentDid(String collection, String fromDid, String toDid,
                                int batchSize, LongConsumer progress) {
        if (collection == null || fromDid == null || toDid == null || fromDid.equals(toDid)) {
            return 0;
        }
        long total = 0;
        long vectorsCarried = 0;
        long vectorsLost = 0;
        try {
            var sm = getSearcherManager(collection);
            while (true) {
                var searcher = sm.acquire();
                var rebuilt = new ArrayList<Document>();
                var ids = new ArrayList<String>();
                try {
                    var q = new TermQuery(new Term("agent_did", safe(fromDid)));
                    var hits = searcher.search(q, batchSize);
                    if (hits.scoreDocs.length == 0) break;

                    var storedFields = searcher.storedFields();
                    for (var sd : hits.scoreDocs) {
                        var old = storedFields.document(sd.doc);
                        var id = old.get(FIELD_ID);
                        if (id == null) continue;

                        var content = old.get(FIELD_CONTENT_STORED);
                        var vector = readVector(searcher.getIndexReader(), sd.doc);
                        if (vector != null) vectorsCarried++; else if (content != null) vectorsLost++;

                        var doc = newDocument(id, content, vector);
                        doc.add(new StringField("agent_did", safe(toDid), Field.Store.YES));
                        // Everything else the two agent collections store. Absent
                        // fields are skipped by copyStored, so one pass serves both.
                        copyStored(old, doc, "item_type", true);
                        copyStored(old, doc, "fragment_type", true);
                        copyStored(old, doc, "room_id", true);
                        copyStored(old, doc, "superseded_by", true);
                        copyStored(old, doc, "authoring_model", false);
                        copyLongStored(old, doc, "timestamp");
                        copyLongStored(old, doc, "valid_from");
                        copyLongStored(old, doc, "superseded_at");
                        var sig = old.get("significance");
                        if (sig != null) {
                            doc.add(new StoredField("significance", Float.parseFloat(sig)));
                        }
                        rebuilt.add(doc);
                        ids.add(id);
                    }
                } finally {
                    sm.release(searcher);
                }
                if (rebuilt.isEmpty()) break;

                var writer = getWriter(collection);
                for (int i = 0; i < rebuilt.size(); i++) {
                    writer.updateDocument(new Term(FIELD_ID, ids.get(i)), rebuilt.get(i));
                }
                writer.commit();
                refreshSearcher(collection);

                total += rebuilt.size();
                if (progress != null) progress.accept(total);
            }
            log.info("Agent DID rewrite on '{}': {} -> {} ({} documents, {} vectors carried"
                    + "{})", collection, fromDid, toDid, total, vectorsCarried,
                vectorsLost > 0 ? ", " + vectorsLost + " had none to carry" : "");
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Agent DID rewrite failed on '" + collection
                + "' after " + total + " document(s): " + e.getMessage(), e);
        }
        return total;
    }

    /**
     * The stored document never contains the vector; the index does. Walk the
     * leaves to find the one holding this doc and read its float vector.
     */
    private List<Float> readVector(IndexReader reader, int docId) {
        try {
            for (var leaf : reader.leaves()) {
                int base = leaf.docBase;
                int max = base + leaf.reader().maxDoc();
                if (docId < base || docId >= max) continue;
                var values = leaf.reader().getFloatVectorValues(FIELD_VECTOR);
                if (values == null) return null;
                int target = docId - base;
                var iter = values.iterator();
                for (int d = iter.nextDoc(); d != DocIdSetIterator.NO_MORE_DOCS;
                        d = iter.nextDoc()) {
                    if (d == target) {
                        var raw = values.vectorValue(d);
                        if (raw == null) return null;
                        var out = new ArrayList<Float>(raw.length);
                        for (var f : raw) out.add(f);
                        return out;
                    }
                    if (d > target) break;
                }
                return null;
            }
        } catch (Exception e) {
            log.debug("Could not recover vector for doc {}: {}", docId, e.toString());
        }
        return null;
    }

    /** Copy a stored long field when present, so absent fields stay absent. */
    private static void copyLongStored(Document from, Document to, String field) {
        var v = from.get(field);
        if (v == null) return;
        try {
            to.add(new StoredField(field, Long.parseLong(v)));
        } catch (NumberFormatException ignored) {
            // Not a long on this document — leave it off rather than corrupt it.
        }
    }

    /** Field naming the document a chunk belongs to — the book, not the piece. */
    static final String FIELD_DOC_GROUP = "doc_group";
    /** Field holding the chunk's position within that document; -1 when unpaginated. */
    static final String FIELD_PART = "part";

    /** {@code "The Diamond Age - Neal Arden.epub (part 78/471)"} → part 78 of 471. */
    private static final Pattern PART_SUFFIX =
        Pattern.compile("^(.*?)\\s*\\(part\\s+(\\d+)\\s*/\\s*(\\d+)\\)\\s*$");

    /**
     * Record which document a chunk came from and where in it.
     *
     * <p><b>Why this had to be indexed and was not.</b> {@code title} was a
     * {@link StoredField} — written down, never searchable — so nothing could ask
     * "what comes before this?". A book is 471 pieces held together only by a
     * string nobody could query, which means an answer that straddles a boundary
     * is unreachable by construction.</p>
     *
     * <p>The live case: Coleridge's <i>The Raven</i>, the poem Finkle-McGraw
     * sends Hackworth, is part 78 of <i>The Diamond Age</i>. The letter naming
     * both men is part 79. A question about the poem matches the letter and
     * never the verse — the poem shares no vocabulary with the question — so the
     * text sat in the library, retrievable in principle, unreachable in practice.</p>
     *
     * <p>Unpaginated documents get their own title as the group and part -1, so
     * every document carries the field. That is what makes the backfill able to
     * ask "which are still missing?" and therefore able to resume.</p>
     */
    private static void addChunkOrder(Document doc, String title) {
        var t = safe(title);
        var m = PART_SUFFIX.matcher(t);
        String group = t;
        int part = -1;
        if (m.matches()) {
            group = m.group(1).trim();
            try {
                part = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {
                part = -1;
            }
        }
        doc.add(new StringField(FIELD_DOC_GROUP, group, Field.Store.YES));
        doc.add(new IntPoint(FIELD_PART, part));
        doc.add(new StoredField(FIELD_PART, part));
    }

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
        upsert(collection, id, doc, false);
    }

    /**
     * @param bulk when true, skip the per-document searcher refresh and the
     *             interleaved commit — the caller batches its own
     *             {@link #commitAll()}. The refresh is the right default for
     *             single inserts (a new memory must be searchable at once)
     *             but forces an NRT segment flush per document, which is
     *             what held a 13.7M-chunk shelf publish to ~170 chunks/s.
     */
    private void upsert(String collection, String id, Document doc, boolean bulk) {
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
            if (bulk) return;
            refreshSearcher(collection);
            // Commit to disk periodically (not every insert)
            if (writer.getDocStats().numDocs % 100 == 0) {
                writer.commit();
            }
        } catch (AlreadyClosedException e) {
            // The store is shutting down. This is not a per-document failure —
            // every remaining document in a bulk run would log the same line
            // (35,856 of them in 4s when a 13.7M-chunk publish met a service
            // restart, 2026-08-25). Let it out so the caller stops the loop.
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("Insert failed for '{}' in '{}': {}", id, collection, e.getMessage());
        }
    }

    private long deleteById(String collection, String id) {
        return deleteByField(collection, FIELD_ID, id);
    }

    /**
     * Delete many documents by id in one pass: batched {@code Term}s, ONE
     * commit at the end. {@link #deleteById} commits and reopens the searcher
     * per id, which is right for deleting one note and ruinous for deleting
     * 74,694 of them (the same per-document-flush shape that held a shelf
     * publish to 170 chunks/s).
     *
     * @return documents actually removed
     */
    public long deleteByIdsBulk(String collection, List<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        try {
            var writer = getWriter(collection);
            long before = writer.getDocStats().numDocs;
            final int BATCH = 5000;
            for (int i = 0; i < ids.size(); i += BATCH) {
                var slice = ids.subList(i, Math.min(i + BATCH, ids.size()));
                var terms = new Term[slice.size()];
                for (int j = 0; j < slice.size(); j++) {
                    terms[j] = new Term(FIELD_ID, slice.get(j));
                }
                writer.deleteDocuments(terms);
            }
            writer.commit();
            refreshSearcher(collection);
            long deleted = before - writer.getDocStats().numDocs;
            log.info("Bulk-deleted {} docs from '{}' ({} ids given)",
                deleted, collection, ids.size());
            return deleted;
        } catch (IOException | RuntimeException e) {
            log.warn("Bulk delete failed on '{}': {}", collection, e.getMessage());
            return 0;
        }
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
                case SPARSE_RERANK ->
                    sparseRerankSearch(collection, queryText, queryEmbedding, topK, filter);
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
            // NORMALISE HERE, not at one call site. Only the rerank's candidate
            // fetch called keywordsOf, so the Study leg searched cleaned terms
            // while the knowledge-pack leg searched the raw string — same query,
            // two different treatments, and the pack side returned a gardening
            // post for a question about Glass Tide (it matched "glass").
            //
            // The model also writes queries like:
            //   velshara OR Glass Tide AND (librarian OR Kestan) — any mention of a
            //   scene where a Librarian explains VelSharas to Kestan. Look for: ...
            // Escaped, that is one long bag of terms in which the two words that
            // matter are outvoted by a paragraph of instructions.
            var parser = new QueryParser(FIELD_CONTENT, analyzer);
            var textQuery = parser.parse(
                QueryParser.escape(keywordsOf(queryText, searcher.getIndexReader())));
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
    /**
     * Two-stage retrieval: wide BM25 recall, then a semantic pass over the
     * survivors (2026-08-05, operator's design).
     *
     * <p>Why this exists: a full-text ingest can add millions of chunks that have
     * no stored vectors (the Calibre run added ~4.4M). Those chunks are invisible
     * to {@link #denseSearch} and, under SET_UNION, can only arrive as an appended
     * tail in raw BM25 order. This mode makes them semantically usable without
     * paying to embed the whole corpus at index time: BM25 supplies recall, and
     * only the candidates it returns get embedded — bounded, per query, at
     * roughly {@code topK * RERANK_CANDIDATE_FACTOR} passages.
     *
     * <p>Degrades rather than fails: with no query embedding, no embedding
     * service, or an embed error, the BM25 ordering is returned untouched. The
     * caller always gets results.
     *
     * <p>Honest limitation: lexical first-stage recall depends on the query
     * carrying usable keywords. A vague conversational query retrieves poorly
     * here no matter how good the reranker is — callers should expand intent into
     * keywords (or constrain by a catalog hit) before reaching for this mode.
     */
    private List<SearchResult> sparseRerankSearch(String collection, String queryText,
                                                   List<Float> queryEmbedding, int topK,
                                                   String filter) throws IOException {
        int candidateK = Math.clamp((long) topK * RERANK_CANDIDATE_FACTOR,
            RERANK_MIN_CANDIDATES, RERANK_MAX_CANDIDATES);
        // The two legs want different things from the same question: BM25 wants
        // discriminating TERMS, the reranker wants the whole INTENT. So the first
        // stage searches on content words only while the semantic pass scores
        // against an embedding of the full query. Costs nothing — no extra
        // inference — and it is the difference between a companion's vague
        // "i wonder how greenhouses keep warm at night" retrieving on
        // {greenhouses, warm, night} versus drowning in stopword matches.
        var candidates = sparseSearch(collection, queryText, candidateK, filter);
        if (candidates.isEmpty() || candidates.size() <= 1) return candidates;

        var svc = EmbeddingService.get();
        if (queryEmbedding == null || svc == null) {
            log.debug("SPARSE_RERANK on '{}': no query vector or embedding service — BM25 order",
                collection);
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }

        record Scored(SearchResult r, float score) {}
        var scored = new ArrayList<Scored>(candidates.size());
        var unscored = new ArrayList<SearchResult>();
        int embedded = 0, cached = 0;
        // What this query paid to compute — persisted afterwards so the
        // next query gets it for nothing (lazy embed write-back).
        var freshlyEmbedded = new LinkedHashMap<String, List<Float>>();
        long deadline = System.nanoTime() + RERANK_BUDGET_MS * 1_000_000L;

        // BATCH the uncached candidates into ONE session run. Embedding used to
        // be one ONNX call per candidate, which is why a 2.5s budget scored only
        // 2 of 60 on a live Study — per-call overhead dominated, not sequence
        // length (truncating to 600 chars only reached 6 of 60). One run over the
        // whole batch is the actual fix.
        var toEmbed = new ArrayList<SearchResult>();
        var bodies = new ArrayList<String>();
        for (var c : candidates) {
            if (rerankVectorCache.get(c.id()) != null) { cached++; continue; }
            // Free if the index already carries it. The Study collection was built
            // BM25-only — 13.7M documents, zero vectors — so this misses today and
            // starts hitting as write-back warms the paths actually read.
            var stored = storedVector(collection, c.id());
            if (stored != null) {
                rerankVectorCache.put(c.id(), stored);
                cached++;
                continue;
            }
            toEmbed.add(c);
            bodies.add(c.content() == null ? ""
                : c.content().length() > RERANK_EMBED_CHARS
                    ? c.content().substring(0, RERANK_EMBED_CHARS)
                    : c.content());
        }
        // SLICE THE BATCH SO THE BUDGET STILL MEANS SOMETHING.
        //
        // The deadline used to bite per candidate. Batching — the right fix for
        // per-call overhead — collapsed the loop into ONE call, and the check
        // became a single test before starting: after that the budget could not
        // stop anything. Measured on the live household 2026-08-08: 64 candidates
        // took ~48s against a declared 2.5s budget, roughly 20x over.
        //
        // That is not just slow, it changes the outcome. The ReAct loop gave up
        // at ~37s and spoke; the passages landed 23s later, with nobody waiting
        // for them. A tool that answers after the conversation has moved on has
        // not answered.
        //
        // Slices keep almost all of the batching win (per-call overhead is
        // amortised across the slice) while giving the deadline somewhere to
        // apply. Whatever is not reached keeps its BM25 position, which is the
        // same graceful degradation as before.
        final int SLICE = 16;
        for (int start = 0; start < bodies.size(); start += SLICE) {
            if (System.nanoTime() >= deadline) {
                log.info("SPARSE_RERANK '{}': budget spent after {} of {} candidates — "
                    + "the rest keep BM25 order", collection, start, bodies.size());
                break;
            }
            int end = Math.min(start + SLICE, bodies.size());
            try {
                var vecs = svc.embedBatch(bodies.subList(start, end));
                for (int i = 0; i < vecs.size() && (start + i) < toEmbed.size(); i++) {
                    var v = vecs.get(i);
                    if (v != null) {
                        var docId = toEmbed.get(start + i).id();
                        rerankVectorCache.put(docId, v);
                        freshlyEmbedded.put(docId, v);
                        embedded++;
                    }
                }
            } catch (RuntimeException e) {
                // A failed slice must not sink the rerank — survivors keep BM25 order.
                log.debug("rerank slice embed failed ({}) — BM25 order for the remainder",
                    e.getMessage());
                break;
            }
        }

        for (var c : candidates) {
            var vec = rerankVectorCache.get(c.id());
            if (vec == null) {
                // Never embedded (budget, or batch failure) — keep BM25 position.
                unscored.add(c);
                continue;
            }
            scored.add(new Scored(c, cosine(queryEmbedding, vec)));
        }
        scored.sort((a, b) -> Float.compare(b.score(), a.score()));

        // INFO, not debug: this is the line that tells us the feature is alive
        // and whether the budget is biting. Without it the only symptom of a
        // broken rerank is "search feels wrong", which is unfalsifiable.
        log.info("SPARSE_RERANK '{}': {} candidates, {} scored ({} embedded, {} cached), "
                + "{} left in BM25 order, returning {}",
            collection, candidates.size(), scored.size(), embedded, cached,
            unscored.size(), Math.min(topK, candidates.size()));

        var out = new ArrayList<SearchResult>();
        for (var s : scored) {
            if (out.size() >= topK) break;
            out.add(new SearchResult(s.r().id(), s.r().content(), s.r().source(),
                s.r().metadata(), s.score()));
        }
        for (var u : unscored) {              // budget ran out: keep them, unranked
            if (out.size() >= topK) break;
            out.add(u);
        }

        // LAZY EMBED WRITE-BACK — off the query's critical path.
        //
        // The caller is already waiting; persisting must not make them wait
        // longer. Handing this to a daemon thread means the query answers at the
        // same speed and the NEXT one over these chunks is free. Best-effort by
        // design: if it fails, the chunks are simply re-embedded another day.
        if (!freshlyEmbedded.isEmpty()) {
            var toPersist = Map.copyOf(freshlyEmbedded);
            var t = new Thread(() -> writeBackVectors(collection, toPersist),
                "vector-writeback-" + collection);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
        return out;
    }

    /**
     * Reduce a conversational query to its discriminating terms for the BM25 leg.
     *
     * <p>Deliberately a stopword filter and nothing cleverer: no stemming, no
     * synonym expansion, no model call. The semantic leg already handles meaning —
     * this only has to stop function words from dominating the lexical match.
     * Falls back to the original text if reduction would leave nothing (a query
     * that is ALL stopwords is better served by its own terms than by silence).
     */
    static String keywordsOf(String queryText) {
        return keywordsOf(queryText, null);
    }

    /**
     * As above, but ranked against the corpus that is about to be searched.
     *
     * @param reader the index being queried, or null when none is available
     */
    static String keywordsOf(String queryText, IndexReader reader) {
        if (queryText == null || queryText.isBlank()) return queryText;
        // Mutable copy: a rescued term's replacements inherit its protection
        // (the split halves of a person's word are still the person's word).
        var protectedTerms = new HashSet<>(protectedTermsOf(queryText));
        var plain = stripProtectionMarkers(queryText);
        var kept = new ArrayList<String>();
        var dedup = new HashSet<String>();
        for (var tok : plain.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}']+")) {
            if (tok.length() > 2 && !QUERY_STOPWORDS.contains(tok) && dedup.add(tok)) {
                kept.add(tok);
            }
        }
        if (kept.isEmpty()) return plain;
        kept = resolveAbsentTerms(kept, protectedTerms, reader);

        // LENGTH IS THE ENEMY OF BM25 HERE — and length is ALSO the wrong knife.
        //
        // A model-written query carries a paragraph of instructions. After
        // stopword removal that is still 20-40 content words, and the two that
        // discriminate are outvoted by thirty that match half the corpus.
        //
        // Two ranking rules were tried before this one and both lost, live:
        //
        //   by LENGTH — search vocabulary is systematically longer than names.
        //     Kept "mention", "explains", "structure"; dropped "glass", "kestan".
        //   by SUBJECT-vs-INSTRUCTION — better, and still not enough. On a
        //     69-word query (2026-08-08 20:01) it kept "dialogue content summary
        //     explanation reality library holdings particular conversation" and
        //     dropped velshara, vel, shara, glass, tide AND kestan. Those words are
        //     not in QUERY_INSTRUCTION_WORDS, so they counted as subject, and the
        //     length tiebreak finished the job. She then answered, honestly, out
        //     of a screenwriting textbook.
        //
        // Both were proxies for the thing actually wanted: RARITY. Measured on
        // the live 15.6M-document Study index, the terms that matter and the
        // terms that survived differ by two to six orders of magnitude —
        // velshara 1, shara 350, kestan 1,465 against conversation 553,578 and
        // particular 735,987. That is not a close call needing a clever
        // heuristic; it is a number the index already knows.
        //
        // So ask it. A term in 40 of 15M documents is the question; a term in
        // 3M is packaging. This is the same intuition BM25's own IDF encodes —
        // the cull now agrees with the scorer instead of fighting it.
        if (kept.size() > MAX_QUERY_TERMS) {
            var df = documentFrequencies(kept, reader);
            // THE PERSON'S WORDS DO NOT COMPETE. Rarity decides which of the
            // MODEL's words survive; the person's ride through untouched (except
            // a term the corpus provably cannot match — protecting that would
            // only waste a slot). Lazarus Long is why: "long", "live", "forever"
            // and "humanity" are each in millions of documents and the cull
            // discarded them all as common — but their CONJUNCTION is the
            // question, and they were the person's own words. Rare-wins is the
            // right rule for the model's padding, and no rule at all is the
            // right rule for what the person actually said.
            var protectedKept = new ArrayList<String>();
            var candidates = new ArrayList<String>();
            for (var t : kept) {
                if (protectedTerms.contains(t) && df.getOrDefault(t, 1) != 0) {
                    protectedKept.add(t);
                } else {
                    candidates.add(t);
                }
            }
            int slots = Math.max(0, MAX_QUERY_TERMS - protectedKept.size());
            var ranked = new ArrayList<>(candidates);
            ranked.sort((a, b) -> {
                // A term in NO document cannot match anything, so it can never
                // help — it may only consume a scarce slot. Last, not first:
                // otherwise a model's typo ("velhara") outranks the real word.
                boolean az = df.getOrDefault(a, -1) == 0;
                boolean bz = df.getOrDefault(b, -1) == 0;
                if (az != bz) return az ? 1 : -1;

                var fa = df.get(a);
                var fb = df.get(b);
                if (fa != null && fb != null && fa > 0 && fb > 0 && !fa.equals(fb)) {
                    return Integer.compare(fa, fb);            // rarest first
                }
                // No corpus, or a genuine tie: fall back to what we had.
                boolean ai = QUERY_INSTRUCTION_WORDS.contains(a);
                boolean bi = QUERY_INSTRUCTION_WORDS.contains(b);
                if (ai != bi) return ai ? 1 : -1;              // subject words first
                return Integer.compare(b.length(), a.length());
            });
            var keepSet = new HashSet<>(protectedKept);
            keepSet.addAll(ranked.subList(0, Math.min(slots, ranked.size())));
            var trimmed = new ArrayList<String>(keepSet.size());
            for (var t : kept) {
                if (keepSet.contains(t) && !trimmed.contains(t)) trimmed.add(t);
            }
            log.debug("Query reduced from {} to {} terms ({} protected{})",
                kept.size(), trimmed.size(), protectedKept.size(),
                df.isEmpty() ? "; no corpus — ranked by subject/length" : "; rest by rarity");
            kept = trimmed;
        }
        return String.join(" ", kept);
    }

    /**
     * Add the person's own words to a query the model rewrote.
     *
     * <p><b>A paraphrase may add, never replace.</b> Live 2026-08-08 20:50: the
     * person asked what the librarian told Kestan about <b>velsharas</b> in <b>glass
     * tide</b>; the companion searched for <b>"glass tine"</b> and <b>"name
     * hub"</b>, retrieved the wrong chapter, and then reported "a character named
     * Hub" — her own garbled query term coming back as an invented character. No
     * amount of ranking can recover a word the query never contained, and the
     * words most likely to be mangled are exactly the proper nouns that carry the
     * question.</p>
     *
     * <p>Appending is safe because of what happens next: the person's real terms
     * are, almost by definition, rarer than the model's padding, so the rarity
     * cull in {@link #keywordsOf} keeps them and drops the packaging. The two
     * changes are one mechanism — restore the words, then let the corpus decide
     * which ones matter.</p>
     *
     * @param modelQuery    what the companion decided to search for
     * @param personRequest what the person actually said
     * @return the model's query plus any content words it dropped
     */
    /** Marks the span of a query that came from the PERSON, not the model. */
    static final char PROTECT_OPEN = '⟦';
    static final char PROTECT_CLOSE = '⟧';

    public static String withPersonTerms(String modelQuery, String personRequest) {
        if (personRequest == null || personRequest.isBlank()) return modelQuery;
        if (modelQuery == null || modelQuery.isBlank()) return personRequest;

        // PROTECT the person's words, don't merely append them.
        //
        // The first version appended only the words her rewrite had dropped,
        // and the rarity cull then treated everything alike. Live 2026-08-09
        // 15:49: asked about Lazarus Long living forever, her 50-word rewrite
        // already contained "lazarus long humanity live forever" — so nothing
        // was appended — and the cull then discarded "long", "live", "forever"
        // and "humanity" as too COMMON, keeping "lazarus" plus her instruction
        // vocabulary. The search missed the one scene the person asked about,
        // while the same five words, unculled, rank it first.
        //
        // Velshara taught the cull that rare words win; this is the counter-
        // lesson: some questions are identified by a CONJUNCTION of common
        // words, and it is not the cull's place to spend the person's words to
        // make room for the model's. So the person's content words travel in a
        // marked span, and the cull keeps everything inside it. The markers are
        // a string protocol, not grammar — chosen because this query crosses
        // four plain-String hops where no side channel exists, and stripped
        // before anything embeds or parses the text.
        var person = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (var raw : personRequest.split("[^\\p{L}\\p{N}']+")) {
            var t = raw.toLowerCase(Locale.ROOT);
            if (t.length() <= 2 || QUERY_STOPWORDS.contains(t)) continue;
            if (seen.add(t)) person.add(t);
        }
        if (person.isEmpty()) return modelQuery;
        var cleanModel = stripProtectionMarkers(modelQuery);
        log.debug("Protecting {} person term(s) through the cull: {}", person.size(), person);
        return cleanModel + " " + PROTECT_OPEN + String.join(" ", person) + PROTECT_CLOSE;
    }

    /** The query with any protection span flattened to plain words. */
    public static String stripProtectionMarkers(String query) {
        if (query == null) return null;
        return query.replace(String.valueOf(PROTECT_OPEN), " ")
                    .replace(String.valueOf(PROTECT_CLOSE), " ");
    }

    /** Public face of {@link #protectedTermsOf} for co-occurrence ranking. */
    public static Set<String> protectedQueryTerms(String query) {
        return protectedTermsOf(query);
    }

    /** The protected words, lowercased, or empty when the query carries none. */
    static Set<String> protectedTermsOf(String query) {
        if (query == null) return Set.of();
        int open = query.indexOf(PROTECT_OPEN);
        int close = query.indexOf(PROTECT_CLOSE);
        if (open < 0 || close <= open) return Set.of();
        var out = new HashSet<String>();
        for (var t : query.substring(open + 1, close)
                .toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}']+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * Replace a term that appears in no document with a form that does.
     *
     * <p>Only the plural, and only when the corpus confirms it. The person wrote
     * "velsharas"; the index holds "velshara" in exactly ONE of 15.6 million
     * documents — the rarest term in the entire corpus, and the single best
     * discriminator available for that question. Verbatim, it matches nothing.</p>
     *
     * <p>Deliberately not a stemmer. A stemmer decides from rules and is wrong
     * about names; this asks the index and only acts on an answer. If the
     * singular is also absent, nothing changes and the dead term is culled last
     * by the rarity ranking anyway.</p>
     */
    private static ArrayList<String> resolveAbsentTerms(ArrayList<String> terms,
                                                        Set<String> protectedTerms,
                                                        IndexReader reader) {
        if (reader == null || reader.maxDoc() == 0) return terms;
        var out = new ArrayList<String>(terms.size());
        for (var t : terms) {
            var rescued = rescueAbsent(t, reader);
            if (rescued.size() != 1 || !rescued.get(0).equals(t)) {
                // Replacements ride under the original's protection — the
                // person said this word, however the index spells it.
                if (protectedTerms.contains(t)) protectedTerms.addAll(rescued);
            }
            for (var r : rescued) {
                if (!out.contains(r)) out.add(r);
            }
        }
        return out;
    }

    /**
     * A term the index has never seen, rescued by asking the index — first the
     * singular, then a compound split.
     *
     * <p>Books hyphenate what readers type solid: the text's "vel-shara"
     * indexes as two tokens, so a person asking about "velsharas" contributes a
     * term found in no document — the question's rarest concept exerting zero
     * pull. Live 2026-08-10, first question to the fresh corpus: that question
     * fell to a decoy passage elected by its common words; rephrased with the
     * hyphen, she answered with the book's own sentences. The summariser had
     * been taught odd spellings in dev39; retrieval never was.</p>
     *
     * <p>Same philosophy as the singular rescue: never guess from rules, act
     * only when the index confirms. The confirmation a split needs is
     * ADJACENCY, not the halves' own frequency: the split is only right if it
     * reconstructs a pair the corpus actually prints side by side. Live
     * 2026-08-10, an hour after the first version shipped: picking the split
     * by the halves' document counts chose "nams hubs" over "vel shara" —
     * both halves of the wrong pair are common enough — and against a small
     * collection it invented "matte red" out of "mattered". A phrase count
     * answers both: "vel shara" stands adjacent in hundreds of documents,
     * "nams hubs" and "matte red" in none that matter, and where no split is
     * ever adjacent there is no split. The rescues remain a UNION, not a
     * chain: on the live corpus "velshara" exists in exactly ONE document of
     * 13.7M, so a singular-then-stop rescue would anchor the question to that
     * lone fluke and never reach the real passages. A term with no rescue at
     * all returns unchanged and the rarity cull ranks it last, as before.</p>
     */
    private static List<String> rescueAbsent(String term, IndexReader reader) {
        try {
            if (reader.docFreq(new Term(FIELD_CONTENT, term)) > 0) return List.of(term);
            var rescued = new ArrayList<String>();
            for (var suffix : new String[]{"es", "s"}) {
                if (!term.endsWith(suffix) || term.length() - suffix.length() < 3) continue;
                var stem = term.substring(0, term.length() - suffix.length());
                if (reader.docFreq(new Term(FIELD_CONTENT, stem)) > 0) {
                    log.debug("Query term '{}' is in no document; using '{}'", term, stem);
                    rescued.add(stem);
                    break;
                }
            }
            if (term.length() >= 6) {
                var searcher = new IndexSearcher(reader);
                String bestHead = null;
                String bestTail = null;
                long bestAdjacent = 0;
                for (int i = 3; i <= term.length() - 3; i++) {
                    var head = term.substring(0, i);
                    if (reader.docFreq(new Term(FIELD_CONTENT, head)) == 0) continue;
                    var tailRaw = term.substring(i);
                    var tails = new ArrayList<String>(2);
                    tails.add(tailRaw);
                    for (var suffix : new String[]{"es", "s"}) {
                        if (tailRaw.endsWith(suffix)
                                && tailRaw.length() - suffix.length() >= 3) {
                            tails.add(tailRaw.substring(0, tailRaw.length() - suffix.length()));
                        }
                    }
                    for (var tail : tails) {
                        long adjacent = searcher.count(
                            new PhraseQuery(FIELD_CONTENT, head, tail));
                        if (adjacent > bestAdjacent) {
                            bestAdjacent = adjacent;
                            bestHead = head;
                            bestTail = tail;
                        }
                    }
                }
                if (bestHead != null) {
                    log.info("Query term '{}' is in no document; split to '{} {}' "
                        + "(adjacent in {} documents)", term, bestHead, bestTail, bestAdjacent);
                    rescued.add(bestHead);
                    rescued.add(bestTail);
                }
            }
            if (!rescued.isEmpty()) return rescued;
        } catch (IOException e) {
            return List.of(term);
        }
        return List.of(term);
    }

    /**
     * The chunks either side of this one, in reading order, including itself.
     *
     * <p>Retrieval finds the chunk whose words match the question. Prose does not
     * respect that boundary: the passage that answers is often the one before or
     * after the one that carries the names. Following the thread is what a person
     * does with a book without thinking, and what search cannot do at all.</p>
     *
     * @param radius how many chunks either side; 1 means the immediate neighbours
     * @return the run of chunks ordered by part, or just this chunk when the
     *         document is unpaginated or has not been backfilled yet
     */
    public List<SearchResult> chunkWithNeighbours(String collection, String docId, int radius) {
        if (collection == null || docId == null || radius < 0) return List.of();
        try {
            var sm = getSearcherManager(collection);
            var searcher = sm.acquire();
            try {
                var self = searcher.search(new TermQuery(new Term(FIELD_ID, docId)), 1);
                if (self.scoreDocs.length == 0) return List.of();
                var selfDoc = searcher.storedFields().document(self.scoreDocs[0].doc);
                var group = selfDoc.get(FIELD_DOC_GROUP);
                var partStr = selfDoc.get(FIELD_PART);
                int part = partStr == null ? -1 : Integer.parseInt(partStr);
                if (group == null || part < 0) {
                    // Unpaginated, or predating the backfill. The chunk on its own
                    // is the honest answer — better than silently returning none.
                    // Logged with the raw values because this branch ate the first
                    // live adjacency read without a trace.
                    log.info("No neighbours for {} — doc_group='{}', part='{}'",
                        docId, group, partStr);
                    return List.of(toResult(selfDoc, 1.0f));
                }

                var q = new BooleanQuery.Builder()
                    .add(new TermQuery(new Term(FIELD_DOC_GROUP, group)), BooleanClause.Occur.MUST)
                    .add(IntPoint.newRangeQuery(FIELD_PART, part - radius, part + radius),
                        BooleanClause.Occur.MUST)
                    .build();
                var hits = searcher.search(q, 2 * radius + 1);

                var run = new ArrayList<Document>();
                for (var sd : hits.scoreDocs) run.add(searcher.storedFields().document(sd.doc));
                run.sort(Comparator.comparingInt(
                    d -> d.get(FIELD_PART) == null ? 0 : Integer.parseInt(d.get(FIELD_PART))));

                var out = new ArrayList<SearchResult>(run.size());
                for (var d : run) out.add(toResult(d, 1.0f));
                return out;
            } finally {
                sm.release(searcher);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read neighbours of {}: {}", docId, e.toString());
            return List.of();
        }
    }

    private SearchResult toResult(Document d, float score) {
        var meta = new LinkedHashMap<String, Object>();
        for (var f : d.getFields()) {
            if (FIELD_CONTENT_STORED.equals(f.name())) continue;
            if (f.stringValue() != null) meta.putIfAbsent(f.name(), f.stringValue());
        }
        return new SearchResult(d.get(FIELD_ID), d.get(FIELD_CONTENT_STORED),
            d.get(FIELD_ID), meta, score);
    }

    /**
     * Give every existing chunk its document group and part number.
     *
     * <p>13.7M documents were written before those fields existed. Resumable by
     * construction: the pass asks for documents that have no {@code doc_group}
     * yet, and every document it touches gets one — including unpaginated ones,
     * which get their own title and part -1. A pass that stops halfway simply has
     * less to do next time, and a finished pass finds nothing.</p>
     *
     * <p>Unlike the owner migration this <b>keeps embeddings</b>: it reads the
     * stored vector back and re-attaches it. That one dropped them and needed a
     * separate re-embed afterwards, and there is no reason to repeat it.</p>
     *
     * @return how many documents were given their position
     */
    /**
     * Test hook: write a Study document the way they were written before chunk
     * order existed — no {@code doc_group}, no {@code part}.
     *
     * <p>Needed because the interesting failure is not in placing a document, it
     * is in FINDING the unplaced ones once some are placed. A fixture built with
     * the current writer has nothing for the backfill to do and cannot exhibit
     * it.</p>
     */
    void insertLegacyStudyItemWithoutOrder(String id, String title, String content) {
        var doc = newDocument(id, content, null);
        doc.add(new StringField("user_did", "did:key:zOwner", Field.Store.YES));
        doc.add(new StringField("item_type", "document", Field.Store.YES));
        doc.add(new StoredField("title", safe(title)));
        doc.add(new StringField("collection", "books", Field.Store.YES));
        doc.add(new StoredField("timestamp", System.currentTimeMillis()));
        doc.add(new StoredField("version", 1));
        doc.add(new StoredField("deleted", 0));
        upsert(SearchCollections.STUDY, id, doc);
    }

    /** A backfill that did not reach the end, carrying how far it got. */
    public static class BackfillInterrupted extends RuntimeException {
        public final long placed;
        BackfillInterrupted(long placed, Throwable cause) {
            super("backfill stopped after " + placed + " documents", cause);
            this.placed = placed;
        }
    }

    public long backfillChunkOrder(String collection, int batchSize,
                                   LongConsumer progress) {
        long total = 0;
        try {
            var sm = getSearcherManager(collection);
            while (true) {
                var searcher = sm.acquire();
                var rebuilt = new ArrayList<Document>();
                var ids = new ArrayList<String>();
                try {
                    // "Which documents have no position yet?" asked through the
                    // POINT field, not FieldExistsQuery.
                    //
                    // FieldExistsQuery needs doc values, norms or vectors, and a
                    // StringField has none of the three — so it does not return
                    // empty, it THROWS. And it only starts throwing once the
                    // field exists at all, which means the first pass over a
                    // virgin index ran perfectly and every pass after it died on
                    // the first query. One batch of 500 out of 15.6M documents,
                    // reported as completion.
                    //
                    // A range over every possible int matches exactly the
                    // documents that have the point, so MUST_NOT gives the ones
                    // that do not. No doc values, no schema change, and it works
                    // on the rows already written.
                    var missing = new BooleanQuery.Builder()
                        .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
                        .add(IntPoint.newRangeQuery(FIELD_PART,
                            Integer.MIN_VALUE, Integer.MAX_VALUE), BooleanClause.Occur.MUST_NOT)
                        .build();
                    var hits = searcher.search(missing, batchSize);
                    if (hits.scoreDocs.length == 0) break;

                    var stored = searcher.storedFields();
                    for (var sd : hits.scoreDocs) {
                        var old = stored.document(sd.doc);
                        var id = old.get(FIELD_ID);
                        if (id == null) continue;

                        // Same round-trip rule as everywhere else: rebuild through
                        // the construction path, never copy the reader's view.
                        var doc = newDocument(id, old.get(FIELD_CONTENT_STORED),
                            readVector(searcher.getIndexReader(), sd.doc));
                        copyStored(old, doc, "user_did", true);
                        copyStored(old, doc, "item_type", true);
                        copyStored(old, doc, "collection", true);
                        copyStored(old, doc, "last_modified_by", true);
                        copyStored(old, doc, "title", false);
                        copyStored(old, doc, "vector_clock", false);
                        addChunkOrder(doc, old.get("title"));
                        var ts = old.get("timestamp");
                        doc.add(new StoredField("timestamp", ts == null ? 0L : Long.parseLong(ts)));
                        var ver = old.get("version");
                        doc.add(new StoredField("version", ver == null ? 1 : Integer.parseInt(ver)));
                        var del = old.get("deleted");
                        doc.add(new StoredField("deleted", del == null ? 0 : Integer.parseInt(del)));

                        rebuilt.add(doc);
                        ids.add(id);
                    }
                } finally {
                    sm.release(searcher);
                }
                if (rebuilt.isEmpty()) break;

                var writer = getWriter(collection);
                for (int i = 0; i < rebuilt.size(); i++) {
                    writer.updateDocument(new Term(FIELD_ID, ids.get(i)), rebuilt.get(i));
                }
                writer.commit();
                refreshSearcher(collection);

                total += rebuilt.size();
                if (progress != null) progress.accept(total);
            }
            if (total > 0) {
                log.info("Chunk-order backfill complete for '{}': {} documents can now be read "
                    + "in sequence", collection, total);
            }
            return total;
        } catch (IOException | RuntimeException e) {
            // Distinguishable from completion by the CALLER, not just in this log.
            // A partial pass that returns a plain count reads as success one level
            // up — which is exactly how "500 of 15,585,914" got reported as
            // finished. Re-thrown so the wrapper has to say which happened.
            log.warn("Chunk-order backfill stopped after {} documents: {} — re-run to resume",
                total, e.toString());
            throw new BackfillInterrupted(total, e);
        }
    }

    /**
     * Test hook: run something against a live reader for a collection.
     *
     * <p>Exists so the term-rarity ranking can be asserted against real index
     * statistics rather than a mock. The searcher is released either way, which
     * is why this takes a function instead of handing the reader out.</p>
     */
    <T> T withReader(String collection, Function<IndexReader, T> fn)
            throws IOException {
        var sm = getSearcherManager(collection);
        var searcher = sm.acquire();
        try {
            return fn.apply(searcher.getIndexReader());
        } finally {
            sm.release(searcher);
        }
    }

    /**
     * How many documents contain each term, or an empty map when the corpus
     * cannot answer.
     *
     * <p>Empty is returned rather than a map of zeroes on purpose. Zero means
     * "provably cannot match" and is acted on; absent means "unknown" and must
     * fall back to the older ranking. Conflating them would make an analyzer
     * mismatch — where every lookup misses — look like a query of nothing but
     * dead terms, and silently invert the cull.</p>
     */
    private static Map<String, Integer> documentFrequencies(List<String> terms,
                                                            IndexReader reader) {
        if (reader == null || reader.maxDoc() == 0) return Map.of();
        var out = new LinkedHashMap<String, Integer>();
        int found = 0;
        for (var t : terms) {
            try {
                int df = reader.docFreq(new Term(FIELD_CONTENT, t));
                out.put(t, df);
                if (df > 0) found++;
            } catch (IOException e) {
                return Map.of();       // unreadable corpus is not a corpus of zeroes
            }
        }
        // Every single term missing means our tokenisation and the index's
        // analyzer disagree, not that the query is meaningless. Don't act on it.
        return found == 0 ? Map.of() : out;
    }

    /**
     * How many terms reach BM25. Twelve is generous for a real question and tight
     * enough that an instruction paragraph cannot drown the two words that matter.
     */
    private static final int MAX_QUERY_TERMS = 12;

    /**
     * Words about the ACT of looking rather than the thing looked for.
     *
     * <p>Demoted when the query is too long, never removed — on a short query
     * they are harmless, and any of them can legitimately be the subject.</p>
     */
    private static final Set<String> QUERY_INSTRUCTION_WORDS = Set.of(
        "mention", "mentions", "mentioned", "explain", "explains", "explained",
        "describe", "describes", "description", "scene", "excerpt", "quote",
        "reference", "references", "look", "looking", "find", "finding", "search",
        "searching", "tell", "telling", "show", "showing", "check", "checking",
        "means", "meaning", "moment", "role", "structure", "surrounding", "pages",
        "page", "information", "info", "details", "detail", "provide", "provides",
        "provided", "regarding", "context", "section", "anywhere", "actual",
        "specifically", "significant", "there's", "whether", "including");

    private static final Set<String> QUERY_STOPWORDS = Set.of(
        "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had",
        "her", "was", "one", "our", "out", "day", "get", "has", "him", "his", "how",
        "man", "new", "now", "old", "see", "two", "way", "who", "boy", "did", "its",
        "let", "put", "say", "she", "too", "use", "that", "with", "have", "this",
        "will", "your", "from", "they", "know", "want", "been", "good", "much",
        "some", "them", "then", "than", "into", "just", "like", "also", "back",
        "about", "there", "their", "what", "when", "where", "which", "would",
        "could", "should", "these", "those", "were", "does", "doing", "very",
        "really", "wonder", "wondering", "maybe", "perhaps", "something",
        "anything", "things", "thing", "sort", "kind");

    /** Cosine on L2-normalized vectors = dot product; 0 when either side is unusable. */
    private static float cosine(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.size() != b.size()) return 0f;
        float dot = 0f;
        for (int i = 0; i < a.size(); i++) dot += a.get(i) * b.get(i);
        return Math.max(0f, dot);
    }

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
        // A closed store must not resurrect a writer. close() clears the map,
        // so without this an insert still in flight during shutdown would open
        // a BRAND NEW writer (and take the write lock) behind the shutdown —
        // found 2026-08-25 while chasing per-chunk WARN spam on a big publish.
        if (closed) {
            throw new AlreadyClosedException("WyrdLuceneStore is closed");
        }
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
            if (clause.contains("!=")) {
                var parts = clause.split("!=", 2);
                String field = parts[0].trim();
                String value = parts[1].trim().replaceAll("^\"|\"$", "");
                builder.add(new TermQuery(new Term(field, value)), BooleanClause.Occur.MUST_NOT);
            } else if (clause.contains("==")) {
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
