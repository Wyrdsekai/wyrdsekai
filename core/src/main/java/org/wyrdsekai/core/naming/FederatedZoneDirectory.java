package org.wyrdsekai.core.naming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Federated-pull {@link ZoneDirectory} backend.
 *
 * <p>Periodically pulls {@code GET /api/directory/recent?limit=N} from
 * a set of peer URLs (federation partners or community rendezvous) and
 * caches the results locally. Zones you trust publish their view of the
 * directory; you absorb that view into your own cache. Equivalent to a
 * recursive DNS resolver's forward pool.</p>
 *
 * <h2>What this is not</h2>
 *
 * <p>Not a DHT. Not a full-text query engine. It's a thin
 * aggregator-over-HTTP that keeps your local directory fresh with what
 * your peers have seen. {@link #publish} and {@link #unpublish} are
 * no-ops — publishing happens via the rendezvous or
 * {@code .well-known} paths.</p>
 *
 * <h2>Peer discovery</h2>
 *
 * <p>Peer URLs come from a {@link Supplier} so the source can evolve
 * (env var list today, federation-agreement-derived URLs tomorrow)
 * without changing this class. Supplier is called on every pull tick
 * — operators can add/remove peers at runtime and the next tick picks
 * it up.</p>
 *
 * <h2>Resilience</h2>
 *
 * <p>One slow/dead peer does not block the others: peers are queried
 * sequentially with a per-peer timeout. Malformed responses are logged
 * at DEBUG and skipped. TTL eviction drops entries unseen for
 * {@link #DEFAULT_TTL} — self-heals when peers disappear from the
 * supplier list.</p>
 */
public final class FederatedZoneDirectory implements ZoneDirectory {

    private static final Logger log = LoggerFactory.getLogger(FederatedZoneDirectory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How often to sweep and pull from peers. Callers override in
     *  {@link #start(Duration)}. */
    public static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofMinutes(15);

    /** Drop cache entries not observed from any peer within this window. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(48);

    /** Per-peer HTTP timeout. */
    public static final Duration DEFAULT_PEER_TIMEOUT = Duration.ofSeconds(5);

    /** Max manifests to pull per peer per tick (bounds memory + bandwidth). */
    public static final int DEFAULT_PULL_LIMIT = 100;

    private final Supplier<Collection<String>> peerUrlsSupplier;
    private final HttpClient http;
    private final Duration peerTimeout;
    private final int pullLimit;
    private final ConcurrentMap<String, ZoneManifestV1> byDid = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> lastSeenAt = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    public FederatedZoneDirectory(Supplier<Collection<String>> peerUrlsSupplier) {
        this(peerUrlsSupplier,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
            DEFAULT_PEER_TIMEOUT, DEFAULT_PULL_LIMIT);
    }

    /** Full constructor — mostly for tests. */
    public FederatedZoneDirectory(
            Supplier<Collection<String>> peerUrlsSupplier,
            HttpClient http,
            Duration peerTimeout,
            int pullLimit) {
        this.peerUrlsSupplier = peerUrlsSupplier;
        this.http = http;
        this.peerTimeout = peerTimeout;
        this.pullLimit = pullLimit;
    }

    /**
     * Start the periodic pull loop. Idempotent — subsequent calls are
     * ignored. The loop runs on a single daemon thread.
     */
    public synchronized void start(Duration refreshInterval) {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "zone-directory-federated-pull");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::pullOnce,
            refreshInterval.toSeconds(), refreshInterval.toSeconds(), TimeUnit.SECONDS);
        log.info("FederatedZoneDirectory: pull loop started (interval={}s)",
            refreshInterval.toSeconds());
    }

    /** Stop the pull loop cleanly. Safe to call multiple times. */
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** Public for tests — trigger one pull cycle synchronously. */
    public void pullOnce() {
        Collection<String> peers;
        try {
            peers = peerUrlsSupplier.get();
        } catch (Exception e) {
            log.warn("peer URL supplier threw: {}", e.getMessage());
            return;
        }
        if (peers == null || peers.isEmpty()) return;
        for (var peer : peers) {
            try {
                pullFromPeer(peer);
            } catch (Exception e) {
                log.debug("pull from {} failed: {}", peer, e.getMessage());
            }
        }
        evictExpired();
    }

    private void pullFromPeer(String peerBaseUrl) {
        var base = peerBaseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        // Prefer the dedicated federated-pull endpoint (SPEC §5.1 #2).
        // Falls back to /recent on 404 for pre-V2 peers that don't serve it yet.
        var url = base + "/api/directory/known-manifests?hops=1&limit=" + pullLimit;

        var req = HttpRequest.newBuilder(URI.create(url))
            .timeout(peerTimeout)
            .header("accept", "application/json")
            .GET().build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("peer unreachable: {} ({})", url, e.getMessage());
            return;
        }
        if (resp.statusCode() == 404) {
            // Fall back to /recent for older peers that don't serve the
            // dedicated federated endpoint.
            var legacyUrl = base + "/api/directory/recent?limit=" + pullLimit;
            log.debug("peer {} has no /known-manifests; falling back to /recent", peerBaseUrl);
            var legacyReq = HttpRequest.newBuilder(URI.create(legacyUrl))
                .timeout(peerTimeout)
                .header("accept", "application/json")
                .GET().build();
            try {
                resp = http.send(legacyReq, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                log.debug("peer fallback unreachable: {} ({})", legacyUrl, e.getMessage());
                return;
            }
        }
        if (resp.statusCode() / 100 != 2) {
            log.debug("peer {} returned HTTP {}", url, resp.statusCode());
            return;
        }
        try {
            var node = MAPPER.readTree(resp.body());
            var manifests = node.path("manifests");
            if (!manifests.isArray()) return;
            int ingested = 0;
            long now = System.currentTimeMillis();
            for (var m : manifests) {
                try {
                    var manifest = MAPPER.treeToValue(m, ZoneManifestV1.class);
                    manifest.validate();  // reject malformed
                    var prior = byDid.get(manifest.did());
                    if (prior == null || preferNewer(manifest, prior)) {
                        byDid.put(manifest.did(), manifest);
                    }
                    lastSeenAt.put(manifest.did(), now);
                    ingested++;
                } catch (Exception e) {
                    log.debug("peer {} sent malformed manifest: {}", peerBaseUrl, e.getMessage());
                }
            }
            log.debug("pulled {} manifests from {}", ingested, peerBaseUrl);
        } catch (Exception e) {
            log.debug("peer {} response parse failed: {}", peerBaseUrl, e.getMessage());
        }
    }

    private void evictExpired() {
        long cutoff = System.currentTimeMillis() - DEFAULT_TTL.toMillis();
        var expired = new ArrayList<String>();
        for (var entry : lastSeenAt.entrySet()) {
            if (entry.getValue() < cutoff) expired.add(entry.getKey());
        }
        for (var did : expired) {
            byDid.remove(did);
            lastSeenAt.remove(did);
        }
        if (!expired.isEmpty()) {
            log.debug("evicted {} stale manifest(s)", expired.size());
        }
    }

    private static boolean preferNewer(ZoneManifestV1 candidate, ZoneManifestV1 incumbent) {
        var ca = candidate.refreshedAt() == null ? "" : candidate.refreshedAt();
        var ia = incumbent.refreshedAt() == null ? "" : incumbent.refreshedAt();
        int cmp = ca.compareTo(ia);
        if (cmp > 0) return true;
        if (cmp < 0) return false;
        return candidate.signature() != null && incumbent.signature() == null;
    }

    // ── ZoneDirectory interface ────────────────────────────────────────

    /** Publish/unpublish are no-ops — this backend is read-only. */
    @Override public void publish(ZoneManifestV1 manifest) { /* no-op */ }
    @Override public void unpublish(String did) {
        byDid.remove(did);
        lastSeenAt.remove(did);
    }

    @Override
    public Optional<ZoneManifestV1> lookup(String did) {
        return Optional.ofNullable(byDid.get(did));
    }

    @Override
    public List<String> discoverByTag(String tag) {
        var out = new ArrayList<String>();
        for (var m : byDid.values()) {
            if (m.tags() != null && m.tags().contains(tag)) out.add(m.did());
        }
        return out;
    }

    @Override
    public List<ZoneManifestV1> recent(int limit) {
        var all = new ArrayList<>(byDid.values());
        all.sort((a, b) -> {
            var ta = a.refreshedAt() == null ? "" : a.refreshedAt();
            var tb = b.refreshedAt() == null ? "" : b.refreshedAt();
            return tb.compareTo(ta);
        });
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    /** Test/diagnostic — current cache size. */
    public int cacheSize() {
        return byDid.size();
    }
}
