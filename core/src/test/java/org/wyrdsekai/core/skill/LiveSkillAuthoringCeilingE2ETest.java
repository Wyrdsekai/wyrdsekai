package org.wyrdsekai.core.skill;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.coding.StubItemWorldApiProvider;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.library.Provenance;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * tier-3 LIVE <b>ceiling probe</b>. Where {@code LiveSkillAuthoringE2ETest}
 * uses celsius&rarr;fahrenheit (the lucky band: two reference points fully pin an affine map, so any
 * plausible bug breaks one of them), this test deliberately picks a skill whose obvious reference
 * values do NOT catch a plausible bug.
 *
 * <p><b>Roman numeral &rarr; integer.</b> The GOOD skill handles subtractive notation; the BUGGY
 * skill is a naive left-to-right sum. They AGREE on every additive numeral (III=3, VIII=8, XV=15…)
 * and diverge ONLY on subtractive ones (IV: 4 vs 6, IX: 9 vs 11, MCMXCIV: 1994 vs 2216). So a
 * harness that only tests additive numerals is toothless — the bug slips through. Only a harness
 * that mines the documented subtractive-notation facts (IV=4, IX=9, XL=40…) and turns them into
 * cases has teeth.</p>
 *
 * <p>This asks the real question of the local 9B: does it understand that the <i>subtractive</i>
 * cases are the discriminating ones, or does it just copy the easy symbol values? The seeded
 * evidence contains BOTH the symbol table and the subtractive examples, so either is groundable —
 * the model's choice is the signal. A failure here is a <b>finding</b> (the authoring ceiling),
 * not a defect; the test prints the authored harness so the outcome is legible either way.</p>
 *
 * <p>Self-skips when the 9B isn't reachable on :8200.</p>
 */
@Tag("integration")
@Tag("needs-llama")
class LiveSkillAuthoringCeilingE2ETest {

    private static final String DRIVE_URL = "http://localhost:8200";
    /** The model family these gates are ABOUT. Checked, not assumed. */
    private static final String EXPECTED_DRIVE_FAMILY = "wyrdsekai-3.5-9b";
    private static final String DRIVE_MODEL = "wyrdsekai-3.5-9b-drive-v6-q4km.gguf";
    private static final String DID = "did:wyrd:e2e-skillceiling";

    // Subtractive-aware parser (right-to-left, subtract when a symbol precedes a larger one).
    private static final String GOOD = """
        function execute(p){
          var m={I:1,V:5,X:10,L:50,C:100,D:500,M:1000};
          var s=String(p.roman), total=0, prev=0;
          for(var i=s.length-1;i>=0;i--){
            var v=m[s.charAt(i)];
            if(v<prev){ total-=v; } else { total+=v; prev=v; }
          }
          return { value: total };
        }""";

    // Naive left-to-right sum — has no concept of subtraction (IV -> 6, IX -> 11).
    private static final String BUGGY = """
        function execute(p){
          var m={I:1,V:5,X:10,L:50,C:100,D:500,M:1000};
          var s=String(p.roman), total=0;
          for(var i=0;i<s.length;i++){ total+=m[s.charAt(i)]; }
          return { value: total };
        }""";

    private static ActorTestKit testKit;

    @BeforeAll
    static void setUp() {
        assumeTrue(driveServesExpectedModel(), "prod 9B drive not reachable on :8200 — skipping live ceiling e2e");
        testKit = ActorTestKit.create();
    }

