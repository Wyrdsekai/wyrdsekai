package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.WebSearchService;
import org.wyrdsekai.core.library.KnowledgePackIndexer;
import org.wyrdsekai.core.library.LibraryServices;
import org.wyrdsekai.core.library.ProposedPack;
import org.wyrdsekai.core.library.Provenance;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the steward proposal endpoints on
 * {@link LibraryKnowledgeRoutes} via a real Javalin server — REST parity
 * with the Study bookshelf / Library card catalog surfaces.
 */
class LibraryProposalRoutesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private WyrdLuceneStore store;
    private Javalin app;
    private HttpClient http;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        LibraryServices.reset();
        LibraryServices.init(tmp.resolve("library"));
        store = new WyrdLuceneStore(tmp.resolve("lucene"), 384);
        var routes = new LibraryKnowledgeRoutes(
            store, new KnowledgePackIndexer(store), tmp.resolve("packs"));
        app = Javalin.create(cfg -> {
            routes.register(cfg.routes);
            // Local source page for the approve→ingest→search loop — keeps the
            // PackIngester fetch on 127.0.0.1 (no internet).
            cfg.routes.get("/test-source", ctx -> ctx.html(
                "<html><body><h1>Precision Rifle Series basics</h1>"
                + "<p>The Hornady 6.5 Creedmoor with a 140 grain ELD Match bullet "
                + "is a common precision rifle series load. Reloading dies, "
                + "match-grade brass, and consistent powder charges matter most "
                + "for repeatable long-range accuracy.</p></body></html>"));
        }).start("127.0.0.1", 0);
        baseUrl = "http://127.0.0.1:" + app.port();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (app != null) app.stop();
        store.close();
        LibraryServices.reset();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static ProposedPack propose(String topic, String proposedBy) {
        return LibraryServices.arrivalTable().propose(ProposedPack.of(
            topic, "summary of " + topic, Provenance.TrustTier.UNKNOWN,
            "gap_detection", proposedBy));
    }

    @Test
    void proposalsEmptyInitially() throws Exception {
        var resp = get("/api/library/proposals");
        assertEquals(200, resp.statusCode());
        var json = MAPPER.readTree(resp.body());
        assertEquals(0, json.get("count").asInt());
    }

    @Test
    void proposalsListPendingWithIds() throws Exception {
        var p = propose("precision rifle reloading", "wyrd");
        propose("sourdough starters", "vesna");

        var json = MAPPER.readTree(get("/api/library/proposals").body());
        assertEquals(2, json.get("count").asInt());
        boolean found = false;
        for (JsonNode node : json.get("proposals")) {
            if (node.get("id").asText().equals(p.id())) {
                found = true;
                assertEquals("precision rifle reloading", node.get("topic").asText());
                assertEquals("PENDING", node.get("status").asText());
            }
        }
        assertTrue(found, "proposed pack should appear with full id");
    }

    @Test
    void approveByIdPrefix() throws Exception {
        var p = propose("precision rifle reloading", "wyrd");

        var resp = post("/api/library/proposals/" + p.id().substring(0, 8) + "/approve",
            "{\"reviewer\":\"operator\"}");
        assertEquals(200, resp.statusCode(), resp.body());
        var json = MAPPER.readTree(resp.body());
        // No sources attached → plain approved, no background ingest.
        assertEquals("approved", json.get("status").asText());
        assertEquals("operator", json.get("proposal").get("reviewedBy").asText());

        var stored = LibraryServices.arrivalTable().get(p.id()).orElseThrow();
        assertEquals(ProposedPack.Status.APPROVED, stored.status());
    }

    @Test
    void rejectWithReason() throws Exception {
        var p = propose("sourdough starters", "vesna");

        var resp = post("/api/library/proposals/" + p.id() + "/reject",
            "{\"reviewer\":\"operator\",\"reason\":\"not now\"}");
        assertEquals(200, resp.statusCode(), resp.body());
        assertEquals("rejected", MAPPER.readTree(resp.body()).get("status").asText());

        var stored = LibraryServices.arrivalTable().get(p.id()).orElseThrow();
        assertEquals(ProposedPack.Status.REJECTED, stored.status());
        assertEquals("not now", stored.rejectionReason());

        // Gone from pending, still visible with ?status=all.
        assertEquals(0, MAPPER.readTree(get("/api/library/proposals").body()).get("count").asInt());
        assertEquals(1, MAPPER.readTree(get("/api/library/proposals?status=all").body()).get("count").asInt());
    }

    @Test
    void unknownPrefixIs404() throws Exception {
        assertEquals(404, post("/api/library/proposals/zzzzzzzz/approve", "").statusCode());
        assertEquals(404, post("/api/library/proposals/zzzzzzzz/reject", "").statusCode());
    }

    @Test
    void missesSurfaceRepeatedTerms() throws Exception {
        var rl = LibraryServices.readingLog();
        rl.recordMiss("precision rifle ballistics", "did:wyrd:test");
        rl.recordMiss("precision rifle scopes", "did:wyrd:test");
        rl.recordMiss("precision rifle ammunition", "did:wyrd:test");

        var json = MAPPER.readTree(get("/api/library/misses").body());
        assertTrue(json.get("count").asInt() >= 1);
        var first = json.get("misses").get(0);
        assertEquals("precision", first.get("term").asText());
        assertEquals(3, first.get("count").asInt());
    }

    @Test
    void approveWithSourcesIngestsAndBecomesSearchable() throws Exception {
        // Full steward loop, all on 127.0.0.1: proposal with a real (local)
        // source → REST approve → background PackIngester fetch+chunk+index →
        // INGESTED → searchable. The network-free twin of the live PRS test.
        WebSearchService.init();
        var table = LibraryServices.arrivalTable();
        var proposal = table.propose(new ProposedPack(
            null, "precision rifle reloading", "household hobby gap",
            List.of(new Provenance.Source(
                "web", baseUrl + "/test-source", baseUrl + "/test-source",
                "PRS basics", List.of(), null)),
            Provenance.TrustTier.UNKNOWN, null, null,
            "the household keeps asking", null, "wyrd", "gap_detection",
            ProposedPack.Status.PENDING, null, null, null));

        var resp = post("/api/library/proposals/" + proposal.id() + "/approve",
            "{\"reviewer\":\"operator\"}");
        assertEquals(200, resp.statusCode(), resp.body());
        assertEquals("approved_ingesting", MAPPER.readTree(resp.body()).get("status").asText());

        // Ingest runs on a virtual thread — poll for INGESTED.
        var deadline = System.currentTimeMillis() + 15_000;
        ProposedPack stored;
        do {
            Thread.sleep(200);
            stored = table.get(proposal.id()).orElseThrow();
        } while (stored.status() != ProposedPack.Status.INGESTED
                 && System.currentTimeMillis() < deadline);
        assertEquals(ProposedPack.Status.INGESTED, stored.status(),
            "approve must drive the proposal through ingest");

        var hits = store.searchKnowledgeText("Creedmoor reloading", 5);
        assertFalse(hits.isEmpty(), "ingested source content must be locally searchable");
    }

    @Test
    void availableCarriesTierAndShelfFields() throws Exception {
        var json = MAPPER.readTree(get("/api/library/available").body());
        var entries = json.get("available");
        assertTrue(entries.size() > 0);
        var entry = entries.get(0);
        assertTrue(entry.has("tier"));
        assertTrue(entry.has("shelf"));
        assertTrue(entry.has("recommended"));
        assertTrue(entry.has("language"));
        assertTrue(entry.has("noFederate"));
        assertTrue(entry.has("installed"));
    }
}
