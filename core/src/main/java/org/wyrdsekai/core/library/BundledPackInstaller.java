package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * packaging follow-up — indexes knowledge packs
 * that ship INSIDE the installer payload (dictionaries: jmdict,
 * freedict-spa-eng), so a fresh node is born with them and never needs the
 * network for the multilingual floor.
 *
 * <p>The payload layout is exactly what {@code wyrd library bundle} produces
 * ({@code <dir>/<pack-name>/pack.json + chunks/*.jsonl}); installers stage it
 * at {@code <installRoot>/share/library-bundle/} via
 * {@code packaging/build-library-bundle.sh}. Idempotent: packs already in the
 * index ({@code packSize > 0}) are skipped, so the boot hook is free after the
 * first run. Runs on a background virtual thread — indexing ~286K dictionary
 * chunks must not block boot.</p>
 */
public final class BundledPackInstaller {

    private static final Logger log = LoggerFactory.getLogger(BundledPackInstaller.class);

    private BundledPackInstaller() {}

    /**
     * Resolve the bundled-packs directory: {@code WYRDSEKAI_LIBRARY_BUNDLE_DIR}
     * env/profile override, else {@code <installRoot>/share/library-bundle}.
     * Returns null when neither resolves to an existing directory.
     */
    public static Path defaultBundleDir() {
        var override = WyrdConfig.get().resolve(
            "WYRDSEKAI_LIBRARY_BUNDLE_DIR", "paths.library_bundle_dir", () -> null);
        if (override != null && !override.isBlank()) {
            var p = Path.of(override);
            return Files.isDirectory(p) ? p : null;
        }
        var root = WyrdConfig.get().installRoot();
        if (root == null || root.isBlank()) return null;
        var p = Path.of(root, "share", "library-bundle");
        return Files.isDirectory(p) ? p : null;
    }

    /** Kick off bundled-pack indexing on a background thread. Returns immediately. */
    public static void installBundled(WyrdLuceneStore lucene, Path bundleDir) {
        if (lucene == null || bundleDir == null || !Files.isDirectory(bundleDir)) return;
        Thread.ofVirtual().name("bundled-pack-install").start(
            () -> installBundledSync(lucene, bundleDir));
    }

    /**
     * Index every prepared pack dir under {@code bundleDir} that isn't already
     * in the store. Synchronous — the test seam and the body of
     * {@link #installBundled}. Returns the number of packs indexed this pass.
     */
    public static int installBundledSync(WyrdLuceneStore lucene, Path bundleDir) {
        var indexer = new KnowledgePackIndexer(lucene);
        int installed = 0;
        for (var packDir : findBundledPacks(bundleDir)) {
            var name = packDir.getFileName().toString();
            try {
                if (indexer.packSize(name) > 0) {
                    log.info("[BundledPacks] '{}' already installed — skipping", name);
                    continue;
                }
                log.info("[BundledPacks] indexing bundled pack '{}'", name);
                var result = indexer.indexPack(packDir,
                    count -> log.info("[BundledPacks] {}: {} chunks indexed...", name, count));
                log.info("[BundledPacks] '{}' done — {} chunks", name, result.chunksIndexed());
                installed++;
            } catch (Exception e) {
                // One bad pack must not sink the rest; next boot retries.
                log.warn("[BundledPacks] '{}' failed: {}", name, e.getMessage());
            }
        }
        if (installed > 0) log.info("[BundledPacks] bundled install pass complete ({} packs)", installed);
        return installed;
    }

    /** Prepared pack dirs under {@code bundleDir}: subdirs with a non-empty chunks/. */
    static List<Path> findBundledPacks(Path bundleDir) {
        var out = new ArrayList<Path>();
        try (var dirs = Files.list(bundleDir)) {
            for (var dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                var chunks = dir.resolve("chunks");
                if (!Files.isDirectory(chunks)) continue;
                try (var c = Files.list(chunks)) {
                    if (c.anyMatch(f -> {
                        try { return Files.isRegularFile(f) && Files.size(f) > 0; }
                        catch (IOException e) { return false; }
                    })) {
                        out.add(dir);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[BundledPacks] cannot scan {}: {}", bundleDir, e.getMessage());
        }
        return out;
    }
}
