package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.lang.management.ManagementFactory;
import org.wyrdsekai.common.model.AppVersion;
import org.wyrdsekai.core.update.UpdateChannelPoller;
import org.wyrdsekai.core.update.SelfUpdate;
import org.wyrdsekai.core.update.ReleaseCheck;
import org.wyrdsekai.core.update.UpdateConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP endpoints for the mesh update protocol.
 *
 *   GET /api/update/status    — current version, channel status, peer versions
 *   GET /api/update/manifest  — serve our release manifest to mesh peers
 *   GET /api/update/package   — download the universal update package
 *   GET /api/update/artifact/{name} — the release installer this node kept, for a household peer
 *   GET /api/update/sums/{version}  — the release's SHA256SUMS this node kept, for a household peer
 *   GET /api/update/health    — post-update health status
 */
public final class UpdateRoutes {

    private final UpdateConfig config;
    private final UpdateChannelPoller poller; // nullable if polling disabled
    private final SelfUpdate selfUpdate;       // nullable in a source checkout
    private final Path installRoot;            // nullable in a source checkout
    private final Path dataDir;
    private volatile Path packagePath; // set after build

    public UpdateRoutes(UpdateConfig config, UpdateChannelPoller poller) {
        this(config, poller, null, null, null);
    }

    public UpdateRoutes(UpdateConfig config, UpdateChannelPoller poller, SelfUpdate selfUpdate, Path installRoot, Path dataDir) {
        this.config = config;
        this.poller = poller;
        this.selfUpdate = selfUpdate;
        this.installRoot = installRoot;
        this.dataDir = dataDir;
    }

    /** Set the path to the built package (called after `wyrdsekai update --publish`). */
    public void setPackagePath(Path path) {
        this.packagePath = path;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/update/status", this::handleStatus);
        app.post("/api/update/check", this::handleCheck);
        app.get("/api/update/manifest", this::handleManifest);
        app.get("/api/update/package", this::handlePackageDownload);
        app.get("/api/update/artifact/{name}", this::handleArtifact);
        app.get("/api/update/sums/{version}", this::handleSums);
        app.get("/api/update/health", this::handleHealth);
    }

    /** A release artifact's file name as the releases ship them, and nothing else: no path in it. */
    static final java.util.regex.Pattern ARTIFACT =
        java.util.regex.Pattern.compile("(wyrdsekai_[0-9][0-9A-Za-z.~+-]*_amd64\\.deb|wyrdsekai-[0-9][0-9A-Za-z.~+-]*\\.tar\\.gz|Wyrdsekai-[0-9][0-9A-Za-z.~+-]*\\.(pkg|msi))");
    static final java.util.regex.Pattern VERSION = java.util.regex.Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");

    /** The folder {@code wyrd update now} downloads into, and {@code wyrd update <file>} keeps a copy in. */
    Path updatesDir() { return dataDir == null ? null : dataDir.resolve("updates"); }

