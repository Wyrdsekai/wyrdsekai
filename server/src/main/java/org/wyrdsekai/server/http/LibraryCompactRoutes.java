package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.search.EmbeddingModel;
import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * #1027/#1034 — REST surface that backs the
 * {@code wyrd library compact <action>} CLI subcommand. The
 * {@code compact-library-index} recipe's Python wrapper
 * ({@code scripts/library/compact_collection.py}) shells to these
 * endpoints; each returns a single JSON object whose keys are
 * what the recipe's gates read.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code POST /api/library/compact/snapshot?collection=&lt;name&gt;}
 *       — returns {@code {"collection": ..., "chunk_count": N}}.</li>
 *   <li>{@code POST /api/library/compact/merge?collection=&lt;name&gt;}
 *       — runs {@code forceMerge(1)}, returns
 *       {@code {"collection": ..., "merge_succeeded": bool}}.</li>
 *   <li>{@code POST /api/library/compact/prune?collection=&lt;name&gt;}
 *       — OSS v0.1 no-op (no expiry mechanism exists yet); returns
 *       {@code {"collection": ..., "pruned_chunks": 0,
 *       "note": "prune-by-expiry not yet implemented (v0.1)"}}.</li>
 *   <li>{@code POST /api/library/compact/reembed?collection=&lt;name&gt;}
 *       — OSS v0.1 no-op (use {@code wyrd embed-migrate} for the bulk
 *       re-embed flow); returns {@code {"reembedded_chunks": 0, ...}}.</li>
 *   <li>{@code POST /api/library/compact/probe?collection=&lt;name&gt;}
 *       — body: {@code {"probes": ["query1", "query2", ...]}}; returns
 *       {@code {"probes_run": N, "results_by_probe":
 *       [{"prompt": "...", "top3_ids": [...]}]}}.</li>
 * </ul>
 *
 * <p>Steward-only: the recipe scheduler dispatches from inside the
 * running zone, so the calling principal is the zone itself. For now,
 * a {@code X-Wyrdsekai-Admin-Token} header check guards external callers
 * (matches the existing recipe-dispatch pattern via
 * {@code WYRDSEKAI_ADMIN_TOKEN}). When the header is missing or wrong,
 * returns 403.</p>
 */
public final class LibraryCompactRoutes {

