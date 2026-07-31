package org.wyrdsekai.e2e.infra;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.WebSearchService;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stub-server harness for {@code OpenCodeHeavyItemE2ETest}.
 *
 * <p>The heavy-item test runs the realistic web-search → fetch → summarise →
 * library/journal/drive write chain end-to-end. To do that without flaky
 * dependence on live Searxng + arxiv + GitHub uptime, we stand up two
 * deterministic stand-ins:</p>
 *
 * <ol>
 *   <li><b>Search seeding</b> — handled in-process via
 *       {@link WebSearchService#seedResults(String, java.util.List)}. The
 *       generated item calls {@code world.web.search("liquid neural networks")},
 *       which routes through {@code ItemWorldApiProviderImpl.webSearch()} →
 *       {@code WebSearchService.search()}. The seed map short-circuits the
 *       backend pick (Searxng/Brave/etc.), so we don't need a stub HTTP
 *       Searxng running on a port. The seeded results <em>do</em> have to
 *       carry valid URLs, though, because the next step in the script is
 *       {@code world.web.fetch(url)}.</li>
 *
 *   <li><b>{@link StubContentServer} — Java {@link HttpServer}</b> on a
 *       random port. Serves the three canned HTML files from
 *       {@code src/test/resources/heavy-item-fixtures/}. The seeded
 *       Searxng URLs point at this server, so when the script does
 *       {@code world.web.fetch(url)} → {@code WebSearchService.fetchContent(url)}
 *       → JDK HttpClient GET, the response is the canned page. This is
 *       deterministic, reproducible, and exercises the full HTML-stripping
 *       extractor path.</li>
 * </ol>
 *
 * <p>Why we picked the {@code seedResults}-+-stub-content-server combination
 * instead of a stub Searxng:</p>
 *
 * <ul>
 *   <li><b>Search response shape is brittle</b>. Reproducing Searxng's exact
 *       JSON schema (the discriminated-union {@code results[]} payload) in a
 *       stub adds drift risk every time Searxng bumps a version. The seed
 *       hook is part of {@code WebSearchService}'s test contract — it
 *       <em>cannot</em> drift from the production search shape because both
 *       branches return the same {@code SearchResult} record.</li>
 *   <li><b>Content fetch shape is stable</b>. The fetcher is just a JDK
 *       {@code HttpClient} GET against a public URL with HTML stripping. The
 *       canned bytes a stub HTTP server serves are exactly what production
 *       sees.</li>
 *   <li><b>Single source of truth on URLs</b>. The seeded results' {@code url}
 *       fields are rewritten at seed time to point at the live stub-content
 *       server's host:port. If the test re-binds the server, the seeds get
 *       re-issued — no hard-coded {@code http://localhost:NNNN} drift.</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * try (var stubs = HeavyItemStubs.start()) {
 *     stubs.seedSearchFor("liquid neural networks");
 *     // ... run test, hit world.web.search + world.web.fetch ...
 * }
 * }</pre>
 *
 * <p>Implements {@link AutoCloseable} so JUnit's try-with-resources cleans up
 * the HTTP server thread + clears the seed map on test teardown.</p>
 */