    /**
     * Nodes update each other through the mesh: a household peer that has learned (from the
     * heartbeats) that this node runs a newer release takes the installer from here instead of the
     * internet. The bytes are public release files; the peer checks them against the release's own
     * checksums, never against this node's word.
     */
    private void handleArtifact(Context ctx) {
        var name = ctx.pathParam("name");
        var dir = updatesDir();
        if (dir == null || !ARTIFACT.matcher(name).matches()) { ctx.status(404).json(Map.of("error", "no such artifact")); return; }
        var f = dir.resolve(name);
        if (!Files.isRegularFile(f)) { ctx.status(404).json(Map.of("error", "this node does not hold " + name)); return; }
        try {
            ctx.header("Content-Type", "application/octet-stream");
            ctx.header("Content-Length", Long.toString(Files.size(f)));
            ctx.header("Content-Disposition", "attachment; filename=\"" + name + "\"");
            ctx.result(Files.newInputStream(f));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "could not serve " + name + ": " + e.getMessage()));
        }
    }

    /** The release's SHA256SUMS as this node fetched it from the release, so a peer can see what the release says it shipped. */
    private void handleSums(Context ctx) {
        var version = ctx.pathParam("version");
        var dir = updatesDir();
        if (dir == null || !VERSION.matcher(version).matches()) { ctx.status(404).json(Map.of("error", "no such version")); return; }
        var f = dir.resolve("SHA256SUMS." + version);
        if (!Files.isRegularFile(f)) { ctx.status(404).json(Map.of("error", "this node does not hold the checksums of " + version)); return; }
        try {
            ctx.header("Content-Type", "text/plain; charset=utf-8");
            ctx.result(Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void handleStatus(Context ctx) {
        var appVer = AppVersion.get();
        var status = new LinkedHashMap<String, Object>();
        status.put("version", appVer.version());
        status.put("buildHash", appVer.buildHash());
        status.put("wireProtocol", appVer.wireProtocol());
        status.put("buildTimestamp", appVer.buildTimestamp().toString());

        // The release this node runs, the latest one published, and what the self-updater
        // last decided (ReleaseCheck / SelfUpdate) — what `wyrd status` and `wyrd doctor` read.
        var release = ReleaseCheck.status(installRoot, dataDir);
        status.put("release", release.toMap());
        if (selfUpdate != null) status.put("selfUpdate", selfUpdate.state());
        status.put("siblings", siblings());

        // Update config
        var configMap = new LinkedHashMap<String, Object>();
        configMap.put("channel", config.channelUrl() != null ? config.channelUrl() : "");
        configMap.put("policy", config.policy().name().toLowerCase());
        configMap.put("checkInterval", config.checkInterval().toString());
        configMap.put("nodeRole", config.nodeRole());
        configMap.put("enabled", config.enabled());
        if (config.pinnedVersion() != null) {
            configMap.put("pinnedVersion", config.pinnedVersion());
        }
        status.put("config", configMap);

        // Channel status
        if (poller != null) {
            var channelStatus = new LinkedHashMap<String, Object>();
            channelStatus.put("url", poller.channelUrl());
            channelStatus.put("lastCheck", poller.lastCheck() != null ? poller.lastCheck().toString() : null);
            channelStatus.put("lastError", poller.lastError());
            var latest = poller.latestManifest();
            if (latest != null) {
                channelStatus.put("latestVersion", latest.version());
                channelStatus.put("updateAvailable", latest.isNewerThan(appVer.version()));
                channelStatus.put("breaking", latest.breaking());
                channelStatus.put("changelog", latest.changelog());
            }
            status.put("channel", channelStatus);
        }

        ctx.json(status);
    }

    /** Ask GitHub now (not the day-old cache) and, in auto mode, take the turn the schedule would. */
    private void handleCheck(Context ctx) {
        if (ReleaseCheck.mode().equals("off")) { ctx.json(Map.of("ok", false, "reason", "WYRDSEKAI_UPDATE=off")); return; }
        try { Files.deleteIfExists(ReleaseCheck.cacheFile(ReleaseCheck.REPO, dataDir)); } catch (Exception ignored) { }
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("release", ReleaseCheck.status(installRoot, dataDir).toMap());
        if (selfUpdate != null) out.put("decision", selfUpdate.tick());
        ctx.json(out);
    }

    /** The latest release of each sibling program, so a household can see when its librarian or coder is behind. */
    private Map<String, Object> siblings() {
        var m = new LinkedHashMap<String, Object>();
        if (ReleaseCheck.mode().equals("off") || dataDir == null) return m;
        for (var repo : new String[] {ReleaseCheck.CODEZAIKU_REPO, ReleaseCheck.RESEARCHZOSHO_REPO}) {
            var id = repo.substring(repo.indexOf('/') + 1);
            m.put(id, Map.of("latest", ReleaseCheck.latestCached(repo, dataDir).orElse("")));
        }
        return m;
    }

    private void handleManifest(Context ctx) {
        // Serve our current version as a manifest for mesh peers
        if (poller != null && poller.latestManifest() != null) {
            ctx.json(poller.latestManifest());
        } else {
            // No channel manifest — return our current version info
            var appVer = AppVersion.get();
            ctx.json(Map.of(
                "version", appVer.version(),
                "wireProtocol", appVer.wireProtocol(),
                "buildHash", appVer.buildHash(),
                "buildTimestamp", appVer.buildTimestamp().toString()
            ));
        }
    }

    private void handlePackageDownload(Context ctx) {
        if (packagePath == null || !Files.exists(packagePath)) {
            ctx.status(404).json(Map.of("error", "No package available. Run: wyrdsekai update --publish"));
            return;
        }
        ctx.header("Content-Type", "application/gzip");
        ctx.header("Content-Disposition", "attachment; filename=\"" + packagePath.getFileName() + "\"");
        try {
            ctx.result(Files.newInputStream(packagePath));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to serve package: " + e.getMessage()));
        }
    }

    private void handleHealth(Context ctx) {
        // Post-update health check — used by mesh peers to verify seed is stable.
        // The meshTestMarker changes with each version — proves new code is running.
        var appVer = AppVersion.get();
        var uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        ctx.json(Map.of(
            "version", appVer.version(),
            "wireProtocol", appVer.wireProtocol(),
            "uptimeMs", uptime,
            "healthy", true,
            "meshTestMarker", "wyrdsekai-" + appVer.version() + "-" + appVer.buildHash()
        ));
    }
}
