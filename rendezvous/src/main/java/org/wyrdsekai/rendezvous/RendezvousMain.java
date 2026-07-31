package org.wyrdsekai.rendezvous;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standalone rendezvous process.
 *
 * <p>Purpose: be the directory aggregator for households that can't
 * self-publish a {@code .well-known/wyrd-zone} endpoint (residential
 * NAT, no public URL). Zones POST their signed manifest here; any
 * wyrdsekai in the world can query the rendezvous by DID, tag, or
 * capability.</p>
 *
 * <p><b>Runs as its own process.</b> Process isolation from the
 * tunnel relay is explicit and mandatory (SPEC §5.2) — an inference-
 * streaming spike on the tunnel must not take down directory service.
 * Deploy as a separate systemd unit alongside the tunnel relay on the
 * same host.</p>
 *
 * <h2>Configuration (env vars)</h2>
 *
 * <ul>
 *   <li>{@code WYRDSEKAI_RENDEZVOUS_PORT} — HTTP port (default 7071).
 *       Deliberately different from the main server's 7070 so both
 *       can run on the same host without conflict.</li>
 *   <li>{@code WYRDSEKAI_RENDEZVOUS_MAX_MANIFESTS} — LRU eviction cap
 *       (default 100_000).</li>
 *   <li>{@code WYRDSEKAI_RENDEZVOUS_PUBLISH_MIN_INTERVAL_MS} — per-DID
 *       publish rate limit, minimum gap between updates (default
 *       60_000 = 1 minute).</li>
 *   <li>{@code WYRDSEKAI_RENDEZVOUS_TTL_SEC} — drop manifests not
 *       refreshed within this window (default 48 hours).</li>
 * </ul>
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>{@code GET /api/directory/recent?limit=N}</li>
 *   <li>{@code GET /api/directory/{did}}</li>
 *   <li>{@code GET /api/directory/tag/{tag}}</li>
 *   <li>{@code GET /api/directory/capability/{name}}</li>
 *   <li>{@code GET /api/directory/search?q=<text>} — semantic search (V2, stubbed)</li>
 *   <li>{@code GET /api/directory/subscribe?tag=X|capability=X} — SSE (V2, stubbed)</li>
 *   <li>{@code POST /publish} — signed manifest body</li>
 *   <li>{@code POST /tombstone} — signed tombstone (body: {"did":"..."})</li>
 *   <li>{@code GET /health} — JSON health for liveness probes</li>
 * </ul>
 */
public final class RendezvousMain {

