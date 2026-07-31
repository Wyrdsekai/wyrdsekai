package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * On-disk cache of pre-fetched coding-CLI archives, used by
 * {@link BundleInstaller} as a fallback when the network is unreachable
 * (air-gapped households).
 *
 * <p>Cache layout:</p>
 * <pre>
 *   {dataDir}/coding-cli-bundle/cache/
 *     goose-1.0.0-linux-x64.tar.gz
 *     goose-1.0.0-linux-x64.tar.gz.sha256
 *     codex-1.2.3-darwin-arm64.tar.gz
 *     ...
 * </pre>
 *
 * <p>Pre-population is the steward's responsibility — typically through a
 * future {@code wyrd download-bundle} subcommand (Phase 2a optional;
 * currently a TODO). Until that ships, stewards drop archives in by hand
 * and the installer picks them up automatically.</p>
 */
public final class AirGapBundleCache {

    private static final Logger log = LoggerFactory.getLogger(AirGapBundleCache.class);

    private final Path cacheRoot;

    public AirGapBundleCache(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    /** Cache root directory — created lazily by {@link #ensureExists()}. */
    public Path root() { return cacheRoot; }

    public void ensureExists() throws IOException {
        Files.createDirectories(cacheRoot);
    }

    /**
     * Resolve a cached archive for {@code (backend, version, platformArch)}.
     * Returns empty if the cache miss should fall through to network.
     *
     * <p>Filename convention: {@code {backend}-{version}-{platformArch}.tar.gz}.
     * Stewards copying archives in must match this exactly.</p>
     */
    public Optional<Path> lookup(String backend, String version, String platformArch) {
        if (backend == null || version == null || platformArch == null) return Optional.empty();
        Path candidate = cacheRoot.resolve(
                backend + "-" + version + "-" + platformArch + ".tar.gz");
        if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
            log.info("[AirGapCache] hit: {}", candidate.getFileName());
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /**
     * Stash a freshly-downloaded archive in the cache so subsequent
     * installs (e.g. on other household nodes that share the same data
     * volume) can reuse it. Best-effort — failure to populate the cache
     * never breaks the install path.
     */
    public void store(String backend, String version, String platformArch, Path source) {
        try {
            ensureExists();
            Path target = cacheRoot.resolve(
                    backend + "-" + version + "-" + platformArch + ".tar.gz");
            Files.copy(source, target,
                    StandardCopyOption.REPLACE_EXISTING);
            log.info("[AirGapCache] stored: {}", target.getFileName());
        } catch (IOException e) {
            log.warn("[AirGapCache] failed to populate cache for {}-{}-{}: {}",
                    backend, version, platformArch, e.getMessage());
        }
    }
}
