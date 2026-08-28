package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.home.HomeClients;
import org.wyrdsekai.core.home.ActionGrants;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.library.ArrivalTable;
import org.wyrdsekai.core.library.KnowledgePackIndexer;
import org.wyrdsekai.core.library.KnowledgePackRegistry;
import org.wyrdsekai.core.library.LibraryServices;
import org.wyrdsekai.core.library.PackIngester;
import org.wyrdsekai.core.library.ProposedPack;
import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP endpoints for the knowledge library subsystem.
 *
 *   GET  /api/library/search?q={query}&limit={limit}  — search knowledge base
 *   GET  /api/library/packs                            — list installed packs with chunk counts
 *   GET  /api/library/packs/{name}                     — info about a specific pack
 *   GET  /api/library/status                           — knowledge base statistics
 *   POST /api/library/install                          — install a knowledge pack (stub)
 *   DELETE /api/library/packs/{name}                   — remove a knowledge pack
 *
 * Steward proposal endpoints ( — REST parity with
 * the Study bookshelf / Library card catalog):
 *   GET  /api/library/proposals?status={pending|all}   — agent acquisition proposals
 *   POST /api/library/prune-sidecars                   — drop metadata.opf &c from a shelf
 *   POST /api/library/proposals/{id}/approve           — approve (id or unique prefix); ingest runs async
 *   POST /api/library/proposals/{id}/reject            — reject with optional {"reason": "..."}
 *   GET  /api/library/misses                           — repeated library-search misses
 */
public final class LibraryKnowledgeRoutes {

    private static final Logger log = LoggerFactory.getLogger(LibraryKnowledgeRoutes.class);

    private final WyrdLuceneStore store;
    private final KnowledgePackIndexer indexer;
    private final Path packsDir;

    public LibraryKnowledgeRoutes(WyrdLuceneStore store, KnowledgePackIndexer indexer, Path packsDir) {
        this.store = store;
        this.indexer = indexer;
        this.packsDir = packsDir;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/library/search", this::handleSearch);
        app.get("/api/library/packs", this::handleListPacks);
        app.get("/api/library/packs/{name}", this::handlePackInfo);
        app.get("/api/library/status", this::handleStatus);
        app.get("/api/library/available", this::handleAvailable);
        app.post("/api/library/install", this::handleInstall);
        app.post("/api/library/share-collection", this::handleShareCollection);
        app.post("/api/library/prune-sidecars", this::handlePruneSidecars);
        app.delete("/api/library/packs/{name}", this::handleRemovePack);

        // Steward proposal endpoints
        app.get("/api/library/proposals", this::handleListProposals);
        app.post("/api/library/proposals/{id}/approve", this::handleApproveProposal);
        app.post("/api/library/proposals/{id}/reject", this::handleRejectProposal);
        app.get("/api/library/misses", this::handleMisses);

        // OPDS-K catalog feed
        app.get("/api/library/opds", this::handleOpdsCatalog);
    }

    /**
     * GET /api/library/opds — OPDS-K catalog feed.
     * Returns installed knowledge packs as an OPDS-compatible JSON catalog.
     */
    private void handleOpdsCatalog(Context ctx) {
        var packs = store.listKnowledgePacks();
        var entries = new ArrayList<Map<String, Object>>();
        for (var packEntry : packs.entrySet()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", packEntry.getKey());
            entry.put("title", packEntry.getKey());
            entry.put("chunks", packEntry.getValue());
            entry.put("links", List.of(
                Map.of(
                    "rel", "http://opds-spec.org/acquisition",
                    "href", "/api/library/packs/" + packEntry.getKey(),
                    "type", "application/wyrdpak+zip"
                )
            ));
            entries.add(entry);
        }

        var catalog = new LinkedHashMap<String, Object>();
        catalog.put("metadata", Map.of(
            "title", "Wyrdsekai Knowledge Library",
            "subtitle", "OPDS-K Catalog",
            "updated", Instant.now().toString()
        ));
        catalog.put("entries", entries);
        ctx.json(catalog);
    }

