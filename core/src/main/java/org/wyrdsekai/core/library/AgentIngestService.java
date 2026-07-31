package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.host.HostActionService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Agent-callable Library ingest — the enactment behind a companion being
 * ASKED "there's a folder of ebooks somewhere, ingest it". Wraps the same
 * machinery as {@code POST /api/study/add} ({@link CalibreCatalogIndexer}
 * for Calibre libraries, {@link DocumentIndexer} otherwise — streaming,
 * resumable, deterministic ids), but confined to the steward's
 * open-roots ({@code WYRDSEKAI_HOST_OPEN_ROOTS} / {@code host.open_roots})
 * so an agent can only ingest from directories the steward has granted.
 *
 * <p>Static seam (same pattern as {@code ForgeRoomBridge} /
 * {@code CompanionSpawner}): {@code init(studyService)} from Main; unwired
 * (tests, phone) returns an honest not-wired error. Ingest runs async —
 * the caller gets {@code {ok, started, mode, collection}} immediately and
 * the library becomes searchable as batches commit.</p>
 */
public final class AgentIngestService {

    private static final Logger log = LoggerFactory.getLogger(AgentIngestService.class);

    private static volatile StudyService studyService;
    private static volatile Supplier<List<String>> rootsSupplier = HostActionService::openRoots;

    private AgentIngestService() {}

    /** Wire the live StudyService. Called once from Main. */
    public static void init(StudyService service) {
        studyService = service;
    }

    /** Test seam: override where granted roots come from. */
    static void setRootsSupplier(Supplier<List<String>> supplier) {
        rootsSupplier = supplier != null ? supplier : HostActionService::openRoots;
    }

    /**
     * Start ingesting {@code path} into the caller's Study.
     *
     * @param userDid    whose Study receives the documents (the caller)
     * @param path       directory to ingest; must lie under a granted root
     * @param collection collection name (blank → directory name)
     * @param mode       "auto" | "catalog" | "full" (same semantics as the
     *                   REST route: auto → catalog for Calibre libraries)
     */
    public static Map<String, Object> ingest(String userDid, String path,
                                              String collection, String mode) {
        var service = studyService;
        if (service == null) {
            return Map.of("ok", false, "error", "library.ingest not wired");
        }
        if (path == null || path.isBlank()) {
            return Map.of("ok", false, "error", "missing_path");
        }

        var roots = rootsSupplier.get();
        if (roots.isEmpty()) {
            return Map.of("ok", false, "error", "no_roots",
                "hint", "steward must set host.open_roots / WYRDSEKAI_HOST_OPEN_ROOTS");
        }
        Path dir;
        try {
            dir = Path.of(path.trim()).toRealPath();
        } catch (Exception e) {
            return Map.of("ok", false, "error", "missing", "path", path.trim());
        }
        if (!Files.isDirectory(dir)) {
            return Map.of("ok", false, "error", "not_directory", "path", dir.toString());
        }
        var inside = false;
        for (var root : roots) {
            if (dir.startsWith(Path.of(root))) { inside = true; break; }
        }
        if (!inside) {
            return Map.of("ok", false, "error", "outside_roots", "path", dir.toString());
        }

        var finalCollection = collection != null && !collection.isBlank()
            ? collection.trim()
            : dir.getFileName().toString();
        var requested = mode == null || mode.isBlank() ? "auto" : mode;
        var finalMode = switch (requested) {
            case "catalog", "full" -> requested;
            default -> CalibreCatalogIndexer.isCalibreLibrary(dir) ? "catalog" : "full";
        };

        log.info("[AgentIngest] {} starts {} ingest of {} -> collection '{}'",
            userDid, finalMode, dir, finalCollection);
        CompletableFuture.runAsync(() -> {
            try {
                if (finalMode.equals("catalog")) {
                    new CalibreCatalogIndexer(service).indexCatalog(
                        userDid, finalCollection, dir,
                        msg -> log.info("[AgentIngest] {}: {}", finalCollection, msg));
                } else {
                    new DocumentIndexer(service).indexDirectory(
                        userDid, finalCollection, dir,
                        msg -> log.info("[AgentIngest] {}: {}", finalCollection, msg));
                }
            } catch (Exception e) {
                log.error("[AgentIngest] ingest of {} failed: {}", dir, e.getMessage());
            }
        });

        return Map.of("ok", true, "started", true,
            "mode", finalMode, "collection", finalCollection, "path", dir.toString());
    }
}
