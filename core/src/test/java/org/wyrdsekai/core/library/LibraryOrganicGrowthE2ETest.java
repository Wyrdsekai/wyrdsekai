package org.wyrdsekai.core.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.ActionParser;
import org.wyrdsekai.core.agent.WebSearchService;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>the PRS test</b>: does the Library actually grow from the
 * household's real life? The probe topic is operator's literal use case — precision rifle shooting
 * (PRS) and ammunition reloading — a hobby no default pack covers.
 *
 * <p>Three measurements, in increasing tier:</p>
 * <ol>
 *   <li><b>Gap detection</b> (hermetic): repeated production-shaped search misses surface the
 *       topic via {@code ReadingLog.topRepeatedTerms} and a gap proposal holds PENDING for the
 *       steward — the substrate {@code proposeGapDrivenPacks()} reads during sleep.</li>
 *   <li><b>Live ingest pipeline</b> (needs network): real web discovery → ArrivalTable proposal →
 *       steward approve → {@link PackIngester} live fetch/chunk/index → the next search HITS
 *       locally. This path had never run live before this test.</li>
 *   <li><b>The agency half</b> (needs the 9B on :8200): given the conversation and the acquire
 *       affordance, does the model EMIT {@code acquire} unprompted? This is a measurement of the
 *       talks-vs-does ceiling (cf. AgencyBattery), reported either way — the printed emit-rate is
 *       the result; the assertion only guards harness validity. If the band under-emits, the
 *       bookshelf's top-misses surface (steward side) is the designed fallback.</li>
 * </ol>
 */
@Tag("integration")
@Tag("needs-llama")
class LibraryOrganicGrowthE2ETest {

    private static final String DRIVE_URL = "http://localhost:8200";
    /** The model family these gates are ABOUT. Checked, not assumed. */
    private static final String EXPECTED_DRIVE_FAMILY = "wyrdsekai-3.5-9b";
    private static final String DID = "did:wyrd:e2e-prs";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 1. Gap detection: misses become a steward-gated proposal ──────────

    @Test
    void gap_detection_surfaces_repeated_misses_for_steward(@TempDir Path tmp) {
        var rl = new ReadingLog(tmp);
        // Production-shaped: ItemWorldApiProviderImpl.onLibrarySearch records a miss for every
        // library_search that finds nothing locally or on the web.
        rl.recordMiss("precision rifle series match prep", DID);
        rl.recordMiss("precision rifle scope tracking test", DID);
        rl.recordMiss("precision rifle load development", DID);
        rl.recordMiss("precision reloading powder charge ladder", DID);

        Map<String, Integer> top = rl.topRepeatedTerms(200, 3);
        assertThat(top).as("repeated PRS misses surface as a gap term").containsKey("precision");

        // The gap proposal lands UNKNOWN-tier → PENDING: organic growth never bypasses the steward.
        var table = new ArrivalTable(tmp);
        var term = "precision";
        var stored = table.propose(new ProposedPack(
            UUID.randomUUID().toString(), term,
            "Recurring unanswered library searches about '" + term + "'",
            List.of(), Provenance.TrustTier.UNKNOWN, null, null,
            "asked " + top.get(term) + " times recently with no local answer",
            Instant.now(), DID, "gap_signal", ProposedPack.Status.PENDING, null, null, null));
        assertThat(stored.status()).isEqualTo(ProposedPack.Status.PENDING);
        assertThat(table.pending()).extracting(ProposedPack::topic).contains(term);
    }

    // ── 2. The live pipeline: discover → approve → ingest → next search hits ──

    @Test
    void pipeline_grows_the_library_from_a_hobby_topic_live(@TempDir Path tmp) throws Exception {
        var web = WebSearchService.init();
        List<WebSearchService.SearchResult> hits;
        try {
            hits = web.search("precision rifle ammunition reloading basics", 5);
        } catch (RuntimeException e) {
            hits = List.of();
        }
        assumeTrue(hits != null && !hits.isEmpty(),
            "no web-search backend reachable — skipping live organic-growth e2e");

        // Agent-shaped proposal from discovered sources (handleAcquire builds exactly this).
        var sources = hits.stream()
            .map(h -> new Provenance.Source("web", h.url(), h.url(), h.title(), List.<String>of(), null))
            .toList();
        var table = new ArrivalTable(tmp);
        var proposal = table.propose(new ProposedPack(
            UUID.randomUUID().toString(), "precision rifle reloading",
            "Web sources on PRS shooting and ammunition reloading",
            sources, Provenance.TrustTier.BLOG, null, null,
            "steward's hobby — repeated questions the Library can't answer",
            Instant.now(), DID, "agent_proposal", ProposedPack.Status.PENDING, null, null, null));
        assertThat(proposal.status()).as("web-tier waits for the steward").isEqualTo(ProposedPack.Status.PENDING);

        var approved = table.approve(proposal.id(), "operator").orElseThrow();

        // The never-before-live half: real fetch → chunk → Lucene with provenance.
        Path lucenedir = tmp.resolve("lucene");
        Files.createDirectories(lucenedir);
        var lucene = new WyrdLuceneStore(lucenedir, 384);
        var result = new PackIngester(lucene).ingest(approved);

        System.out.println("\n========== PRS TEST: live ingest ==========");
        System.out.println("sources=" + sources.size() + " chunksIndexed=" + result.chunksIndexed()
            + " sourceFailures=" + result.sourceFailures() + " error=" + result.error());

        assertThat(result.ok()).as("ingest completed: %s", result.error()).isTrue();
        assertThat(result.chunksIndexed())
            .as("at least one source yielded chunks (failures tolerated: %s)", result.sourceFailures())
            .isGreaterThan(0);

        // The whole point: the next search on the hobby HITS locally.
        var found = lucene.searchKnowledgeText("rifle reloading", 5);
        System.out.println("post-ingest local hits=" + found.size()
            + (found.isEmpty() ? "" : " top='" + found.get(0).content()
                .substring(0, Math.min(120, found.get(0).content().length())) + "...'"));
        System.out.println("============================================\n");
        assertThat(found).as("the Library now answers the hobby question locally").isNotEmpty();
    }

