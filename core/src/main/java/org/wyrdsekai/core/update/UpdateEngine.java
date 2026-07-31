package org.wyrdsekai.core.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.AppVersion;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Core update engine: download, stage, verify, swap, restart, rollback.
 *
 * The update flow:
 * 1. Download package from peer or channel
 * 2. Verify SHA-256
 * 3. Extract to staging directory
 * 4. Pre-update health check (disk space)
 * 5. Stop server gracefully (via PID file)
 * 6. Swap lib/ → lib.prev/, staging/lib/ → lib/
 * 7. Restart server
 * 8. Post-update health check (60s window)
 * 9. Auto-rollback if health fails
 */
public final class UpdateEngine {

    private static final Logger log = LoggerFactory.getLogger(UpdateEngine.class);

    private final Path installDir;      // ~/.wyrdsekai/
    private final UpdateConfig config;
    private final HttpClient http;

    // State
    private volatile String failedVersion;  // set if an update failed — advertised to mesh
    private volatile Instant lastUpdateAttempt;
    private volatile UpdateState state = UpdateState.IDLE;
    private final List<UpdateEvent> history = Collections.synchronizedList(new ArrayList<>());

    public enum UpdateState {
        IDLE, DOWNLOADING, STAGING, SWAPPING, RESTARTING, HEALTH_CHECK, ROLLING_BACK, FAILED
    }

    public record UpdateEvent(Instant timestamp, String fromVersion, String toVersion,
                              String action, boolean success, String details) {}