public final class HeavyItemStubs implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HeavyItemStubs.class);

    /** Pages that live in {@code src/test/resources/heavy-item-fixtures/}. */
    public static final String FIXTURE_DIR = "/heavy-item-fixtures";

    /** The three canned content URLs the seed map points at. */
    public static final String ARXIV_KEY = "arxiv_2006_04439";
    public static final String CSAIL_KEY = "mit_csail_lnn";
    public static final String NCPS_KEY = "github_ncps_readme";

    private final StubContentServer contentServer;
    private final List<String> seededPatterns = new ArrayList<>();

    private HeavyItemStubs(StubContentServer contentServer) {
        this.contentServer = contentServer;
    }

    /**
     * Start the stub HTTP content server on a random ephemeral port. Does
     * NOT seed search yet — call {@link #seedSearchFor(String)} after
     * {@link WebSearchService#init()} has been invoked by
     * {@code TestServerBootstrap}.
     */
    public static HeavyItemStubs start() throws IOException {
        var server = StubContentServer.start();
        log.info("HeavyItemStubs: content server up on {}", server.baseUrl());
        return new HeavyItemStubs(server);
    }

    /** Base URL of the stub content server (no trailing slash). */
    public String contentBaseUrl() {
        return contentServer.baseUrl();
    }

    /** Number of times the content server has been hit. Useful for assertions. */
    public int contentHits() {
        return contentServer.hitCount();
    }

    /**
     * Seed {@link WebSearchService} with the canned three-result list for
     * {@code query}. The seed map is keyed on a case-insensitive substring,
     * so any query containing this string will return the canned list.
     *
     * <p>Side effect: the seeded URLs point at the stub content server's
     * actual host:port — so once {@link WebSearchService#init()} has been
     * invoked, fetching those URLs hits the stub.</p>
     */
    public void seedSearchFor(String query) {
        var ws = WebSearchService.get();
        if (ws == null) {
            throw new IllegalStateException(
                "WebSearchService.init() must be called before seeding. "
                    + "TestServerBootstrap normally does this in its constructor.");
        }
        var base = contentServer.baseUrl();
        var results = List.of(
            new WebSearchService.SearchResult(
                "Liquid Time-constant Networks (Hasani et al., 2020)",
                base + "/" + ARXIV_KEY,
                "Liquid time-constant networks (LTC) are continuous-time RNNs whose "
                    + "neurons are described by ODEs with input-dependent time constants. "
                    + "Hasani, Lechner et al., 2020."),
            new WebSearchService.SearchResult(
                "MIT CSAIL — Liquid Networks Generalize From Less Data",
                base + "/" + CSAIL_KEY,
                "MIT CSAIL: 19-neuron Liquid Neural Networks adapt parameters online "
                    + "while running, steering autonomous drones in unseen forests."),
            new WebSearchService.SearchResult(
                "ncps — Neural Circuit Policies (GitHub)",
                base + "/" + NCPS_KEY,
                "Reference Python library for LTC + Closed-form Continuous-time (CfC) "
                    + "neural networks; PyTorch and TensorFlow backends.")
        );
        ws.seedResults(query.toLowerCase(), results);
        seededPatterns.add(query.toLowerCase());
        log.info("HeavyItemStubs: seeded {} results for query pattern '{}'",
            results.size(), query);
    }

    @Override
    public void close() {
        contentServer.stop();
        // We deliberately don't have a hook to un-seed WebSearchService — the
        // fixture's only public mutation is via seedResults() and the test
        // tears down WebSearchService along with TestServerBootstrap. If a
        // future refactor isolates seeds, add a clearSeeds() to WebSearchService
        // and call it here.
        if (!seededPatterns.isEmpty()) {
            log.info("HeavyItemStubs: closed; {} seed pattern(s) remain on "
                + "WebSearchService until JVM teardown", seededPatterns.size());
        }
    }

    // ─── Stub content server ──────────────────────────────────────────

    /**
     * Tiny HTTP server that serves the three canned HTML fixtures. Uses the
     * JDK's built-in {@link HttpServer} to keep the test infra dep-free —
     * Jetty / Netty would pull a transitive headache for what's effectively
     * a {@code switch} on path.
     */
    public static final class StubContentServer {

        private final HttpServer server;
        private final int port;
        private final Map<String, byte[]> bodies;
        private final AtomicInteger hits = new AtomicInteger();

        private StubContentServer(HttpServer server, int port,
                                   Map<String, byte[]> bodies) {
            this.server = server;
            this.port = port;
            this.bodies = bodies;
        }

        public static StubContentServer start() throws IOException {
            var bodies = loadFixtures();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            int port = server.getAddress().getPort();
            var holder = new StubContentServer(server, port, bodies);
            server.createContext("/", new FixtureHandler(holder));
            // Use a small thread pool — these requests serve tiny canned
            // bodies and the test fires at most a few of them. The default
            // single-threaded executor would serialise concurrent fetches,
            // which is fine for this test but masks subtle bugs in
            // future tests that fan out fetches.
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            return holder;
        }

        public String baseUrl() { return "http://127.0.0.1:" + port; }
        public int port() { return port; }
        public int hitCount() { return hits.get(); }

        public void stop() {
            // Stop with a 0s grace period — this is test infra, no client
            // is going to be mid-request when teardown fires.
            server.stop(0);
        }

        private static Map<String, byte[]> loadFixtures() throws IOException {
            var bodies = new HashMap<String, byte[]>();
            for (var key : List.of(ARXIV_KEY, CSAIL_KEY, NCPS_KEY)) {
                var path = FIXTURE_DIR + "/" + key + ".html";
                try (InputStream in = HeavyItemStubs.class.getResourceAsStream(path)) {
                    if (in == null) {
                        throw new IOException("Missing fixture resource: " + path
                            + " — expected under e2e-test/src/test/resources" + FIXTURE_DIR);
                    }
                    bodies.put(key, in.readAllBytes());
                }
            }
            return Map.copyOf(bodies);
        }
    }

    private static final class FixtureHandler implements HttpHandler {
        private final StubContentServer holder;

        FixtureHandler(StubContentServer holder) { this.holder = holder; }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            holder.hits.incrementAndGet();
            // Strip leading slash; the URL path is the fixture key.
            var path = ex.getRequestURI().getPath();
            var key = path.startsWith("/") ? path.substring(1) : path;
            var body = holder.bodies.get(key);
            if (body == null) {
                var msg = ("404 — no fixture for '" + key + "'. Known keys: "
                    + holder.bodies.keySet()).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                ex.sendResponseHeaders(404, msg.length);
                try (var os = ex.getResponseBody()) { os.write(msg); }
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        }
    }
}