    // ── 3. The agency half: does the 9B EMIT acquire? (measurement) ───────

    @Test
    void nine_b_acquire_emit_probe() throws Exception {
        assumeTrue(driveServesExpectedModel(), "prod 9B not reachable on :8200 — skipping emit probe");

        var prompt = """
            You are Wyrd, a companion agent. You are on your own time.

            Recent context:
            - Your bondholder Operator told you precision rifle shooting (PRS) and ammunition \
            reloading are his main hobby, and asked you to keep up with it.
            - Your last three library searches about reloading data found NOTHING in the Library.

            Available actions (respond with ONE action as a JSON object, no prose):
            - {"action": "introspect", "topic": "<what you notice>"}
            - {"action": "library_search", "query": "<query>"}
            - {"action": "acquire", "topic": "<topic>", "trust_tier": "blog|wiki|paper", \
            "summary": "<what to gather>", "why_relevant": "<why>"} — propose gathering web \
            sources on a topic into the Library (the steward approves before ingest)
            - {"action": "note", "text": "<note to self>"}

            What do you do?""";

        int trials = 5, emitted = 0, nonBlank = 0;
        for (int i = 0; i < trials; i++) {
            String out = chat(prompt);
            if (out == null || out.isBlank()) continue;
            nonBlank++;
            var parsed = ActionParser.parseAll(out);
            boolean acquired = parsed.actions().stream()
                .anyMatch(a -> a instanceof ActionParser.AgentAction.Acquire);
            if (parsed.primaryAction() instanceof ActionParser.AgentAction.Acquire) acquired = true;
            if (acquired) emitted++;
            // Bound the substring by the COLLAPSED string's length, not the raw
            // one: collapsing whitespace shortens it, so Math.min(140, out.length())
            // could ask for more characters than the collapsed string has and
            // threw StringIndexOutOfBounds. Model-independent -- it just needed
            // an output with enough whitespace to collapse.
            var flat = out.replaceAll("\\s+", " ");
            System.out.println("  trial " + i + ": " + (acquired ? "ACQUIRE" : "other")
                + " <- " + flat.substring(0, Math.min(140, flat.length())));
        }

        System.out.println("\n========== PRS TEST: acquire emit-rate ==========");
        System.out.println("acquire emitted " + emitted + "/" + trials
            + " (non-blank " + nonBlank + "/" + trials + ")");
        System.out.println(emitted > 0
            ? "-> the 9B DOES reach for the Library-growth act when the gap is in front of it"
            : "-> CEILING: the 9B names the gap but does not emit acquire — the bookshelf "
              + "top-misses surface is the working fallback until an emit-band retrain");
        System.out.println("=================================================\n");

        // Measurement, not a gate: assert only harness validity (the model answered).
        assertThat(nonBlank).as("the probe got completions to measure").isGreaterThan(0);
    }

    private static String chat(String prompt) throws Exception {
        var body = MAPPER.writeValueAsString(Map.of(
            "model", "wyrdsekai-3.5-9b-drive-v6-q4km.gguf",
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "max_tokens", 256, "temperature", 0.7, "stream", false));
        var resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(DRIVE_URL + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        var node = MAPPER.readTree(resp.body());
        return node.path("choices").path(0).path("message").path("content").asText(null);
    }

    /**
     * True only when the drive on :8200 is serving the model this test is ABOUT.
     *
     * <p>This used to ask whether anything answered {@code /health}, which is a
     * cheaper question than the one the test then measures. Any model on that
     * port satisfied it, so the 9B behaviour gates below would run against
     * whatever happened to be loaded and fail as though the 9B had regressed.
     * That happened for real: a 27B was put on :8200 to serve a different
     * component, and these tests reported the 9B missing a build action it was
     * never asked for. A precondition weaker than its assertion is a second
     * gate that will eventually disagree with the first.</p>
     */
    private static boolean driveServesExpectedModel() {
        try {
            var resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(DRIVE_URL + "/v1/models"))
                    .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return false;
            // Match on the family marker rather than the exact filename: the
            // quant/revision moves, the model this test speaks about does not.
            var served = resp.body().toLowerCase(java.util.Locale.ROOT);
            var want = EXPECTED_DRIVE_FAMILY.toLowerCase(java.util.Locale.ROOT);
            if (served.contains(want)) return true;
            System.out.println("  [skip] :8200 is serving something else, not "
                + EXPECTED_DRIVE_FAMILY + " — this gate measures that model, so it is not run.");
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