    public UpdateEngine(Path installDir, UpdateConfig config) {
        this.installDir = installDir;
        this.config = config;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    // --- Public API ---

    public UpdateState state() { return state; }
    public String failedVersion() { return failedVersion; }
    public Instant lastUpdateAttempt() { return lastUpdateAttempt; }
    public List<UpdateEvent> history() { return Collections.unmodifiableList(history); }

    /**
     * Apply an update from a manifest. Downloads package, stages, swaps, restarts.
     *
     * @param manifest The release manifest to install
     * @param sourceUrl URL to download the package from (peer or channel)
     * @return Result of the update attempt
     */
    public UpdateResult apply(ReleaseManifest manifest, String sourceUrl) {
        var currentVersion = AppVersion.get().version();
        lastUpdateAttempt = Instant.now();

        // Pre-checks
        if (!manifest.isNewerThan(currentVersion)) {
            return fail(currentVersion, manifest.version(), "Not newer than current version");
        }
        if (!manifest.canUpgradeFrom(currentVersion)) {
            return fail(currentVersion, manifest.version(),
                "Cannot upgrade from " + currentVersion + " (minVersion=" + manifest.minVersion() + ")");
        }

        var pkg = manifest.packages() != null ? manifest.packages().get("universal") : null;
        if (pkg == null && sourceUrl == null) {
            return fail(currentVersion, manifest.version(), "No package URL available");
        }

        var downloadUrl = sourceUrl != null ? sourceUrl : pkg.url();

        try {
            // Step 1: Download
            state = UpdateState.DOWNLOADING;
            var downloadPath = installDir.resolve("staging").resolve("download.tar.gz");
            Files.createDirectories(downloadPath.getParent());
            log.info("[Update] Downloading v{} from {}", manifest.version(), downloadUrl);
            download(downloadUrl, downloadPath);

            // Step 2: Verify SHA-256
            if (pkg != null && pkg.sha256() != null) {
                var actualSha = sha256(downloadPath);
                if (!pkg.sha256().equals(actualSha)) {
                    return fail(currentVersion, manifest.version(),
                        "SHA-256 mismatch: expected " + pkg.sha256() + ", got " + actualSha);
                }
                log.info("[Update] SHA-256 verified");
            }

            // Step 3: Extract to staging
            state = UpdateState.STAGING;
            var stagingDir = installDir.resolve("staging").resolve("extracted");
            deleteRecursive(stagingDir);
            Files.createDirectories(stagingDir);
            extractTarGz(downloadPath, stagingDir);
            log.info("[Update] Extracted to staging");

            // Step 4: Pre-update checks
            var stagingLib = stagingDir.resolve("lib");
            if (!Files.isDirectory(stagingLib)) {
                return fail(currentVersion, manifest.version(), "Staging has no lib/ directory");
            }
            long freeSpace = installDir.toFile().getUsableSpace();
            long neededSpace = directorySize(stagingLib) * 2; // need space for prev + new
            if (freeSpace < neededSpace) {
                return fail(currentVersion, manifest.version(),
                    "Insufficient disk space: need " + neededSpace + ", have " + freeSpace);
            }

            // Step 5: Swap
            state = UpdateState.SWAPPING;
            var libDir = installDir.resolve("lib");
            var libPrev = installDir.resolve("lib.prev");

            // Remove old backup
            deleteRecursive(libPrev);

            // Current → prev
            if (Files.isDirectory(libDir)) {
                Files.move(libDir, libPrev);
            }

            // Staging → current
            Files.move(stagingLib, libDir);

            // Also swap bin/ and scripts/ if present in staging
            swapIfPresent(stagingDir, "bin");
            swapIfPresent(stagingDir, "scripts");

            log.info("[Update] Swapped lib/ (prev saved to lib.prev/)");

            // Step 6: Cache version
            cacheVersion(manifest.version(), libDir);

            // Record success
            state = UpdateState.IDLE;
            var event = new UpdateEvent(Instant.now(), currentVersion, manifest.version(),
                "update", true, "Staged and swapped successfully");
            history.add(event);

            // Cleanup staging
            deleteRecursive(installDir.resolve("staging"));

            log.info("[Update] v{} → v{} — swap complete. Restart required.",
                currentVersion, manifest.version());

            return new UpdateResult(true, currentVersion, manifest.version(),
                "Update staged. Restart server to apply.", null);

        } catch (Exception e) {
            state = UpdateState.FAILED;
            failedVersion = manifest.version();
            return fail(currentVersion, manifest.version(), "Update failed: " + e.getMessage());
        }
    }

    /**
     * Rollback to the previous version (lib.prev/).
     */
    public UpdateResult rollback() {
        var currentVersion = AppVersion.get().version();
        var libDir = installDir.resolve("lib");
        var libPrev = installDir.resolve("lib.prev");

        if (!Files.isDirectory(libPrev)) {
            return new UpdateResult(false, currentVersion, null,
                "No previous version available (lib.prev/ not found)", null);
        }

        try {
            state = UpdateState.ROLLING_BACK;

            // Swap back: lib → lib.failed, lib.prev → lib
            var libFailed = installDir.resolve("lib.failed");
            deleteRecursive(libFailed);
            if (Files.isDirectory(libDir)) {
                Files.move(libDir, libFailed);
            }
            Files.move(libPrev, libDir);

            state = UpdateState.IDLE;
            failedVersion = currentVersion; // mark current as failed

            var event = new UpdateEvent(Instant.now(), currentVersion, "previous",
                "rollback", true, "Rolled back to previous version");
            history.add(event);

            log.info("[Update] Rolled back from v{}. Restart required.", currentVersion);

            return new UpdateResult(true, currentVersion, "previous",
                "Rollback staged. Restart server to apply.", null);

        } catch (Exception e) {
            state = UpdateState.FAILED;
            return new UpdateResult(false, currentVersion, null,
                "Rollback failed: " + e.getMessage(), e);
        }
    }

    /**
     * Post-update health check. Call this after restart.
     * Returns true if healthy, false if rollback is recommended.
     */
    public boolean postUpdateHealthCheck(String healthUrl) {
        state = UpdateState.HEALTH_CHECK;
        var deadline = Instant.now().plusSeconds(60);
        int checks = 0;

        while (Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(5000);
                checks++;

                var resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200 && resp.body().contains("UP")) {
                    log.info("[Update] Post-update health check passed (check #{}, {}s)",
                        checks, checks * 5);
                    state = UpdateState.IDLE;
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("[Update] Health check #{} failed: {}", checks, e.getMessage());
            }
        }

        log.warn("[Update] Post-update health check FAILED after {} checks", checks);
        state = UpdateState.IDLE;
        return false;
    }

