package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.identity.StudyOwnerGuard;
import org.wyrdsekai.core.library.CalibreCatalogIndexer;
import org.wyrdsekai.core.library.DocumentIndexer;
import org.wyrdsekai.core.library.StudyService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;

/**
 * HTTP endpoints for the private Study subsystem.
 *
 * <h3>Core</h3>
 *   GET    /api/study/search?q={query}&user={did}    — search Study content
 *   GET    /api/study/journal?user={did}              — list recent journal entries
 *   POST   /api/study/journal                         — write journal entry
 *   POST   /api/study/add                             — index documents from path
 *   GET    /api/study/status?user={did}               — Study statistics
 *   DELETE /api/study/collection/{name}?user={did}    — delete a collection
 *
 * <h3>L2 — Version Tracking</h3>
 *   PUT    /api/study/item/{id}                       — edit item (new version)
 *   GET    /api/study/item/{id}/history?user={did}    — version history
 *
 * <h3>L2 — Agent Consent</h3>
 *   POST   /api/study/consent/grant                   — grant companion access
 *   POST   /api/study/consent/revoke                  — revoke companion access
 *   GET    /api/study/consent?user={did}              — list grants
 *   GET    /api/study/consent/search                  — search as companion
 *
 * <h3>L2 — Shared Shelves</h3>
 *   POST   /api/study/share                           — share collection with user
 *   POST   /api/study/unshare                         — unshare collection
 *   GET    /api/study/shares?user={did}               — list shares
 *   GET    /api/study/share/search                    — search shared collection
 *
 * <h3>L2 — Storage & Export</h3>
 *   GET    /api/study/disk-usage?user={did}           — disk usage estimate
 *   POST   /api/study/export                          — export collection
 *   POST   /api/study/import                          — import collection
 */
public final class StudyRoutes {

    private static final Logger log = LoggerFactory.getLogger(StudyRoutes.class);

    /**
     * (owner|collection) keys with an ingest currently running. Static because
     * the constraint is per-node, whatever routes instance took the request —
     * the ledger file and the extraction workers are node-global resources.
     * add() is the atomic claim; the job's finally releases.
     */
    private static final Set<String> ACTIVE_INGESTS = ConcurrentHashMap.newKeySet();

    private final StudyService studyService;
    private final DocumentIndexer documentIndexer;

    public StudyRoutes(StudyService studyService, DocumentIndexer documentIndexer) {
        this.studyService = studyService;
        this.documentIndexer = documentIndexer;
    }

    public void register(JavalinDefaultRoutingApi app) {
        // Core
        app.get("/api/study/search", this::handleSearch);
        app.get("/api/study/journal", this::handleListJournal);
        app.post("/api/study/journal", this::handleWriteJournal);
        app.post("/api/study/add", this::handleAddDocuments);
        app.get("/api/study/status", this::handleStatus);
        app.delete("/api/study/collection/{name}", this::handleDeleteCollection);

        // L2 — Version Tracking
        app.put("/api/study/item/{id}", this::handleEditItem);
        app.get("/api/study/item/{id}/history", this::handleVersionHistory);

        // L2 — Agent Consent
        app.post("/api/study/consent/grant", this::handleGrantConsent);
        app.post("/api/study/consent/revoke", this::handleRevokeConsent);
        app.get("/api/study/consent", this::handleListConsent);
        // Everything a person needs to share a shelf, in one call: who they are,
        // which companions exist, which shelves exist. Added 2026-08-07 because
        // the consent model was complete and had no usable interface — enabling
        // it meant reading two DIDs out of sqlite and hand-writing curl, and a
        // permission nobody can grant may as well not exist.
        app.get("/api/study/sharing-context", this::handleSharingContext);
        app.get("/api/study/consent/search", this::handleSearchAsCompanion);

        // L2 — Shared Shelves
        app.post("/api/study/share", this::handleShare);
        app.post("/api/study/unshare", this::handleUnshare);
        app.get("/api/study/shares", this::handleListShares);
        app.get("/api/study/share/search", this::handleSearchShared);

        // L2 — Storage & Export
        app.get("/api/study/disk-usage", this::handleDiskUsage);
        app.post("/api/study/export", this::handleExport);
        app.post("/api/study/import", this::handleImport);

        // Phone sync
        // NOTE: the old GET/POST /api/study/sync (timestamp-LWW, unauthenticated
        // merge-by-body-userDid) is REMOVED — phone↔zone Study sync happens over
        // the authenticated Between CRDT channel (StudySyncPeer). Deleted pre-OSS.
    }