    private static final Logger log = LoggerFactory.getLogger(LibraryCompactRoutes.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_HEADER = "X-Wyrdsekai-Admin-Token";

    private final WyrdLuceneStore store;

    public LibraryCompactRoutes(WyrdLuceneStore store) {
        this.store = store;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.post("/api/library/compact/snapshot", this::handleSnapshot);
        app.post("/api/library/compact/merge",    this::handleMerge);
        app.post("/api/library/compact/prune",    this::handlePrune);
        app.post("/api/library/compact/reembed",  this::handleReembed);
        app.post("/api/library/compact/probe",    this::handleProbe);
        // Library-freshness seam (research-pack-freshness recipe). enumerate
        // reads provenance for source-liveness checks; prune-ids deletes dead
        // chunks. Side-channel — neither rewrites a document, so dense vectors
        // are never lost (cf. the updateKnowledgeTags vector-loss trap).
        app.post("/api/library/freshness/enumerate", this::handleFreshnessEnumerate);
        app.post("/api/library/freshness/prune-ids", this::handleFreshnessPruneIds);
    }

    // ── handlers ──────────────────────────────────────────────────────────

    private void handleSnapshot(Context ctx) {
        if (!authorised(ctx)) return;
        String collection = collectionParam(ctx);
        if (collection == null) return;
        long n = store.countCollection(collection);
        ctx.json(Map.of("collection", collection, "chunk_count", n));
    }

    private void handleMerge(Context ctx) {
        if (!authorised(ctx)) return;
        String collection = collectionParam(ctx);
        if (collection == null) return;
        boolean ok = store.forceMergeCollection(collection);
        ctx.json(Map.of("collection", collection, "merge_succeeded", ok));
    }

    private void handlePrune(Context ctx) {
        if (!authorised(ctx)) return;
        String collection = collectionParam(ctx);
        if (collection == null) return;
        // #1038 — real prune by rolling TTL. Default TTL
        // (365d) is conservative; steward overrides via the
        // {@code ttl_days} query param. Master kill-switch via
        // {@code library.compact.prune.enabled} for households that
        // want compact recipes to run snapshot+merge only.
        var cfg = WyrdConfig.get();
        boolean enabled = cfg == null || cfg.libraryCompactPruneEnabled();
        int ttlDays = cfg == null ? 365 : cfg.libraryCompactPruneDefaultTtlDays();
        String override = ctx.queryParam("ttl_days");
        if (override != null && !override.isBlank()) {
            try { ttlDays = Math.max(1, Integer.parseInt(override.trim())); }
            catch (NumberFormatException ignored) {}
        }
        var resp = new LinkedHashMap<String, Object>();
        resp.put("collection", collection);
        resp.put("ttl_days", ttlDays);
        if (!enabled) {
            resp.put("pruned_chunks", 0);
            resp.put("note", "prune disabled via library.compact.prune.enabled=false");
            ctx.json(resp);
            return;
        }
        // Only the knowledge collection carries inserted_ms — other
        // collections (study, rooms, fragments) have different lifecycle
        // rules and aren't prune-eligible from this surface.
        if (!SearchCollections.KNOWLEDGE.equals(collection)) {
            resp.put("pruned_chunks", 0);
            resp.put("note", "prune only applies to 'knowledge' collection");
            ctx.json(resp);
            return;
        }
        long cutoffMs = System.currentTimeMillis()
            - (long) ttlDays * 24L * 3600L * 1000L;
        long pruned = store.pruneKnowledgeOlderThan(cutoffMs);
        resp.put("pruned_chunks", pruned);
        ctx.json(resp);
    }

    private void handleReembed(Context ctx) {
        if (!authorised(ctx)) return;
        String collection = collectionParam(ctx);
        if (collection == null) return;
        // #1039 — count chunks whose stored
        // {@code embedding_model} field doesn't match the active model.
        // The actual re-embed pass lives in {@code wyrd embed-migrate}
        // (its own plan/run/status pattern, safer for multi-GB runs);
        // this endpoint surfaces the stale count so the welfare gate
        // can verify the index is mostly in-sync before allowing the
        // compact recipe to proceed with the cheaper steps.
        var cfg = WyrdConfig.get();
        boolean enabled = cfg == null || cfg.libraryCompactReembedEnabled();
        String targetVersion = cfg == null ? "auto"
            : cfg.libraryCompactReembedTargetModel();
        if ("auto".equalsIgnoreCase(targetVersion)) {
            targetVersion = EmbeddingModel
                .PARAPHRASE_L12.version();
        }
        var resp = new LinkedHashMap<String, Object>();
        resp.put("collection", collection);
        resp.put("target_model", targetVersion);
        if (!enabled) {
            resp.put("reembedded_chunks", 0);
            resp.put("stale_chunks", 0);
            resp.put("note", "reembed disabled via library.compact.reembed.enabled=false");
            ctx.json(resp);
            return;
        }
        if (!SearchCollections.KNOWLEDGE.equals(collection)) {
            resp.put("reembedded_chunks", 0);
            resp.put("stale_chunks", 0);
            resp.put("note", "reembed-by-version only applies to 'knowledge' collection");
            ctx.json(resp);
            return;
        }
        long stale = store.countStaleEmbeddingChunks(targetVersion);
        resp.put("stale_chunks", stale);
        // v0.1: surface stale count, defer actual reembed to `wyrd
        // embed-migrate`. A future patch can wire an in-route reembed
        // loop here when the bundled embedder is callable from inside
        // Javalin without blocking the request thread for minutes.
        resp.put("reembedded_chunks", 0);
        if (stale > 0) {
            resp.put("note", "run 'wyrd embed-migrate run' to reembed "
                + stale + " stale chunks");
        }
        ctx.json(resp);
    }

    private void handleProbe(Context ctx) {
        if (!authorised(ctx)) return;
        String collection = collectionParam(ctx);
        if (collection == null) return;

        // Body: {"probes": ["query1", ...]} OR a path arg
        // (?probes_file=/tmp/foo.jsonl) for probe sets too large for
        // a query param. JSONL probe rows: {"prompt": "..."}.
        var prompts = parsePrompts(ctx);
        if (prompts == null) return;

        var results = new ArrayList<Map<String, Object>>();
        for (var prompt : prompts) {
            var hits = searchTextOnly(collection, prompt, 3);
            results.add(Map.of(
                "prompt", prompt,
                "top3_ids", hits));
        }
        ctx.json(Map.of(
            "collection", collection,
            "probes_run", prompts.size(),
            "results_by_probe", results));
    }

    /**
     * Enumerate knowledge-pack chunk provenance for freshness auditing. Query
     * {@code limit} (default 500, capped 50000). Returns {@code count} + an
     * {@code entries} list of provenance maps (id/pack/title/source/...).
     */
    private void handleFreshnessEnumerate(Context ctx) {
        if (!authorised(ctx)) return;
        int limit = 500;
        String lim = ctx.queryParam("limit");
        if (lim != null && !lim.isBlank()) {
            try { limit = Math.max(1, Math.min(50_000, Integer.parseInt(lim.trim()))); }
            catch (NumberFormatException ignored) {}
        }
        var entries = store.enumerateKnowledgeProvenance(limit);
        ctx.json(Map.of("count", entries.size(), "entries", entries));
    }

    /**
     * Prune knowledge chunks by id (dead/unreachable source removal). Body:
     * {@code {"ids": ["chunk-1", ...]}}. Returns {@code requested} + {@code pruned}.
     */
    private void handleFreshnessPruneIds(Context ctx) {
        if (!authorised(ctx)) return;
        var ids = new ArrayList<String>();
        try {
            String body = ctx.body();
            if (body != null && !body.isBlank()) {
                JsonNode arr = JSON.readTree(body).get("ids");
                if (arr != null && arr.isArray()) {
                    for (var n : arr) if (n.isTextual()) ids.add(n.asText());
                }
            }
        } catch (IOException e) {
            ctx.status(400).json(Map.of("error", "bad_body: " + e.getMessage()));
            return;
        }
        long pruned = store.pruneKnowledgeByIds(ids);
        ctx.json(Map.of("requested", ids.size(), "pruned", pruned));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Steward-auth gate: env-var-driven shared token (matches existing
     *  pattern in /api/test/* and recipe dispatch routes). */
    private boolean authorised(Context ctx) {
        String expected = System.getenv("WYRDSEKAI_ADMIN_TOKEN");
        if (expected == null || expected.isBlank()) {
            // No token configured → accept loopback only (recipe scheduler
            // runs in-process so this is the typical path).
            String remote = ctx.ip();
            boolean local = "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote)
                || "::1".equals(remote);
            if (!local) {
                ctx.status(403).json(Map.of("error", "admin_token_required"));
                return false;
            }
            return true;
        }
        String got = ctx.header(ADMIN_HEADER);
        if (!expected.equals(got)) {
            ctx.status(403).json(Map.of("error", "invalid_admin_token"));
            return false;
        }
        return true;
    }

    private String collectionParam(Context ctx) {
        String name = ctx.queryParam("collection");
        if (name == null || name.isBlank()) {
            ctx.status(400).json(Map.of("error", "collection parameter required"));
            return null;
        }
        // Allow only known collection names — guards against
        // path-traversal-style misuse against the underlying directory.
        boolean known = false;
        for (var c : SearchCollections.ALL) {
            if (c.equals(name)) { known = true; break; }
        }
        if (!known) {
            ctx.status(400).json(Map.of("error", "unknown collection: " + name));
            return null;
        }
        return name;
    }

    private List<String> parsePrompts(Context ctx) {
        // Two sources, in priority order:
        //   1) body JSON: {"probes": ["q1", ...]}
        //   2) probes_file query param: path to a JSONL file on disk
        //      (recipe wrapper writes a /tmp/<collection>-probe-<label>.json)
        try {
            String body = ctx.body();
            if (body != null && !body.isBlank()) {
                JsonNode node = JSON.readTree(body);
                if (node.has("probes") && node.get("probes").isArray()) {
                    var out = new ArrayList<String>();
                    node.get("probes").forEach(p -> out.add(p.asText()));
                    return out;
                }
            }
        } catch (IOException e) {
            log.warn("probe body parse failed: {}", e.getMessage());
        }
        String probesFile = ctx.queryParam("probes_file");
        if (probesFile != null && !probesFile.isBlank()) {
            try {
                var out = new ArrayList<String>();
                for (var line : Files.readAllLines(Path.of(probesFile), StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    try {
                        JsonNode row = JSON.readTree(t);
                        if (row.has("prompt")) out.add(row.get("prompt").asText());
                    } catch (IOException ignored) {
                        // Non-JSON line — treat the whole line as a prompt.
                        out.add(t);
                    }
                }
                return out;
            } catch (IOException e) {
                ctx.status(400).json(Map.of("error", "probes_file unreadable: " + e.getMessage()));
                return null;
            }
        }
        // No probes provided → run zero probes (recipe contract: probes_run=0).
        return List.of();
    }

    private List<String> searchTextOnly(String collection, String query, int topK) {
        // Each collection has its own text-search method on WyrdLuceneStore.
        // Use the closest available match by collection name. For OSS v0.1,
        // only KNOWLEDGE and ROOM_CONTENT are exposed via text-only paths
        // that don't require an embedding model — those are also the only
        // collections the recipe is currently scheduled to compact.
        var out = new ArrayList<String>();
        try {
            var results = switch (collection) {
                case SearchCollections.KNOWLEDGE -> store.searchKnowledgeText(query, topK);
                case SearchCollections.ROOM_CONTENT -> store.searchRooms(query, topK);
                default -> List.<WyrdLuceneStore.SearchResult>of();
            };
            for (var r : results) {
                out.add(r.id());
            }
        } catch (Exception e) {
            log.warn("probe search failed for {} q={}: {}", collection, query, e.getMessage());
        }
        return out;
    }
}
