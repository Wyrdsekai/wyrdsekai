package org.wyrdsekai.core.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.AppVersion;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * Polls a release channel URL for new versions.
 * The channel serves a signed {@link ReleaseManifest} as JSON.
 *
 * Supports:
 * - HTTP/HTTPS URLs (remote channel)
 * - file:// URLs (air-gapped / local)
 * - "mesh://" pseudo-URL (disable channel polling, rely on mesh heartbeats only)
 */
public final class UpdateChannelPoller implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UpdateChannelPoller.class);

    private final String channelUrl;
    private final Duration checkInterval;
    private final String releasePublicKey; // base64 Ed25519 public key, nullable = skip verification
    private final HttpClient http;
    private final ScheduledExecutorService scheduler;

    private volatile ReleaseManifest latestManifest;
    private volatile Instant lastCheck;
    private volatile String lastError;

    public UpdateChannelPoller(String channelUrl, Duration checkInterval, String releasePublicKey) {
        this.channelUrl = channelUrl;
        this.checkInterval = checkInterval;
        this.releasePublicKey = releasePublicKey;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "update-channel-poller");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start periodic polling. First check runs after initialDelay.
     */
    public void start(Duration initialDelay) {
        if (channelUrl == null || channelUrl.isEmpty() || "mesh://".equals(channelUrl)) {
            log.info("[Update] Channel polling disabled (url={})", channelUrl);
            return;
        }
        scheduler.scheduleAtFixedRate(this::poll, initialDelay.toSeconds(),
            checkInterval.toSeconds(), TimeUnit.SECONDS);
        log.info("[Update] Channel poller started: {} (interval={})", channelUrl, checkInterval);
    }

    /**
     * Poll the channel once (synchronous). Returns the manifest if a newer version is available.
     */
    public Optional<ReleaseManifest> check() {
        poll();
        if (latestManifest != null && latestManifest.isNewerThan(AppVersion.get().version())) {
            return Optional.of(latestManifest);
        }
        return Optional.empty();
    }

    /**
     * Get the latest manifest from the last poll, regardless of whether it's newer.
     */
    public ReleaseManifest latestManifest() { return latestManifest; }
    public Instant lastCheck() { return lastCheck; }
    public String lastError() { return lastError; }
    public String channelUrl() { return channelUrl; }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    // --- Internal ---

    private void poll() {
        try {
            lastCheck = Instant.now();
            lastError = null;

            String json;
            if (channelUrl.startsWith("file://")) {
                var path = Path.of(URI.create(channelUrl));
                json = Files.readString(path);
            } else {
                var request = HttpRequest.newBuilder()
                    .uri(URI.create(channelUrl))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    lastError = "HTTP " + response.statusCode();
                    log.warn("[Update] Channel returned {}: {}", response.statusCode(), channelUrl);
                    return;
                }
                json = response.body();
            }

            var manifest = ReleaseManifest.fromJson(json);

            // Verify signature if we have a release key
            if (releasePublicKey != null && !releasePublicKey.isEmpty()) {
                if (!manifest.verify(releasePublicKey)) {
                    lastError = "Invalid signature";
                    log.warn("[Update] Manifest signature verification FAILED for v{}", manifest.version());
                    return;
                }
            }

            latestManifest = manifest;

            if (manifest.isNewerThan(AppVersion.get().version())) {
                log.info("[Update] New version available: v{} (current: v{}, breaking={})",
                    manifest.version(), AppVersion.get().version(), manifest.breaking());
            } else {
                log.debug("[Update] Up to date (channel: v{}, current: v{})",
                    manifest.version(), AppVersion.get().version());
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("[Update] Channel poll failed: {}", e.getMessage());
        }
    }
}