    private void handleSearch(Context ctx) {
        var q = ctx.queryParam("q");
        var userDid = ctx.queryParam("user");
        if (q == null || q.isBlank() || userDid == null || userDid.isBlank()) {
            ctx.status(400).json(Map.of("error", "q and user parameters required"));
            return;
        }
        var results = studyService.searchAll(userDid, q, 10);
        ctx.json(Map.of("query", q, "count", results.size(), "results", results));
    }

    private void handleListJournal(Context ctx) {
        var userDid = ctx.queryParam("user");
        if (userDid == null || userDid.isBlank()) {
            ctx.status(400).json(Map.of("error", "user parameter required"));
            return;
        }
        var entries = studyService.recentJournal(userDid, 20);
        ctx.json(Map.of("count", entries.size(), "entries", entries));
    }

    private void handleWriteJournal(Context ctx) {
        var body = ctx.bodyAsClass(JournalRequest.class);
        if (body.user() == null || body.content() == null) {
            ctx.status(400).json(Map.of("error", "user and content fields required"));
            return;
        }
        boolean isPrivate = body.isPrivate() != null && body.isPrivate();
        String id;
        if (isPrivate) {
            id = studyService.writePrivateJournalEntry(body.user(), body.content());
        } else {
            id = studyService.writeJournalEntry(body.user(), body.content());
        }
        log.info("[Study] journal write user={} private={} chars={} id={}",
            body.user(), isPrivate, body.content().length(), id);
        ctx.json(Map.of("id", id, "private", isPrivate));
    }

    private void handleAddDocuments(Context ctx) {
        var body = ctx.bodyAsClass(AddRequest.class);
        if (body.user() == null || body.path() == null) {
            ctx.status(400).json(Map.of("error", "user and path fields required"));
            return;
        }

        // Resolve the owner SYNCHRONOUSLY, before the async job starts. The
        // indexing below runs in a CompletableFuture that only logs failures, so
        // a refusal raised in there would return 200 to the caller and silently
        // index nothing — the exact silent-failure shape that let 13.7M rows be
        // written under an owner referring to nobody.
        final String owner;
        try {
            owner = StudyOwnerGuard.require(body.user());
        } catch (StudyOwnerGuard.UnresolvableOwnerException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
            return;
        }

        var dir = Path.of(body.path());
        var collection = body.collection() != null ? body.collection()
            : dir.getFileName().toString();

        // mode "catalog": index only the Calibre metadata.db card catalog —
        // seconds even for tens of thousands of books. mode "full": extract
        // and index every document's text. mode "auto" (default): catalog
        // when the directory is a Calibre library, full otherwise.
        var requested = body.mode() == null || body.mode().isBlank() ? "auto" : body.mode();
        var mode = switch (requested) {
            case "catalog", "full" -> requested;
            default -> CalibreCatalogIndexer.isCalibreLibrary(dir) ? "catalog" : "full";
        };

        // ONE INGEST PER (owner, collection) AT A TIME.
        //
        // Every POST used to fire a fresh async job unconditionally, so a
        // double-submit — an impatient re-run, a retried curl, two terminals —
        // ran two full passes over the same 74k-book tree at once. The index
        // survives that (content-derived ids + updateDocument = last-write-
        // wins), but everything else degrades: double extraction CPU for
        // hours, two writers interleaving one ledger file, and two "Processed
        // N files" streams shredding any progress monitoring. Refusing is
        // kinder than racing: the second caller is told a run is active, and
        // the ledger already makes a SEQUENTIAL re-run cheap and correct.
        var ingestKey = owner + "|" + collection;
        if (!ACTIVE_INGESTS.add(ingestKey)) {
            log.info("[Study] Ingest already running for '{}' — refusing duplicate", collection);
            ctx.status(409).json(Map.of(
                "status", "already_indexing",
                "collection", collection,
                "message", "An ingest for this collection is already running. It is "
                    + "resumable: if it was interrupted, re-run after it stops."));
            return;
        }

        log.info("[Study] Indexing documents for {}: {} -> collection '{}' (mode={})",
            body.user(), body.path(), collection, mode);

        // Run async
        CompletableFuture.runAsync(() -> {
            try {
                if (mode.equals("catalog")) {
                    new CalibreCatalogIndexer(studyService).indexCatalog(
                        owner, collection, dir,
                        msg -> log.info("[Study] {}: {}", collection, msg));
                } else {
                    documentIndexer.indexDirectory(owner, collection, dir,
                        msg -> log.info("[Study] {}: {}", collection, msg));
                }
            } catch (Exception e) {
                log.error("[Study] Document indexing failed for {}: {}", collection, e.getMessage());
            } finally {
                // Always release, even on failure — a slot leaked here would
                // refuse every future ingest of this collection until restart.
                ACTIVE_INGESTS.remove(ingestKey);
            }
        });

        ctx.status(202).json(Map.of(
            "status", "indexing",
            "mode", mode,
            "collection", collection,
            "path", body.path(),
            "message", mode.equals("catalog")
                ? "Calibre catalog indexing started (metadata card-catalog; use mode=full for book text)."
                : "Document indexing started. Check /api/study/status for progress."
        ));
    }