    /**
     * List cached versions available for rollback.
     */
    public List<String> cachedVersions() {
        var versionsDir = installDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) return List.of();
        try (var stream = Files.list(versionsDir)) {
            return stream.filter(Files::isDirectory)
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    // --- Internal ---

    private void download(String url, Path target) throws IOException, InterruptedException {
        if (url.startsWith("file://")) {
            Files.copy(Path.of(URI.create(url)), target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            var resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET().build(), HttpResponse.BodyHandlers.ofFile(target));
            if (resp.statusCode() != 200) {
                throw new IOException("Download failed: HTTP " + resp.statusCode());
            }
        }
    }

    private void extractTarGz(Path archive, Path targetDir) throws IOException {
        try (var fis = new FileInputStream(archive.toFile());
             var gis = new GZIPInputStream(fis);
             var bis = new BufferedInputStream(gis)) {

            var header = new byte[512];
            while (true) {
                int read = bis.readNBytes(header, 0, 512);
                if (read < 512) break;

                // Check for end-of-archive (all zeros)
                boolean allZero = true;
                for (byte b : header) {
                    if (b != 0) { allZero = false; break; }
                }
                if (allZero) break;

                // Parse name (0-99)
                var name = new String(header, 0, 100).trim();
                if (name.isEmpty()) break;

                // Parse size (124-135, octal)
                var sizeStr = new String(header, 124, 12).trim();
                long size = sizeStr.isEmpty() ? 0 : Long.parseLong(sizeStr, 8);

                // Type (156): '0' or '\0' = regular file, '5' = directory
                byte type = header[156];

                var targetPath = targetDir.resolve(name);
                Files.createDirectories(targetPath.getParent());

                if (type == '5') {
                    Files.createDirectories(targetPath);
                } else if (size > 0) {
                    var data = bis.readNBytes((int) size);
                    Files.write(targetPath, data);

                    // Skip padding to 512-byte boundary
                    int remainder = (int) (size % 512);
                    if (remainder > 0) {
                        bis.readNBytes(512 - remainder);
                    }
                }
            }
        }
    }

    private void swapIfPresent(Path stagingDir, String dirName) throws IOException {
        var staged = stagingDir.resolve(dirName);
        if (!Files.isDirectory(staged)) return;

        var current = installDir.resolve(dirName);
        var prev = installDir.resolve(dirName + ".prev");
        deleteRecursive(prev);
        if (Files.isDirectory(current)) {
            Files.move(current, prev);
        }
        Files.move(staged, current);
    }

    private void cacheVersion(String version, Path libDir) {
        try {
            var versionsDir = installDir.resolve("versions").resolve(version).resolve("lib");
            Files.createDirectories(versionsDir.getParent());
            // Copy current lib as cache (for rollback to specific version)
            copyRecursive(libDir, versionsDir);
            pruneVersionCache();
        } catch (Exception e) {
            log.debug("[Update] Could not cache version {}: {}", version, e.getMessage());
        }
    }

    private void pruneVersionCache() {
        var versionsDir = installDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) return;
        try (var stream = Files.list(versionsDir)) {
            var versions = stream.filter(Files::isDirectory)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
            // Keep only the configured number of versions
            if (versions.size() > config.versionCacheSize()) {
                for (int i = 0; i < versions.size() - config.versionCacheSize(); i++) {
                    deleteRecursive(versions.get(i));
                }
            }
        } catch (Exception e) {
            log.debug("[Update] Version cache pruning failed: {}", e.getMessage());
        }
    }

    private String sha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var is = new FileInputStream(file.toFile())) {
                var buf = new byte[8192];
                int read;
                while ((read = is.read(buf)) != -1) {
                    digest.update(buf, 0, read);
                }
            }
            var hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IOException("SHA-256 failed", e);
        }
    }

    private long directorySize(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); }
                catch (IOException e) { return 0; }
            }).sum();
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); }
                catch (IOException ignored) {}
            });
        }
    }

    private static void copyRecursive(Path src, Path dst) throws IOException {
        try (var walk = Files.walk(src)) {
            walk.forEach(source -> {
                var target = dst.resolve(src.relativize(source));
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private UpdateResult fail(String from, String to, String reason) {
        var event = new UpdateEvent(Instant.now(), from, to, "update", false, reason);
        history.add(event);
        log.warn("[Update] Failed: {}", reason);
        return new UpdateResult(false, from, to, reason, null);
    }

    public record UpdateResult(boolean success, String fromVersion, String toVersion,
                               String message, Exception error) {}
}