    /**
     * GET /api/library/search?q={query}&limit={limit}
     * Text search over the knowledge base (BM25).
     */
    private void handleSearch(Context ctx) {
        var q = ctx.queryParam("q");
        if (q == null || q.isBlank()) {
            ctx.status(400).json(Map.of("error", "q parameter required"));
            return;
        }

        var limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(10);
        if (limit < 1) limit = 1;
        if (limit > 100) limit = 100;

        var results = store.searchKnowledgeText(q, limit);

        log.info("[Library] search q=\"{}\" limit={} hits={}", q, limit, results.size());

        ctx.json(Map.of(
            "query", q,
            "limit", limit,
            "count", results.size(),
            "results", results
        ));
    }

    /**
     * GET /api/library/packs
     * List installed knowledge packs with their chunk counts.
     */
    private void handleListPacks(Context ctx) {
        var packs = store.listKnowledgePacks();

        var packList = packs.entrySet().stream()
            .map(e -> Map.of("name", e.getKey(), "chunks", e.getValue()))
            .toList();

        ctx.json(Map.of(
            "packs", packList,
            "totalPacks", packList.size()
        ));
    }

    /**
     * GET /api/library/packs/{name}
     * Info about a specific knowledge pack.
     */
    private void handlePackInfo(Context ctx) {
        var name = ctx.pathParam("name");
        var chunks = indexer.packSize(name);

        if (chunks == 0) {
            ctx.status(404).json(Map.of("error", "Pack not found: " + name));
            return;
        }

        ctx.json(Map.of(
            "name", name,
            "chunks", chunks
        ));
    }

    /**
     * GET /api/library/status
     * Knowledge base statistics: total chunks, collection counts, installed packs.
     */
    private void handleStatus(Context ctx) {
        var totalKnowledge = indexer.totalSize();
        var packs = store.listKnowledgePacks();
        var lcshCount = store.totalCount(SearchCollections.LCSH);

        var status = new LinkedHashMap<String, Object>();
        status.put("totalChunks", totalKnowledge);
        status.put("totalPacks", packs.size());
        status.put("lcshTerms", lcshCount);
        status.put("packs", packs);

        ctx.json(status);
    }