    private void handleStatus(Context ctx) {
        var userDid = ctx.queryParam("user");
        if (userDid == null || userDid.isBlank()) {
            ctx.status(400).json(Map.of("error", "user parameter required"));
            return;
        }
        ctx.json(studyService.getStats(userDid));
    }

    private void handleDeleteCollection(Context ctx) {
        var name = ctx.pathParam("name");
        var userDid = ctx.queryParam("user");
        if (userDid == null || userDid.isBlank()) {
            ctx.status(400).json(Map.of("error", "user parameter required"));
            return;
        }
        long deleted = studyService.deleteCollection(userDid, name);
        ctx.json(Map.of("collection", name, "deleted", deleted));
    }

    // --- L2: Version Tracking ---

    private void handleEditItem(Context ctx) {
        var id = ctx.pathParam("id");
        var body = ctx.bodyAsClass(EditRequest.class);
        if (body.user() == null || body.content() == null) {
            ctx.status(400).json(Map.of("error", "user and content fields required"));
            return;
        }
        int newVersion = studyService.editItem(id, body.user(), body.content());
        if (newVersion < 0) {
            ctx.status(404).json(Map.of("error", "item not found", "id", id));
            return;
        }
        ctx.json(Map.of("id", id, "version", newVersion));
    }

    private void handleVersionHistory(Context ctx) {
        var id = ctx.pathParam("id");
        var userDid = ctx.queryParam("user");
        if (userDid == null || userDid.isBlank()) {
            ctx.status(400).json(Map.of("error", "user parameter required"));
            return;
        }
        var history = studyService.getVersionHistory(id, userDid, 20);
        ctx.json(Map.of("id", id, "versions", history.size(), "history", history));
    }

    // --- L2: Agent Consent ---


