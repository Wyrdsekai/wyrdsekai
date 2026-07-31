package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Downloads, verifies, and atomically installs a coding-CLI backend
 * binary into the household's coding-CLI root.
 * §8.3.
 *
 * <p>Flow per {@link #installBackend}:
 * <ol>
 *   <li>Look up the entry in {@link BundleManifest}.</li>
 *   <li>Resolve {@code (platform, arch)} → manifest sha256 + URL.</li>
 *   <li>Refuse install if the sha256 is the {@code TODO_RUN_BUILD_HELPER}
 *       placeholder (manifest not finalised — would let any download
 *       through).</li>
 *   <li>Try the {@link AirGapBundleCache} first; fall through to HTTP on
 *       cache miss.</li>
 *   <li>Stream-download to {@code <root>/<backend>.partial}, computing
 *       sha256 inline.</li>
 *   <li>Verify the sha256 against the manifest. On mismatch, delete the
 *       partial and throw — the steward sees an actionable error.</li>
 *   <li>Atomic-rename the verified archive to its cache slot, then
 *       extract into {@code <root>/<backend>/}.</li>
 * </ol>
 *
 * <p>Does <i>not</i> handle setup-helper backends (Docker pulls — those
 * route through {@code wyrd setup openhands}) or config-only ones
 * (Devin). The CLI surfaces those distinctions via human-readable
 * messages before ever reaching {@link #installBackend}.</p>
 */
public final class BundleInstaller {

    private static final Logger log = LoggerFactory.getLogger(BundleInstaller.class);

    /**
     * sha256 placeholder used in the shipped manifest until the build
     * helper has populated real hashes. Treated as a hard refusal at
     * install time — never silently downloads + accepts whatever sha
     * the network returned.
     */
    public static final String SHA256_PLACEHOLDER = "TODO_RUN_BUILD_HELPER";

    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final int    DOWNLOAD_BUFFER_BYTES = 64 * 1024;

    private final BundleManifest manifest;
    private final HttpClient http;
    private final AirGapBundleCache cache;
    private final Archiver archiver;
    private final NpmInstaller npm;

    public BundleInstaller(BundleManifest manifest, AirGapBundleCache cache) {
        this(manifest, cache, defaultHttpClient(), new TarGzArchiver(), new DefaultNpmInstaller());
    }

    /** Test seam (without npm — npm-distributed entries fail clearly). */
    public BundleInstaller(BundleManifest manifest,
                           AirGapBundleCache cache,
                           HttpClient http,
                           Archiver archiver) {
        this(manifest, cache, http, archiver, new DefaultNpmInstaller());
    }

    /** Test seam — pluggable npm installer for hermetic tests. */
    public BundleInstaller(BundleManifest manifest,
                           AirGapBundleCache cache,
                           HttpClient http,
                           Archiver archiver,
                           NpmInstaller npm) {
        this.manifest = Objects.requireNonNull(manifest);
        this.cache    = Objects.requireNonNull(cache);
        this.http     = Objects.requireNonNull(http);
        this.archiver = Objects.requireNonNull(archiver);
        this.npm      = Objects.requireNonNull(npm);
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(HTTP_CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ── Status / list ──

    /** Listing of installed backends — directory walk, name only. */
    public List<String> listInstalled(Path destinationRoot) throws IOException {
        if (!Files.isDirectory(destinationRoot)) return List.of();
        try (var stream = Files.list(destinationRoot)) {
            List<String> out = new ArrayList<>();
            stream.filter(Files::isDirectory)
                  .map(p -> p.getFileName().toString())
                  .filter(n -> !n.startsWith("."))   // hidden / .partial / cache/
                  .filter(n -> !n.equals("cache"))
                  .sorted(Comparator.naturalOrder())
                  .forEach(out::add);
            return out;
        }
    }

    /**
     * Install status for one backend: was a directory installed, what
     * version is in its {@code .version} marker, where does it live.
     * Used by the Coding Slate furnishing to render check/dot indicators.
     *
     * <p>For npm-distributed entries, falls back to {@code npm ls -g
     * <pkg>} so the steward sees the right state even when the binary
     * lives outside Wyrdsekai's install root.</p>
     */
    public Status getStatus(String backendName, Path destinationRoot) {
        // npm-distributed entries don't live in destinationRoot — probe
        // the npm global install instead.
        var entryOpt = manifest.get(backendName);
        if (entryOpt.isPresent() && entryOpt.get().isNpmDistribution()) {
            BackendBundleEntry e = entryOpt.get();
            try {
                var r = npm.list(e.npmPackage());
                if (r.installed()) {
                    return new Status(true, r.version(), Path.of("npm:global:" + e.npmPackage()));
                }
            } catch (Exception probeErr) {
                log.debug("[BundleInstaller] npm ls probe failed for {}: {}",
                        e.npmPackage(), probeErr.getMessage());
            }
            return new Status(false, null, Path.of("npm:global:" + e.npmPackage()));
        }

        Path dir = destinationRoot.resolve(backendName);
        if (!Files.isDirectory(dir)) {
            return new Status(false, null, dir);
        }
        Path versionFile = dir.resolve(".version");
        String version = null;
        if (Files.isRegularFile(versionFile)) {
            try {
                version = Files.readString(versionFile).trim();
            } catch (IOException e) {
                log.warn("[BundleInstaller] failed to read {}: {}", versionFile, e.getMessage());
            }
        }
        return new Status(true, version, dir);
    }

    public record Status(boolean installed, String version, Path path) {}

    // ── Install / update / uninstall ──

    /**
     * Install (or refuse to overwrite an existing install of) a backend.
     *
     * @param name             backend name as it appears in the manifest
     * @param destinationRoot  install root (typically
     *                         {@code <dataDir>/coding-cli-bundle/}); created
     *                         if it doesn't exist.
     * @param force            when {@code true}, overwrite an existing
     *                         install. Otherwise throws {@link
     *                         InstallException} on conflict.
     * @return the on-disk path the backend was installed to.
     */
    public Path installBackend(String name, Path destinationRoot, boolean force) throws IOException {
        BackendBundleEntry entry = manifest.get(name).orElseThrow(() ->
                new InstallException("Unknown backend '" + name
                        + "'. Run `wyrd coding list` to see available backends."));

        if (entry.bundled()) {
            throw new InstallException("Backend '" + name
                    + "' is bundled with the install — no separate download needed.");
        }
        if (entry.configOnly()) {
            throw new InstallException("Backend '" + name
                    + "' is config-only (cloud SaaS). No binary to install — "
                    + "add the API key to your Key Chest.");
        }
        if (entry.dockerImage() != null && !entry.dockerImage().isBlank()) {
            String setup = entry.setupCommand() == null
                    ? "wyrd setup " + name
                    : entry.setupCommand();
            throw new InstallException("Backend '" + name
                    + "' is a setup-helper backend (Docker image). Run `"
                    + setup + "` instead.");
        }

        // v2 schema (2026-05-04 reconciliation): npm-distributed CLIs (Cline,
        // Continue, Claude SDK, Gemini CLI) install via `npm install -g`
        // rather than the atomic-tarball pipeline. The download path is owned
        // by npm itself; we just shell out and trust npm's own integrity.
        if (entry.isNpmDistribution()) {
            return installFromNpm(name, entry, destinationRoot);
        }

        String platformArch = currentPlatformArch();
        String expectedSha = entry.sha256For(platformArch);
        if (expectedSha == null || expectedSha.isBlank()) {
            throw new InstallException("Backend '" + name + "' has no sha256 entry for platform '"
                    + platformArch + "'. Manifest may not support this host.");
        }
        if (SHA256_PLACEHOLDER.equals(expectedSha)) {
            throw new InstallException("Backend '" + name + "' has a placeholder sha256 ("
                    + SHA256_PLACEHOLDER + ") for platform '" + platformArch
                    + "'. The Wyrdsekai release was built without running "
                    + "scripts/build-coding-cli-manifest.sh; refusing to install "
                    + "an unverified binary. Wait for the next release or run "
                    + "the helper locally before retrying.");
        }

        Path targetDir = destinationRoot.resolve(name);
        if (Files.exists(targetDir)) {
            if (!force) {
                throw new InstallException("Backend '" + name
                        + "' already installed at " + targetDir
                        + ". Pass --force to overwrite, or run `wyrd coding uninstall "
                        + name + "` first.");
            }
            log.info("[BundleInstaller] --force given; removing existing install at {}", targetDir);
            deleteRecursively(targetDir);
        }
        Files.createDirectories(destinationRoot);

        // 1. Resolve archive — try cache first, then HTTP.
        Path archive;
        Optional<Path> cached = cache.lookup(name, entry.version(), platformArch);
        if (cached.isPresent()) {
            archive = cached.get();
            log.info("[BundleInstaller] using cached archive {}", archive);
        } else {
            String url = entry.resolvedDownloadUrl(platformOnly(platformArch), archOnly(platformArch));
            log.info("[BundleInstaller] downloading {} {} ({}) from {}",
                    name, entry.version(), platformArch, url);
            archive = downloadToPartial(name, destinationRoot, url);
            cache.store(name, entry.version(), platformArch, archive);
        }

        // 2. sha256-verify (always — even for cached files; cheap insurance
        //    against tampering with the cache directory).
        String actualSha = sha256Hex(archive);
        if (!actualSha.equalsIgnoreCase(expectedSha)) {
            try { Files.deleteIfExists(archive); } catch (IOException ignore) {}
            throw new InstallException("sha256 mismatch for " + name + " " + entry.version()
                    + " (" + platformArch + "): expected " + expectedSha
                    + " but got " + actualSha + ". Refusing to install. "
                    + "Either the download was corrupted or the manifest is stale; "
                    + "retry, or check for an updated manifest.");
        }
        log.info("[BundleInstaller] sha256 OK for {}: {}", name, actualSha);

        // 3. Atomic-extract: extract to <name>.tmp, then rename to <name>.
        Path tmpDir = destinationRoot.resolve(name + ".tmp");
        if (Files.exists(tmpDir)) deleteRecursively(tmpDir);
        Files.createDirectories(tmpDir);
        archiver.extract(archive, tmpDir);

        // Drop a `.version` marker so getStatus() can report the installed
        // version without re-reading the manifest.
        Files.writeString(tmpDir.resolve(".version"), entry.version());

        try {
            Files.move(tmpDir, targetDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Some filesystems (notably overlayfs in Docker) reject ATOMIC_MOVE
            // across directories. Fall back to a non-atomic move.
            log.warn("[BundleInstaller] atomic move failed ({}); falling back", e.getMessage());
            Files.move(tmpDir, targetDir);
        }

        // Tidy: the .partial download lives in destinationRoot itself (alongside
        // the install dirs). Once it has been sha256-verified and copied into
        // the air-gap cache, we have no further use for it. Cached lookups
        // skip the .partial path entirely.
        Path partial = destinationRoot.resolve(name + ".partial");
        try { Files.deleteIfExists(partial); } catch (IOException ignore) {}

        log.info("[BundleInstaller] installed '{}' v{} -> {}", name, entry.version(), targetDir);
        return targetDir;
    }

    /**
     * v2 install path: shell out to {@code npm install -g <pkg>@<version>}
     * for the npm-distributed entries (Cline, Continue, Claude SDK,
     * Gemini CLI). Refuses cleanly with an actionable error when
     * {@code npm} isn't on {@code PATH} ("install Node.js 20+ from
     * nodejs.org").
     *
     * <p>Doesn't touch {@code destinationRoot} — npm owns its own global
     * install directory. The returned {@link Path} is a synthetic marker
     * ({@code npm:global:<pkg>}) so callers (Coding Slate, status display)
     * see something meaningful without needing to know the npm prefix.</p>
     */
    private Path installFromNpm(String name, BackendBundleEntry entry, Path destinationRoot)
            throws IOException {
        if (entry.npmPackage() == null || entry.npmPackage().isBlank()) {
            throw new InstallException("npm-distributed backend '" + name
                    + "' is missing 'npm_package' in the manifest — "
                    + "wyrdsekai release was built with a malformed manifest.");
        }
        if (!npm.isAvailable()) {
            throw new InstallException("Backend '" + name
                    + "' is npm-distributed (npm install -g " + entry.npmPackage()
                    + "@" + entry.version() + ") but `npm` was not found on PATH. "
                    + "Install Node.js 20+ from https://nodejs.org and re-run "
                    + "`wyrd coding install " + name + "`.");
        }
        log.info("[BundleInstaller] npm install -g {}@{}", entry.npmPackage(), entry.version());
        var result = npm.installGlobal(entry.npmPackage(), entry.version());
        if (!result.success()) {
            throw new InstallException("npm install -g " + entry.npmPackage()
                    + "@" + entry.version() + " failed (exit " + result.exitCode()
                    + "): " + (result.stderr() == null ? "" : result.stderr().trim()));
        }
        log.info("[BundleInstaller] npm-installed '{}' v{}", name, entry.version());
        // destinationRoot isn't owned by npm — but stash a stub `.npm-version`
        // marker there so getStatus() / list() see a directory presence in
        // tooling that walks destinationRoot. The Coding Slate furnishing
        // uses getStatus() which prefers the npm probe; this is just the
        // belt-and-suspenders backup for legacy listers.
        try {
            Files.createDirectories(destinationRoot.resolve(name));
            Files.writeString(destinationRoot.resolve(name).resolve(".npm-version"),
                    entry.version());
        } catch (IOException stubErr) {
            log.debug("[BundleInstaller] couldn't write .npm-version stub: {}",
                    stubErr.getMessage());
        }
        return Path.of("npm:global:" + entry.npmPackage());
    }

    /**
     * Update: re-installs if the manifest version differs from the
     * installed version. No-op if already current.
     */
    public Optional<Path> updateBackend(String name, Path destinationRoot) throws IOException {
        BackendBundleEntry entry = manifest.get(name).orElseThrow(() ->
                new InstallException("Unknown backend '" + name + "'"));
        Status status = getStatus(name, destinationRoot);
        if (!status.installed()) {
            // Nothing to update — installer would refuse with InstallException.
            return Optional.empty();
        }
        if (entry.version() != null && entry.version().equals(status.version())) {
            log.info("[BundleInstaller] '{}' already at v{}; nothing to do", name, entry.version());
            return Optional.empty();
        }
        log.info("[BundleInstaller] updating '{}' {} -> {}",
                name, status.version(), entry.version());
        return Optional.of(installBackend(name, destinationRoot, /*force*/ true));
    }

    /** Recursively delete a backend's install directory. No-op if absent. */
    public boolean uninstallBackend(String name, Path destinationRoot) throws IOException {
        Path dir = destinationRoot.resolve(name);
        if (!Files.exists(dir)) return false;
        deleteRecursively(dir);
        log.info("[BundleInstaller] uninstalled '{}' from {}", name, dir);
        return true;
    }

    /**
     * Download a single backend / platform archive into the air-gap cache,
     * verify its sha256 against the manifest, but do not extract or
     * install. Used by {@code wyrd download-bundle} (
     * §8.4) to pre-fetch every (backend, platform) pair on a connected
     * machine for later air-gapped installs.
     *
     * <p>If the cache already contains a file matching the manifest's
     * sha256, the download is skipped — repeat invocations are idempotent.
     * If the cache contains a file whose sha256 does <i>not</i> match
     * (corruption / stale manifest), the file is overwritten.</p>
     *
     * @param name          backend name from the manifest
     * @param platformArch  {@code "<platform>-<arch>"} (e.g. {@code "linux-x64"})
     * @return result describing whether bytes were downloaded, the final
     *         on-disk archive path, and the size in bytes
     * @throws InstallException sha256 mismatch / placeholder hash / unknown
     *         backend / unsupported platform / non-installable shape
     *         (bundled, docker, config-only)
     */
    public DownloadResult downloadOnly(String name, String platformArch) throws IOException {
        BackendBundleEntry entry = manifest.get(name).orElseThrow(() ->
                new InstallException("Unknown backend '" + name
                        + "'. Run `wyrd coding list` to see available backends."));

        if (!entry.isInstallable()) {
            // Bundled / docker / config-only — nothing to pre-fetch.
            String reason;
            if (entry.bundled()) reason = "bundled";
            else if (entry.configOnly()) reason = "config-only";
            else if (entry.dockerImage() != null) reason = "docker-image";
            else reason = "non-downloadable";
            throw new InstallException("Backend '" + name
                    + "' is " + reason + " — nothing to pre-fetch.");
        }

        // npm-distributed entries don't pre-fetch through this path — npm has
        // its own cache and resolves at install time. Pre-fetching for
        // air-gap is not supported for npm CLIs in v2; air-gap households
        // can either run `npm pack <pkg>` manually or wait for a future
        // dedicated subcommand.
        if (entry.isNpmDistribution()) {
            throw new InstallException("Backend '" + name + "' is npm-distributed — "
                    + "use `wyrd coding install " + name + "` (which shells out to "
                    + "npm) instead. Air-gap pre-fetch for npm is not supported in "
                    + "manifest v2.");
        }

        String expectedSha = entry.sha256For(platformArch);
        if (expectedSha == null || expectedSha.isBlank()) {
            throw new InstallException("Backend '" + name + "' has no sha256 entry for platform '"
                    + platformArch + "'. Manifest may not support this host.");
        }
        if (SHA256_PLACEHOLDER.equals(expectedSha)) {
            throw new InstallException("Backend '" + name + "' has a placeholder sha256 ("
                    + SHA256_PLACEHOLDER + ") for platform '" + platformArch
                    + "'. Run scripts/build-coding-cli-manifest.sh before pre-fetching.");
        }

        cache.ensureExists();
        Path target = cache.root().resolve(
                name + "-" + entry.version() + "-" + platformArch + ".tar.gz");

        // Idempotency: skip if cached file already matches the manifest sha.
        if (Files.isRegularFile(target)) {
            String existing = sha256Hex(target);
            if (existing.equalsIgnoreCase(expectedSha)) {
                long size = Files.size(target);
                log.info("[BundleInstaller] cache hit (sha-verified) {} -> skipping", target);
                return new DownloadResult(name, platformArch, target, size, false);
            }
            log.warn("[BundleInstaller] cached {} sha mismatch (expected {} got {}); refetching",
                    target.getFileName(), expectedSha, existing);
            Files.deleteIfExists(target);
        }

        String url = entry.resolvedDownloadUrl(platformOnly(platformArch), archOnly(platformArch));
        log.info("[BundleInstaller] downloading {} {} ({}) from {}",
                name, entry.version(), platformArch, url);

        // Stream to a sibling .partial under the cache directory itself so
        // a half-written download can never be picked up by a concurrent
        // installer on the same data volume.
        Path partial = cache.root().resolve(
                name + "-" + entry.version() + "-" + platformArch + ".tar.gz.partial");
        Files.deleteIfExists(partial);
        downloadStream(url, partial);

        String actualSha = sha256Hex(partial);
        if (!actualSha.equalsIgnoreCase(expectedSha)) {
            try { Files.deleteIfExists(partial); } catch (IOException ignore) {}
            throw new InstallException("sha256 mismatch for " + name + " " + entry.version()
                    + " (" + platformArch + "): expected " + expectedSha
                    + " but got " + actualSha + ". Refusing to populate cache.");
        }

        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("[BundleInstaller] atomic move failed ({}); falling back", e.getMessage());
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }

        long size = Files.size(target);
        log.info("[BundleInstaller] cached {} ({} bytes, sha {})",
                target.getFileName(), size, actualSha);
        return new DownloadResult(name, platformArch, target, size, true);
    }

    /** Result of a {@link #downloadOnly} call — surfaced in CLI progress output. */
    public record DownloadResult(
            String backend,
            String platformArch,
            Path archive,
            long bytes,
            boolean downloaded   // false = cache hit, true = bytes streamed from network
    ) {}

    // ── Helpers ──

    /**
     * Stream the URL's bytes to {@code <root>/<name>.partial} on disk.
     * Caller is responsible for verifying sha256 + renaming.
     */
    private Path downloadToPartial(String name, Path destinationRoot, String url) throws IOException {
        Files.createDirectories(destinationRoot);
        Path partial = destinationRoot.resolve(name + ".partial");
        downloadStream(url, partial);
        return partial;
    }

    /**
     * Atomically (well — overwrite-safely) stream a URL to {@code target}.
     * Caller owns sha256 verification and any further rename / extract.
     * Public-package so {@link #downloadOnly} can reuse the same primitive.
     */
    private void downloadStream(String url, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.deleteIfExists(target);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InstallException("Download interrupted: " + url, e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new InstallException("Download failed (" + resp.statusCode() + "): " + url);
        }
        try (var in = resp.body();
             var out = Files.newOutputStream(target)) {
            byte[] buf = new byte[DOWNLOAD_BUFFER_BYTES];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
        }
    }

    /** Streaming SHA-256 of a file → lowercase hex. */
    static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[DOWNLOAD_BUFFER_BYTES];
                int read;
                while ((read = in.read(buf)) != -1) {
                    md.update(buf, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : md.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available on this JDK", e);
        }
    }

    /**
     * Recursive directory deletion. Java's {@link Files#walk} + delete on
     * close keeps this short; chosen over a custom FileVisitor for clarity.
     */
    static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(p -> {
                      try { Files.delete(p); }
                      catch (IOException e) {
                          throw new RuntimeException("Failed to delete " + p, e);
                      }
                  });
        }
    }

    /**
     * Map this JVM's host to a {@code "<platform>-<arch>"} key matching
     * the manifest. Mirrors {@code detect_platform} in {@code bin/wyrd}.
     */
    public static String currentPlatformArch() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String platform;
        if (osName.contains("mac") || osName.contains("darwin")) {
            platform = "darwin";
        } else if (osName.contains("win")) {
            platform = "windows";
        } else {
            platform = "linux";
        }
        String arch;
        if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            arch = "arm64";
        } else if (osArch.contains("amd64") || osArch.contains("x86_64") || osArch.contains("x64")) {
            arch = "x64";
        } else if (osArch.contains("arm")) {
            arch = "arm";
        } else {
            arch = osArch.isEmpty() ? "x64" : osArch;
        }
        return platform + "-" + arch;
    }

    private static String platformOnly(String platformArch) {
        int dash = platformArch.indexOf('-');
        return dash < 0 ? platformArch : platformArch.substring(0, dash);
    }

    private static String archOnly(String platformArch) {
        int dash = platformArch.indexOf('-');
        return dash < 0 ? "x64" : platformArch.substring(dash + 1);
    }

    /**
     * Test-friendly archive abstraction. Production uses
     * {@link TarGzArchiver}; tests inject a no-op or pre-canned extractor.
     */
    public interface Archiver {
        void extract(Path archive, Path targetDir) throws IOException;
    }

    /**
     * Test-friendly indirection for the npm subprocess. Production uses
     * {@link DefaultNpmInstaller}; tests inject a stub that records calls
     * and returns canned results.
     */
    public interface NpmInstaller {
        /** True iff {@code npm} resolves on {@code PATH}. */
        boolean isAvailable();

        /** Result of {@code npm install -g <pkg>@<version>}. */
        NpmInstallResult installGlobal(String npmPackage, String version) throws IOException;

        /** Result of {@code npm ls -g <pkg>}. */
        NpmListResult list(String npmPackage) throws IOException;

        /** Outcome of {@link #installGlobal}. */
        record NpmInstallResult(int exitCode, String stdout, String stderr) {
            public boolean success() { return exitCode == 0; }
        }

        /** Outcome of {@link #list} — installed flag and (best-effort) parsed version. */
        record NpmListResult(boolean installed, String version) {}
    }

    /**
     * Default {@link NpmInstaller} — shells out to a real {@code npm}
     * binary discovered on {@code PATH}. Cheaply checks the binary's
     * existence by walking {@code PATH} (mirrors {@link
     * OpenHandsBackend#probeDockerDefault}) so a host without Node.js
     * fails instantly rather than after a full {@link ProcessBuilder}
     * cycle.
     */
    public static final class DefaultNpmInstaller implements NpmInstaller {
        /** Most npm registry installs land in <60s; cap at 5min for big bundles. */
        private static final Duration NPM_INSTALL_TIMEOUT = Duration.ofMinutes(5);
        /** {@code npm ls} should complete in <2s on a healthy install. */
        private static final Duration NPM_LIST_TIMEOUT = Duration.ofSeconds(10);

        @Override public boolean isAvailable() {
            String path = System.getenv("PATH");
            if (path == null) return false;
            for (var dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                for (var bin : new String[]{"npm", "npm.cmd", "npm.exe"}) {
                    var candidate = Path.of(dir, bin);
                    if (Files.isExecutable(candidate)) return true;
                }
            }
            return false;
        }

        @Override
        public NpmInstallResult installGlobal(String npmPackage, String version) throws IOException {
            String spec = (version == null || version.isBlank())
                    ? npmPackage
                    : npmPackage + "@" + version;
            var pb = new ProcessBuilder("npm", "install", "-g", spec)
                    .redirectErrorStream(false);
            try {
                Process p = pb.start();
                String stdout = new String(p.getInputStream().readAllBytes());
                String stderr = new String(p.getErrorStream().readAllBytes());
                boolean done = p.waitFor(NPM_INSTALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (!done) {
                    p.destroyForcibly();
                    return new NpmInstallResult(-1, stdout,
                            "npm install -g " + spec + " timed out after "
                                    + NPM_INSTALL_TIMEOUT.toMinutes() + " min");
                }
                return new NpmInstallResult(p.exitValue(), stdout, stderr);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("npm install interrupted", e);
            }
        }

        @Override
        public NpmListResult list(String npmPackage) throws IOException {
            // `npm ls -g <pkg>` returns:
            //   exit 0 + stdout naming the package & version when installed
            //   exit 1 ("(empty)" message) when not installed.
            // We parse a best-effort "<pkg>@<version>" out of stdout.
            var pb = new ProcessBuilder("npm", "ls", "-g", "--depth=0", npmPackage)
                    .redirectErrorStream(true);
            try {
                Process p = pb.start();
                String out = new String(p.getInputStream().readAllBytes());
                boolean done = p.waitFor(NPM_LIST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (!done) {
                    p.destroyForcibly();
                    return new NpmListResult(false, null);
                }
                if (p.exitValue() != 0) {
                    return new NpmListResult(false, null);
                }
                // Parse "<pkg>@<version>" out of npm's tree output.
                String version = null;
                String prefix = npmPackage + "@";
                for (var line : out.split("\\r?\\n")) {
                    int idx = line.indexOf(prefix);
                    if (idx >= 0) {
                        String tail = line.substring(idx + prefix.length()).trim();
                        // Trim trailing whitespace / sub-tree markers.
                        int sp = 0;
                        while (sp < tail.length()
                                && !Character.isWhitespace(tail.charAt(sp))) sp++;
                        version = tail.substring(0, sp);
                        break;
                    }
                }
                return new NpmListResult(true, version);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("npm ls interrupted", e);
            }
        }
    }

    /** Default {@link Archiver} — shells out to {@code tar} via {@link ProcessBuilder}. */
    public static final class TarGzArchiver implements Archiver {
        @Override public void extract(Path archive, Path targetDir) throws IOException {
            Files.createDirectories(targetDir);
            ProcessBuilder pb = new ProcessBuilder(
                    "tar", "-xzf", archive.toAbsolutePath().toString(),
                    "-C", targetDir.toAbsolutePath().toString())
                    .redirectErrorStream(true);
            try {
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes());
                int rc = p.waitFor();
                if (rc != 0) {
                    throw new IOException("tar -xzf failed (rc=" + rc + "): " + output);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("tar interrupted", e);
            }
        }
    }

    /** Surfaced to the steward via {@link CodingCli}; message must be actionable. */
    public static final class InstallException extends IOException {
        public InstallException(String message) { super(message); }
        public InstallException(String message, Throwable cause) { super(message, cause); }
    }
}
