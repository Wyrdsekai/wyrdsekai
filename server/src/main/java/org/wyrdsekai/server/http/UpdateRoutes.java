package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.lang.management.ManagementFactory;
import org.wyrdsekai.common.model.AppVersion;
import org.wyrdsekai.core.update.UpdateChannelPoller;
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
 *   GET /api/update/health    — post-update health status
 */
public final class UpdateRoutes {

    private final UpdateConfig config;
    private final UpdateChannelPoller poller; // nullable if polling disabled
    private volatile Path packagePath; // set after build

    public UpdateRoutes(UpdateConfig config, UpdateChannelPoller poller) {
        this.config = config;
        this.poller = poller;
    }

    /** Set the path to the built package (called after `wyrdsekai update --publish`). */
    public void setPackagePath(Path path) {
        this.packagePath = path;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/update/status", this::handleStatus);
        app.get("/api/update/manifest", this::handleManifest);
        app.get("/api/update/package", this::handlePackageDownload);
        app.get("/api/update/health", this::handleHealth);
    }

    private void handleStatus(Context ctx) {
        var appVer = AppVersion.get();
        var status = new LinkedHashMap<String, Object>();
        status.put("version", appVer.version());
        status.put("buildHash", appVer.buildHash());
        status.put("wireProtocol", appVer.wireProtocol());
        status.put("buildTimestamp", appVer.buildTimestamp().toString());

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