    /** Person + companions + shelves — the whole grant UX in one request. */
    private void handleSharingContext(Context ctx) {
        var jdbc = System.getProperty("wyrdsekai.jdbc.url");
        String person = null;
        var companions = new ArrayList<Map<String, String>>();
        if (jdbc != null && !jdbc.isBlank()) {
            try (var conn = DriverManager.getConnection(jdbc)) {
                try (var ps = conn.prepareStatement(
                        "SELECT did FROM person_identities ORDER BY created_at LIMIT 1");
                     var rs = ps.executeQuery()) {
                    if (rs.next()) person = rs.getString(1);
                } catch (SQLException ignore) { /* pre-migration node */ }

                // A name can map to several DIDs (a re-birth leaves both rows).
                // Return them newest-first so a caller taking the first gets the
                // live one rather than an arbitrary row.
                try (var ps = conn.prepareStatement(
                        "SELECT name, did FROM companions WHERE archived=0 "
                            + "ORDER BY last_seen_at DESC, born_at DESC");
                     var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        companions.add(Map.of("name", rs.getString("name"),
                                              "did", rs.getString("did")));
                    }
                } catch (SQLException ignore) { /* no companions yet */ }
            } catch (SQLException e) {
                log.debug("[Study] sharing-context db read failed: {}", e.getMessage());
            }
        }
        ctx.json(Map.of(
            "person", person == null ? "" : person,
            "personReady", person != null,
            "companions", companions));
    }

    private void handleGrantConsent(Context ctx) {
        var body = ctx.bodyAsClass(ConsentRequest.class);
        if (body.user() == null || body.companion() == null || body.collection() == null) {
            ctx.status(400).json(Map.of("error", "user, companion, and collection fields required"));
            return;
        }
        studyService.grantAccess(body.user(), body.companion(), body.collection());
        ctx.json(Map.of("granted", true, "companion", body.companion(), "collection", body.collection()));
    }

    private void handleRevokeConsent(Context ctx) {
        var body = ctx.bodyAsClass(ConsentRequest.class);
        if (body.user() == null || body.companion() == null || body.collection() == null) {
            ctx.status(400).json(Map.of("error", "user, companion, and collection fields required"));
            return;
        }
        studyService.revokeAccess(body.user(), body.companion(), body.collection());
        ctx.json(Map.of("revoked", true, "companion", body.companion(), "collection", body.collection()));
    }

    private void handleListConsent(Context ctx) {
        var userDid = ctx.queryParam("user");
        if (userDid == null || userDid.isBlank()) {
            ctx.status(400).json(Map.of("error", "user parameter required"));
            return;
        }
        var grants = studyService.listGrants(userDid);
        ctx.json(Map.of("user", userDid, "count", grants.size(), "grants", grants));
    }

    private void handleSearchAsCompanion(Context ctx) {
        var userDid = ctx.queryParam("user");
        var companion = ctx.queryParam("companion");
        var q = ctx.queryParam("q");
        if (userDid == null || companion == null || q == null || q.isBlank()) {
            ctx.status(400).json(Map.of("error", "user, companion, and q parameters required"));
            return;
        }
        var results = studyService.searchAsCompanion(userDid, companion, q, 10);
        ctx.json(Map.of("query", q, "count", results.size(), "results", results));
    }

    // --- L2: Shared Shelves ---

    private void handleShare(Context ctx) {
        var body = ctx.bodyAsClass(ShareRequest.class);
        if (body.owner() == null || body.collection() == null || body.target() == null) {
            ctx.status(400).json(Map.of("error", "owner, collection, and target fields required"));
            return;
        }
        studyService.shareCollection(body.owner(), body.collection(), body.target());
        ctx.json(Map.of("shared", true, "collection", body.collection(), "target", body.target()));
    }

    private void handleUnshare(Context ctx) {
        var body = ctx.bodyAsClass(ShareRequest.class);
        if (body.owner() == null || body.collection() == null || body.target() == null) {
            ctx.status(400).json(Map.of("error", "owner, collection, and target fields required"));
            return;
        }
        studyService.unshareCollection(body.owner(), body.collection(), body.target());
        ctx.json(Map.of("unshared", true, "collection", body.collection(), "target", body.target()));
    }

    private void handleListShares(Context ctx) {
        var userDid = ctx.queryParam("user");
        if (userDid == null || userDid.isBlank()) {
            ctx.status(400).json(Map.of("error", "user parameter required"));
            return;
        }
        var shares = studyService.listShares(userDid);
        ctx.json(Map.of("user", userDid, "count", shares.size(), "shares", shares));
    }

    private void handleSearchShared(Context ctx) {
        var owner = ctx.queryParam("owner");
        var collection = ctx.queryParam("collection");
        var requester = ctx.queryParam("requester");
        var q = ctx.queryParam("q");
        if (owner == null || collection == null || requester == null || q == null || q.isBlank()) {
            ctx.status(400).json(Map.of("error", "owner, collection, requester, and q parameters required"));
            return;
        }
        var results = studyService.searchSharedCollection(owner, collection, requester, q, 10);
        ctx.json(Map.of("query", q, "count", results.size(), "results", results));
    }

    // --- L2: Storage & Export ---

    private void handleDiskUsage(Context ctx) {
        var userDid = ctx.queryParam("user");
        if (userDid == null || userDid.isBlank()) {
            ctx.status(400).json(Map.of("error", "user parameter required"));
            return;
        }
        ctx.json(studyService.getDiskUsage(userDid));
    }

    private void handleExport(Context ctx) {
        var body = ctx.bodyAsClass(ExportRequest.class);
        if (body.user() == null || body.collection() == null) {
            ctx.status(400).json(Map.of("error", "user and collection fields required"));
            return;
        }
        try {
            var exportDir = Files.createTempDirectory("study-export-");
            int count = studyService.exportCollection(body.user(), body.collection(), exportDir);
            ctx.json(Map.of("exported", count, "collection", body.collection(),
                "path", exportDir.toString()));
        } catch (Exception e) {
            log.error("[Study] Export failed: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Export failed: " + e.getMessage()));
        }
    }

    private void handleImport(Context ctx) {
        var body = ctx.bodyAsClass(ImportRequest.class);
        if (body.user() == null || body.collection() == null || body.path() == null) {
            ctx.status(400).json(Map.of("error", "user, collection, and path fields required"));
            return;
        }
        try {
            int count = studyService.importCollection(body.user(), body.collection(), Path.of(body.path()));
            ctx.json(Map.of("imported", count, "collection", body.collection()));
        } catch (Exception e) {
            log.error("[Study] Import failed: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Import failed: " + e.getMessage()));
        }
    }

    // --- Request DTOs ---

    private record JournalRequest(String user, String content, Boolean isPrivate) {}
    private record AddRequest(String user, String path, String collection, String mode) {}
    private record EditRequest(String user, String content) {}
    private record ConsentRequest(String user, String companion, String collection) {}
    private record ShareRequest(String owner, String collection, String target) {}
    private record ExportRequest(String user, String collection) {}
    private record ImportRequest(String user, String collection, String path) {}
}
