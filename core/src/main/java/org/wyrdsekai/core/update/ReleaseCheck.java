package org.wyrdsekai.core.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.AppVersion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The released versions of Wyrdsekai and its two siblings, read from GitHub and remembered for a
 * day, beside what this node runs. The update itself is the platform installer's job
 * ({@code wyrd update now}); this class only knows what is current and what is available.
 *
 * <p>Modes ({@code WYRDSEKAI_UPDATE}): {@code check} (default) says when a newer release exists —
 * in {@code wyrd status}, {@code wyrd doctor} and {@code /api/update/status}; {@code auto} lets the
 * node install it at a quiet moment inside its window ({@link SelfUpdate}); {@code off} asks
 * nothing of GitHub. A dev build ({@code 0.2.3~dev17}, {@code 0.1.0-SNAPSHOT}) is never
 * auto-updated: what runs on a dev box is what the developer put there.
 */
public final class ReleaseCheck {

    private static final Logger log = LoggerFactory.getLogger(ReleaseCheck.class);
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();

    public static final String REPO = "Wyrdsekai/wyrdsekai";
    public static final String CODEZAIKU_REPO = "Wyrdsekai/codezaiku";
    public static final String RESEARCHZOSHO_REPO = "Wyrdsekai/researchzosho";
    public static final String MODE_ENV = "WYRDSEKAI_UPDATE";
    static final Duration CACHE_TTL = Duration.ofHours(24);

    private ReleaseCheck() {}

    /** Where the environment is read from; replaceable in tests. */
    static volatile Function<String, String> env = System::getenv;
    /** Where the latest release is read from; replaceable in tests (GitHub otherwise). */
    static volatile Function<String, Optional<String>> latestSource = ReleaseCheck::latest;

    /** {@code check} | {@code auto} | {@code off}. */
    public static String mode() {
        var m = env.apply(MODE_ENV);
        m = m == null ? "" : m.trim().toLowerCase(Locale.ROOT);
        return m.equals("auto") || m.equals("off") ? m : "check";
    }

    /**
     * The version this node runs: the {@code VERSION} file the packagers ship beside the
     * program (the package version, {@code 0.3.0} or {@code 0.2.3~dev17}), else the build's own
     * ({@code 0.1.0-SNAPSHOT}), which every dev checkout reports.
     */
    public static String installedVersion(Path installRoot) {
        if (installRoot != null) {
            try {
                var f = installRoot.resolve("VERSION");
                if (Files.isRegularFile(f)) {
                    var v = Files.readString(f, StandardCharsets.UTF_8).strip();
                    if (!v.isEmpty()) return v;
                }
            } catch (IOException ignored) { }
        }
        return AppVersion.get().version();
    }

    /** A plain {@code X.Y.Z} release, as opposed to a dev or snapshot build. */
    public static boolean isRelease(String version) {
        return version != null && version.matches("\\d+\\.\\d+\\.\\d+");
    }

    /** True when {@code latest} is a release newer than {@code installed} by semver, ignoring suffixes. */
    public static boolean isNewer(String installed, String latest) {
        if (!isRelease(latest) || installed == null || installed.isBlank()) return false;
        var base = installed.replace('~', '-');
        int dash = base.indexOf('-');
        if (dash >= 0) base = base.substring(0, dash);
        return ReleaseManifest.compareVersions(latest, base) > 0;
    }

    /** The latest release tag of {@code repo}, without its leading {@code v}; empty when GitHub cannot be reached. */
    public static Optional<String> latest(String repo) {
        try {
            var req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repo + "/releases/latest"))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Wyrdsekai/" + AppVersion.get().version())
                .header("Accept", "application/vnd.github+json").GET().build();
            var r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) { log.debug("[release] {} → HTTP {}", repo, r.statusCode()); return Optional.empty(); }
            JsonNode node = M.readTree(r.body());
            var tag = node.path("tag_name").asText("");
            if (tag.startsWith("v")) tag = tag.substring(1);
            return isRelease(tag) ? Optional.of(tag) : Optional.empty();
        } catch (Exception e) {
            log.debug("[release] {} unreachable: {}", repo, e.toString());
            return Optional.empty();
        }
    }

    /**
     * The latest release of {@code repo} as remembered under {@code cacheDir} (a day old at most);
     * refreshed from GitHub when stale or absent, in this thread. Empty when neither knows.
     */
    public static Optional<String> latestCached(String repo, Path cacheDir) {
        var f = cacheFile(repo, cacheDir);
        try {
            if (f != null && Files.isRegularFile(f)) {
                var age = Duration.between(Files.getLastModifiedTime(f).toInstant(), Instant.now());
                var v = Files.readString(f, StandardCharsets.UTF_8).strip();
                if (age.compareTo(CACHE_TTL) < 0 && isRelease(v)) return Optional.of(v);
            }
        } catch (IOException ignored) { }
        var fresh = latestSource.apply(repo);
        if (fresh.isPresent() && f != null) {
            try { Files.createDirectories(f.getParent()); Files.writeString(f, fresh.get(), StandardCharsets.UTF_8); }
            catch (IOException e) { log.debug("[release] could not cache {}: {}", f, e.toString()); }
        } else if (fresh.isEmpty() && f != null && Files.isRegularFile(f)) {
            try { var v = Files.readString(f, StandardCharsets.UTF_8).strip(); if (isRelease(v)) return Optional.of(v); } catch (IOException ignored) { }
        }
        return fresh;
    }

    public static Path cacheFile(String repo, Path cacheDir) {
        if (cacheDir == null) return null;
        var slug = repo.substring(repo.indexOf('/') + 1).toLowerCase(Locale.ROOT);
        return cacheDir.resolve("latest-" + slug + ".txt");
    }

    /** The release artifact this platform installs, by the names the release ships. */
    public static String artifactName(String version, String os, String arch, boolean hasDpkg) {
        var o = os == null ? "" : os.toLowerCase(Locale.ROOT);
        if (o.contains("win")) return "Wyrdsekai-" + version + ".msi";
        if (o.contains("mac") || o.contains("darwin")) return "Wyrdsekai-" + version + ".pkg";
        if (hasDpkg && (arch == null || arch.contains("64"))) return "wyrdsekai_" + version + "_amd64.deb";
        return "wyrdsekai-" + version + ".tar.gz";
    }

    public static String downloadBase(String version) {
        return "https://github.com/" + REPO + "/releases/download/v" + version;
    }

    /** One line for a status listing: what runs, what is out, whether that is newer. */
    public record Status(String installed, String latest, boolean updateAvailable, String mode) {
        public Map<String, Object> toMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("installed", installed);
            m.put("latest", latest == null ? "" : latest);
            m.put("updateAvailable", updateAvailable);
            m.put("mode", mode);
            return m;
        }
    }

    public static Status status(Path installRoot, Path cacheDir) {
        var installed = installedVersion(installRoot);
        if (mode().equals("off")) return new Status(installed, null, false, "off");
        var latest = latestCached(REPO, cacheDir).orElse(null);
        return new Status(installed, latest, isNewer(installed, latest), mode());
    }
}