    /**
     * POST /api/library/install
     * Install a knowledge pack. Accepts:
     *   {"pack": "pack-name"}         — install from built-in registry
     *   {"pack": "my-name", "url": "https://..."} — install from any URL
     */
    /** Share a Study collection zone-wide as a local pack — see
     *  StudyService.shareCollection (2026-08-25). Runs IN the server because
     *  the Lucene index has one writer; an offline CLI could never take the
     *  lock while the zone is up. Body: {"collection": "...", "owner": "..."}
     *  — owner defaults to the zone owner. */
    private void handleShareCollection(Context ctx) {
        var body = ctx.bodyAsClass(ShareRequest.class);
        if (body.collection() == null || body.collection().isBlank()) {
            ctx.status(400).json(Map.of("error", "collection field required"));
            return;
        }
        var owner = body.owner() != null && !body.owner().isBlank()
            ? body.owner()
            : (ActionGrants.get() != null ? ActionGrants.get().fallbackOwnerDid() : null);
        if (owner == null) {
            ctx.status(400).json(Map.of(
                "error", "no owner given and no zone owner configured"));
            return;
        }
        try {
            var svc = new StudyService(store, HomeClients.get());
            int chunks = svc.shareCollection(owner, body.collection(), packsDir, indexer);
            ctx.json(Map.of(
                "status", chunks > 0 ? "shared" : "empty",
                "collection", body.collection(),
                "pack", "study-share-" + body.collection().toLowerCase()
                    .replaceAll("[^a-z0-9_-]", "-"),
                "chunks", chunks));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    record ShareRequest(String collection, String owner) {}

    /**
     * Remove sidecar documents ({@code metadata.opf} and friends) that an
     * older ingest swept into a Study collection, and from the pack published
     * from it. Runs in-server because Lucene has one writer.
     */
    private void handlePruneSidecars(Context ctx) {
        var body = ctx.bodyAsClass(ShareRequest.class);
        if (body.collection() == null || body.collection().isBlank()) {
            ctx.status(400).json(Map.of("error", "collection field required"));
            return;
        }
        var owner = body.owner() != null && !body.owner().isBlank()
            ? body.owner()
            : (ActionGrants.get() != null ? ActionGrants.get().fallbackOwnerDid() : null);
        if (owner == null) {
            ctx.status(400).json(Map.of(
                "error", "no owner given and no zone owner configured"));
            return;
        }
        var pack = "study-share-" + body.collection().toLowerCase()
            .replaceAll("[^a-z0-9_-]", "-");
        try {
            var result = new StudyService(store, HomeClients.get())
                .pruneSidecars(owner, body.collection(), pack);
            ctx.json(Map.of(
                "status", "pruned",
                "collection", body.collection(),
                "pack", pack,
                "scanned", result.scanned(),
                "studyRemoved", result.studyRemoved(),
                "knowledgeRemoved", result.knowledgeRemoved()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void handleInstall(Context ctx) {
        var body = ctx.bodyAsClass(InstallRequest.class);
        if (body.pack() == null || body.pack().isBlank()) {
            ctx.status(400).json(Map.of("error", "pack field required"));
            return;
        }

        var packName = body.pack();

        // Check if already installed
        if (indexer.packSize(packName) > 0) {
            ctx.json(Map.of(
                "status", "already_installed",
                "pack", packName,
                "chunks", indexer.packSize(packName)
            ));
            return;
        }

        log.info("[Library] Starting install of pack '{}'", packName);

        CompletableFuture<?> future;
        if (body.url() != null && !body.url().isBlank()) {
            future = KnowledgePackRegistry.installFromUrl(packName, body.url(), packsDir, indexer,
                msg -> log.info("[Library] {}: {}", packName, msg));
        } else {
            var packInfo = KnowledgePackRegistry.find(packName);
            if (packInfo.isEmpty()) {
                var available = KnowledgePackRegistry.listAvailable().stream()
                    .map(KnowledgePackRegistry.PackInfo::name).toList();
                ctx.status(404).json(Map.of(
                    "error", "Unknown pack: " + packName,
                    "available", available
                ));
                return;
            }
            future = KnowledgePackRegistry.install(packName, packsDir, indexer,
                msg -> log.info("[Library] {}: {}", packName, msg));
        }
        // Log async errors (don't block the HTTP response)
        future.whenComplete((result, error) -> {
            if (error != null) {
                log.error("[Library] Pack '{}' install FAILED: {}", packName, error.getMessage(), error);
            }
        });

        ctx.status(202).json(Map.of(
            "status", "installing",
            "pack", packName,
            "message", "Download and indexing started. Check /api/library/packs/" + packName + " for progress."
        ));
    }

    /**
     * GET /api/library/available
     * List packs available for installation from the built-in registry.
     */
    private void handleAvailable(Context ctx) {
        var available = KnowledgePackRegistry.listAvailable().stream()
            .map(p -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("name", p.name());
                m.put("title", p.title());
                m.put("description", p.description());
                m.put("size", p.estimatedSize());
                m.put("copyright", p.copyright());
                m.put("contentRating", p.contentRating());
                m.put("tier", p.effectiveTier());
                m.put("shelf", p.shelf());
                m.put("recommended", Boolean.TRUE.equals(p.recommended()));
                m.put("language", p.language());
                m.put("noFederate", p.isNoFederate());
                m.put("installed", indexer.packSize(p.name()) > 0);
                return m;
            })
            .toList();
        ctx.json(Map.of("available", available));
    }

    /**
     * DELETE /api/library/packs/{name}
     * Remove all chunks for a knowledge pack.
     */
    private void handleRemovePack(Context ctx) {
        var name = ctx.pathParam("name");
        var existing = indexer.packSize(name);

        if (existing == 0) {
            ctx.status(404).json(Map.of("error", "Pack not found: " + name));
            return;
        }

        long deleted = indexer.removePack(name);
        log.info("[Library] Removed pack '{}': {} chunks deleted", name, deleted);

        ctx.json(Map.of(
            "status", "removed",
            "pack", name,
            "chunksDeleted", deleted
        ));
    }

    /**
     * GET /api/library/proposals?status={pending|all}
     * Agent acquisition proposals awaiting the steward (default: pending only).
     */
    private void handleListProposals(Context ctx) {
        var table = LibraryServices.arrivalTable();
        if (table == null) {
            ctx.status(503).json(Map.of("error", "Library proposals not available on this node"));
            return;
        }
        var all = "all".equalsIgnoreCase(ctx.queryParam("status"));
        var proposals = all ? table.list() : table.pending();
        ctx.json(Map.of(
            "proposals", proposals,
            "count", proposals.size()
        ));
    }

    /**
     * POST /api/library/proposals/{id}/approve
     * Approve a pending proposal by id (or unique id prefix). Body (optional):
     * {"reviewer": "..."}. When the proposal carries sources, ingest runs in
     * the background — poll GET /api/library/proposals?status=all for INGESTED.
     */
    private void handleApproveProposal(Context ctx) {
        var table = LibraryServices.arrivalTable();
        if (table == null) {
            ctx.status(503).json(Map.of("error", "Library proposals not available on this node"));
            return;
        }
        var match = findPendingByPrefix(table, ctx.pathParam("id"));
        if (match == null) {
            ctx.status(404).json(Map.of("error", "No pending proposal matching: " + ctx.pathParam("id")));
            return;
        }
        var reviewer = reviewerFrom(ctx);
        var approved = table.approve(match.id(), reviewer).orElse(null);
        if (approved == null) {
            ctx.status(409).json(Map.of("error", "Could not approve: " + match.id()));
            return;
        }
        boolean ingesting = !approved.sources().isEmpty();
        if (ingesting) {
            Thread.ofVirtual().name("proposal-ingest").start(() -> {
                try {
                    var result = new PackIngester(store).ingest(approved);
                    if (result.ok()) table.markIngested(approved.id());
                } catch (Exception e) {
                    log.warn("[Library] Proposal '{}' ingest failed: {}", approved.topic(), e.getMessage());
                }
            });
        }
        log.info("[Library] Proposal '{}' approved by {} (ingesting={})", approved.topic(), reviewer, ingesting);
        ctx.json(Map.of(
            "status", ingesting ? "approved_ingesting" : "approved",
            "proposal", approved
        ));
    }

    /**
     * POST /api/library/proposals/{id}/reject
     * Reject a pending proposal by id (or unique id prefix). Body (optional):
     * {"reviewer": "...", "reason": "..."}.
     */
    private void handleRejectProposal(Context ctx) {
        var table = LibraryServices.arrivalTable();
        if (table == null) {
            ctx.status(503).json(Map.of("error", "Library proposals not available on this node"));
            return;
        }
        var match = findPendingByPrefix(table, ctx.pathParam("id"));
        if (match == null) {
            ctx.status(404).json(Map.of("error", "No pending proposal matching: " + ctx.pathParam("id")));
            return;
        }
        var body = reviewBody(ctx);
        var reason = body.reason() == null || body.reason().isBlank()
            ? "declined by steward" : body.reason();
        var rejected = table.reject(match.id(), reviewerFrom(ctx), reason).orElse(null);
        if (rejected == null) {
            ctx.status(409).json(Map.of("error", "Could not reject: " + match.id()));
            return;
        }
        ctx.json(Map.of("status", "rejected", "proposal", rejected));
    }

    /**
     * GET /api/library/misses
     * Repeated library-search misses — what the household keeps asking that
     * the Library can't answer. Steward signal for new packs/acquisitions.
     */
    private void handleMisses(Context ctx) {
        var rl = LibraryServices.readingLog();
        if (rl == null) {
            ctx.status(503).json(Map.of("error", "Reading log not available on this node"));
            return;
        }
        var top = rl.topRepeatedTerms(200, 2);
        var misses = top.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(10)
            .map(e -> Map.of("term", e.getKey(), "count", e.getValue()))
            .toList();
        ctx.json(Map.of("misses", misses, "count", misses.size()));
    }

    private static ProposedPack findPendingByPrefix(
            ArrivalTable table, String idPrefix) {
        if (idPrefix == null || idPrefix.isBlank()) return null;
        var prefix = idPrefix.trim();
        return table.pending().stream()
            .filter(p -> p.id().startsWith(prefix))
            .findFirst().orElse(null);
    }

    private String reviewerFrom(Context ctx) {
        var body = reviewBody(ctx);
        return body.reviewer() == null || body.reviewer().isBlank() ? "steward" : body.reviewer();
    }

    private ReviewRequest reviewBody(Context ctx) {
        if (ctx.body().isBlank()) return new ReviewRequest(null, null);
        try {
            return ctx.bodyAsClass(ReviewRequest.class);
        } catch (Exception e) {
            return new ReviewRequest(null, null);
        }
    }

    /** Request body for proposal approve/reject. */
    private record ReviewRequest(String reviewer, String reason) {}

    /** Request body for POST /api/library/install */
    private record InstallRequest(String pack, String url) {}
}
