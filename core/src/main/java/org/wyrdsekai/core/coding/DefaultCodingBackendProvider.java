package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.scripting.api.CodingBackendProvider;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Phase 1a default implementation of {@link CodingBackendProvider}.
 *
 * <p>Returns {@code "codeplane"} when the CodePlane backend is registered
 * and healthy, else {@code null}.
 * step 17 — Phase 1b replaces this with a GraalJS policy script.</p>
 */
public final class DefaultCodingBackendProvider implements CodingBackendProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultCodingBackendProvider.class);

    private final BackendRegistry registry;

    public DefaultCodingBackendProvider(BackendRegistry registry) {
        this.registry = registry != null ? registry : BackendRegistry.get();
    }

    // Health probes shell out to `<bin> --version` for CLI backends (Pi /
    // OpenCode / OpenHands / Goose). A cold subprocess spawn under a loaded
    // JVM routinely exceeds a few hundred ms, so the probe timeout must be
    // generous — a too-tight one (the original 250ms) silently reports every
    // subprocess backend as unavailable and suppresses Workshop narration.
    // Results are cached briefly so room-narration probes (one per backend on
    // every onEnter/look) don't re-pay the cost. The cache is static because
    // RoomScriptEngine builds a fresh provider instance per room.
    private static final long PROBE_TIMEOUT_MS = 2500;
    private static final long POSITIVE_TTL_MS = 30_000;
    private static final long NEGATIVE_TTL_MS = 5_000;

    private record Health(boolean ok, long expiresAt) {}

    private static final ConcurrentHashMap<String, Health> HEALTH_CACHE =
        new ConcurrentHashMap<>();

    /**
     * Cached, generously-timed health probe. Returns false when the backend is
     * unregistered or its probe fails/times out. Positive results are cached
     * longer than negatives so a backend that is still warming up recovers
     * quickly while a steady-state "available" answer stays cheap.
     */
    private boolean probeHealthy(String name) {
        long now = System.currentTimeMillis();
        var cached = HEALTH_CACHE.get(name);
        if (cached != null && cached.expiresAt() > now) return cached.ok();

        boolean healthy = false;
        var backend = registry.backendFor(name).orElse(null);
        if (backend != null) {
            try {
                healthy = Boolean.TRUE.equals(backend.healthCheck()
                    .toCompletableFuture()
                    .get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                log.debug("health probe failed for {}: {}", name, e.getMessage());
            }
        }
        HEALTH_CACHE.put(name, new Health(healthy, now + (healthy ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS)));
        return healthy;
    }

    @Override
    public String backendFor(String entityId, String taskType, String taskDescription) {
        // Phase 1a is intentionally minimal: prefer codeplane if healthy.
        return probeHealthy(CodePlaneBackend.NAME) ? CodePlaneBackend.NAME : null;
    }

    @Override
    public boolean backendAvailable(String name) {
        if (name == null || name.isBlank()) return false;
        return probeHealthy(name);
    }
}