    private static final Logger log = LoggerFactory.getLogger(RendezvousMain.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Runtime handle — what {@link #start} returns. Tests can shut down
     * the server via {@link #close()} without killing the JVM.
     */
    public static final class Handle implements AutoCloseable {
        private final Javalin app;
        private final DirectoryStore store;
        private final PublishGateway gateway;
        private final SubscriptionHub hub;
        private final ScheduledExecutorService sweeper;

        Handle(Javalin app, DirectoryStore store, PublishGateway gateway,
               SubscriptionHub hub, ScheduledExecutorService sweeper) {
            this.app = app; this.store = store; this.gateway = gateway;
            this.hub = hub; this.sweeper = sweeper;
        }

        public int port() { return app.port(); }
        public DirectoryStore store() { return store; }
        public PublishGateway gateway() { return gateway; }
        public SubscriptionHub hub() { return hub; }

        @Override public void close() {
            try { sweeper.shutdownNow(); } catch (Exception ignore) {}
            try { app.stop(); } catch (Exception ignore) {}
        }
    }

    public static void main(String[] args) {
        int port = intEnv("WYRDSEKAI_RENDEZVOUS_PORT", 7071);
        int maxManifests = intEnv("WYRDSEKAI_RENDEZVOUS_MAX_MANIFESTS", 100_000);
        long publishMinMs = longEnv("WYRDSEKAI_RENDEZVOUS_PUBLISH_MIN_INTERVAL_MS", 60_000L);
        long ttlSec = longEnv("WYRDSEKAI_RENDEZVOUS_TTL_SEC", 48 * 3600L);
        start(port, maxManifests, publishMinMs, ttlSec);
    }

    /**
     * Start the rendezvous with explicit config. Used both by {@link #main}
     * (with env-derived defaults) and by integration tests (which want
     * random ports + tight rate-limit windows).
     *
     * @return a {@link Handle} that can be used to stop the server + query
     *     bound port. Tests should use try-with-resources.
     */
    public static Handle start(int port, int maxManifests,
                                long publishMinMs, long ttlSec) {
        var store = new DirectoryStore(maxManifests, ttlSec);
        var hub = new SubscriptionHub();

        // Keyword search: always on. Lucene-backed BM25 over analyzed
        // fields (tokenization, stop words, multi-field with boosts).
        // Degrades to the substring scorer if initialisation fails.
        KeywordIndex keywordIndex = null;
        try {
            keywordIndex = new KeywordIndex();
            store.setKeywordIndex(keywordIndex);
            log.info("Keyword search: Lucene (in-memory, BM25)");
        } catch (Exception e) {
            log.warn("Keyword search: Lucene init failed ({}), falling back to substring",
                e.getMessage());
        }
        final KeywordIndex keywordIndexRef = keywordIndex;

        // Semantic search (complementary): enabled when WYRDSEKAI_EMBEDDING_URL
        // is set. Search API blends keyword + semantic via RRF when both are
        // available; otherwise serves the available path.
        var embedUrl = System.getenv("WYRDSEKAI_EMBEDDING_URL");
        var embedModel = System.getenv().getOrDefault("WYRDSEKAI_EMBEDDING_MODEL", "default");
        EmbeddingClient embedClient = null;
        SemanticIndex semanticIndex = null;
        if (embedUrl != null && !embedUrl.isBlank()) {
            embedClient = new EmbeddingClient(embedUrl, embedModel);
            semanticIndex = new SemanticIndex(embedClient, store);
            log.info("Semantic search: embeddings at {} (model={})", embedUrl, embedModel);
        } else {
            log.info("Semantic search: off (set WYRDSEKAI_EMBEDDING_URL to enable)");
        }
        final SemanticIndex semanticIndexRef = semanticIndex;

        store.setChangeListener(new DirectoryStore.ChangeListener() {
            @Override public void onPublished(ZoneManifestV1 m, boolean isNew) {
                hub.notifyPublished(m, isNew);
                if (keywordIndexRef != null) keywordIndexRef.index(m);
                if (semanticIndexRef != null) semanticIndexRef.indexManifest(m);
            }
            @Override public void onRemoved(ZoneManifestV1 m) {
                hub.notifyRemoved(m);
                if (keywordIndexRef != null) keywordIndexRef.remove(m.did());
                if (semanticIndexRef != null) semanticIndexRef.removeManifest(m.did());
            }
        });
        var gateway = new PublishGateway(store, publishMinMs);

        // Periodic TTL sweep — cheap pass over lastSeenAt map.
        var sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "rendezvous-ttl-sweep");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(store::evictExpired,
            5, 5, TimeUnit.MINUTES);

        final var appRef = new AtomicReference<Javalin>();
        appRef.set(Javalin.create(cfg -> {
            cfg.routes.get("/health", ctx -> ctx.json(Map.of(
                "status", "ok",
                "manifests", store.size(),
                "cap", maxManifests,
                "uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime())));

            cfg.routes.get("/api/directory/recent", ctx -> {
                int limit = parseLimit(ctx.queryParam("limit"), 20);
                var manifests = store.recent(limit).stream()
                    .map(m -> MAPPER.convertValue(m, Map.class))
                    .toList();
                ctx.json(Map.of("count", manifests.size(), "manifests", manifests));
            });

            cfg.routes.get("/api/directory/tag/{tag}", ctx -> {
                var tag = ctx.pathParam("tag");
                var dids = store.discoverByTag(tag);
                ctx.json(Map.of("tag", tag, "count", dids.size(), "dids", dids));
            });

            cfg.routes.get("/api/directory/capability/{capability}", ctx -> {
                var cap = ctx.pathParam("capability");
                var dids = store.discoverByCapability(cap);
                ctx.json(Map.of("capability", cap, "count", dids.size(), "dids", dids));
            });

            cfg.routes.get("/api/directory/search", ctx -> {
                var q = ctx.queryParam("q");
                if (q == null || q.isBlank()) {
                    ctx.status(400).json(Map.of("error", "q query param required"));
                    return;
                }
                int limit = parseLimit(ctx.queryParam("limit"), 10);
                // Hybrid: Lucene BM25 (via store.searchText) + semantic
                // embeddings (when configured), merged by Reciprocal Rank
                // Fusion. Either path alone still returns usable results.
                var keyword = store.searchText(q, limit * 2);
                var semantic = semanticIndexRef != null
                    ? semanticIndexRef.search(q, limit * 2)
                    : List.<DirectoryStore.SearchHit>of();
                var merged = RrfMerge.merge(keyword, semantic, limit);
                ctx.json(Map.of(
                    "q", q,
                    "count", merged.size(),
                    "mode", semanticIndexRef != null ? "hybrid" : "keyword",
                    "hits", merged));
            });

            cfg.routes.sse("/api/directory/subscribe", client -> {
                var tag = client.ctx().queryParam("tag");
                var cap = client.ctx().queryParam("capability");
                var filter = new SubscriptionHub.Filter(tag, cap);

                client.keepAlive();

                long id = SubscriptionHub.nextSinkId();
                var sink = new SubscriptionHub.SseSink() {
                    @Override public long id() { return id; }
                    @Override public void send(String event, String jsonData) {
                        client.sendEvent(event, jsonData);
                    }
                    @Override public void close() { client.close(); }
                    @Override public boolean isClosed() { return client.terminated(); }
                };
                hub.subscribe(sink, filter);
                client.sendEvent("subscribed",
                    "{\"filter\":\"" + filter.label() + "\"}");
                client.onClose(() -> hub.unsubscribe(id));
            });

            cfg.routes.get("/api/directory/{did}", ctx -> {
                var did = ctx.pathParam("did");
                var opt = store.lookup(did);
                if (opt.isEmpty()) {
                    ctx.status(404).json(Map.of(
                        "error", "no manifest for " + did));
                    return;
                }
                ctx.contentType("application/json");
                ctx.result(opt.get().toJsonBytes());
            });

            cfg.routes.post("/publish", ctx -> {
                var body = ctx.body();
                if (body == null || body.isBlank()) {
                    ctx.status(400).json(Map.of("error", "empty body"));
                    return;
                }
                try {
                    var manifest = ZoneManifestV1.fromJsonString(body);
                    var ip = ctx.ip();
                    var result = gateway.publish(manifest, ip);
                    switch (result) {
                        case ACCEPTED -> ctx.json(Map.of(
                            "status", "accepted", "did", manifest.did()));
                        case RATE_LIMITED -> ctx.status(429).json(Map.of(
                            "error", "rate limit: one publish per " + publishMinMs + "ms per DID"));
                        case REJECTED_INVALID -> ctx.status(400).json(Map.of(
                            "error", "manifest validation failed"));
                        case REJECTED_FULL -> ctx.status(503).json(Map.of(
                            "error", "rendezvous at capacity; try another"));
                    }
                } catch (IllegalStateException e) {
                    ctx.status(400).json(Map.of(
                        "error", "manifest parse/validate failed: " + e.getMessage()));
                } catch (Exception e) {
                    log.warn("publish handler error: {}", e.toString());
                    ctx.status(500).json(Map.of("error", "internal error"));
                }
            });

            cfg.routes.post("/tombstone", ctx -> {
                try {
                    var node = MAPPER.readTree(ctx.body());
                    var did = node.path("did").asText(null);
                    if (did == null || did.isBlank()) {
                        ctx.status(400).json(Map.of("error", "did required"));
                        return;
                    }
                    store.unpublish(did);
                    ctx.json(Map.of("status", "tombstoned", "did", did));
                } catch (Exception e) {
                    ctx.status(400).json(Map.of(
                        "error", "tombstone request malformed: " + e.getMessage()));
                }
            });
        }).start("0.0.0.0", port));

        log.info("wyrd-rendezvous listening on :{} (cap={}, publishMin={}ms, ttl={}s)",
            appRef.get().port(), maxManifests, publishMinMs, ttlSec);
        return new Handle(appRef.get(), store, gateway, hub, sweeper);
    }

    private static int parseLimit(String raw, int defaultValue) {
        if (raw == null) return defaultValue;
        try {
            return Math.max(1, Math.min(200, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int intEnv(String key, int defaultValue) {
        var v = System.getenv(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static long longEnv(String key, long defaultValue) {
        var v = System.getenv(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Long.parseLong(v.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }
}