    @AfterAll
    static void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
        SkillDraftStore.resetForTests();
    }

    @Test
    void probes_whether_the_9b_authors_a_discriminating_harness_for_a_hard_skill(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("drafts.db"));
        var store = new SkillDraftStore(jdbc);

        Path lucenedir = tmp.resolve("lucene");
        Files.createDirectories(lucenedir);
        var lucene = new WyrdLuceneStore(lucenedir, 384);
        seedRomanNumeralKnowledge(lucene);

        var backend = new InferenceBackend.LlamaServer(
            "prod9b", new InferenceClient(DRIVE_URL), 10, List.of(), null);
        var router = testKit.spawn(InferenceRouter.create(
            List.of(backend), DRIVE_MODEL, null, Duration.ofMinutes(5)));

        var draft = SkillDraft.pending(UUID.randomUUID().toString(), DID,
            "roman_to_int", "Convert a Roman numeral string to its integer value",
            "Steward asked for a Roman numeral parser.",
            GOOD, "graaljs", List.of("Roman numerals", "Roman numeral subtractive notation"),
            null, DRIVE_MODEL);
        store.upsert(draft);

        var authoring = LiveSkillAuthoring.forAgent(router, testKit.scheduler(), lucene, store, DID);
        int authored = authoring.authorPendingFor(DID);

        var reloaded = store.get(draft.draftId()).orElseThrow();
        AnchorHarness harness = reloaded.verificationHarness();

        // ── Print the authored harness so the ceiling outcome is legible either way ──
        System.out.println("\n========== CEILING PROBE: roman_to_int ==========");
        System.out.println("authored harnesses: " + authored);
        if (harness == null) {
            System.out.println("NO HARNESS AUTHORED (no anchors mined / quality gate dropped it)");
        } else {
            System.out.println("harness '" + harness.skillName() + "' cases=" + harness.cases().size());
            for (var c : harness.cases()) {
                Object exp = c.check() != null ? c.check().expected() : null;
                System.out.println("  params=" + c.params() + " key=" + c.outputKey()
                    + " expect[" + (c.check() != null ? c.check().kind() : "?") + "]=" + exp
                    + " src=" + c.source());
            }
        }

        var verifier = new SkillVerifier(new ItemScriptExecutor());
        var caps = ItemCapabilitySet.of(List.of());
        boolean goodPasses = harness != null
            && verifier.verify("good", GOOD, harness, StubItemWorldApiProvider.INSTANCE, caps).passed();
        boolean buggyBlocked = harness != null
            && !verifier.verify("buggy", BUGGY, harness, StubItemWorldApiProvider.INSTANCE, caps).passed();
        System.out.println("VERDICT: goodPasses=" + goodPasses + " buggyBlocked=" + buggyBlocked
            + (buggyBlocked ? "  -> 9B authored a DISCRIMINATING harness (handles this band)"
                            : "  -> CEILING: harness is toothless against the subtractive bug"));
        System.out.println("=================================================\n");

        // The probe: a harness exists, permits the correct skill, and CATCHES the subtractive bug.
        assertThat(authored).as("the 9B authored a harness for the one pending draft").isEqualTo(1);
        assertThat(harness).as("harness persisted").isNotNull();
        assertThat(goodPasses).as("correct subtractive parser PASSES the live-authored harness").isTrue();
        assertThat(buggyBlocked)
            .as("naive-sum bug (IV->6) is BLOCKED — the harness mined discriminating subtractive cases")
            .isTrue();
    }

    private static void seedRomanNumeralKnowledge(WyrdLuceneStore lucene) {
        var prov = new Provenance(
            new Provenance.Source("wiki", "Roman numerals", "https://en.wikipedia.org/wiki/Roman_numerals",
                "Roman numerals — Wikipedia", List.of(), 2026),
            Provenance.TrustTier.WIKI, "CC-BY-SA", null, null, null, null, null, null);
        // Snippet A: the symbol table + additive reading (the "easy" facts).
        lucene.insertKnowledge("k-roman-symbols", "history", "Roman numerals",
            "Roman numerals use seven letters: I is 1, V is 5, X is 10, L is 50, C is 100, "
            + "D is 500, and M is 1000. Numerals are normally written largest to smallest from "
            + "left to right and the values are added together; for example VIII is 8 and XV is 15.",
            "Wikipedia: Roman numerals", "Roman numerals", null, prov);
        // Snippet B: the subtractive notation (the discriminating facts).
        lucene.insertKnowledge("k-roman-subtractive", "history", "Roman numerals",
            "Where a smaller numeral appears before a larger one its value is subtracted, a rule "
            + "called subtractive notation. Thus IV is 4 (one less than five), IX is 9, XL is 40, "
            + "XC is 90, CD is 400, and CM is 900. For example MCMXCIV represents 1994.",
            "Wikipedia: Roman numerals", "Roman numeral subtractive notation", null, prov);
        lucene.commitAll();
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
