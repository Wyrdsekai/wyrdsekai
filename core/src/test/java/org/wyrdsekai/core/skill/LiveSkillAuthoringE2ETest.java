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
 * tier-3 LIVE e2e proving {@link LiveSkillAuthoring} authors a real
 * discriminating verification harness end-to-end against the prod drive model.
 *
 * <p>Wires the exact production seams: a real {@link InferenceRouter} → the local 9B on :8200
 * (cap:reasoning, household tier), a real {@link WyrdLuceneStore} (seeded with two
 * temperature-conversion knowledge chunks so the leakage-barrier retrieval has evidence), and a
 * real {@link SkillDraftStore}. Then it runs {@code authorPendingFor} (the same call the Forge
 * sleep-pass and {@code POST /api/skill/author} make) and asserts: a harness was mined + compiled +
 * persisted on the draft, and that frozen harness — run as pure code, no model — PERMITS the
 * correct skill and BLOCKS a buggy one.</p>
 *
 * <p>Self-skips when the 9B isn't reachable on :8200, so it is safe in the hermetic CI lane.</p>
 */
@Tag("integration")
@Tag("needs-llama")
class LiveSkillAuthoringE2ETest {

    private static final String DRIVE_URL = "http://localhost:8200";
    private static final String DRIVE_MODEL = "wyrdsekai-3.5-9b-drive-v6-q4km.gguf";
    private static final String DID = "did:wyrd:e2e-skillauthor";

    private static final String GOOD = "function execute(p){ return { fahrenheit: p.celsius * 9 / 5 + 32 }; }";
    private static final String BUGGY = "function execute(p){ return { fahrenheit: p.celsius * 9 / 5 }; }";

    private static ActorTestKit testKit;

    @BeforeAll
    static void setUp() {
        assumeTrue(driveReachable(), "prod 9B drive not reachable on :8200 — skipping live e2e");
        testKit = ActorTestKit.create();
    }

    @AfterAll
    static void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
        SkillDraftStore.resetForTests();
    }

    @Test
    void authors_a_discriminating_harness_against_the_live_9b(@TempDir Path tmp) throws Exception {
        // ── Real stores ───────────────────────────────────────────────────
        var jdbc = SchemaInitializer.initialize(tmp.resolve("drafts.db"));
        var store = new SkillDraftStore(jdbc);

        Path lucenedir = tmp.resolve("lucene");
        Files.createDirectories(lucenedir);
        var lucene = new WyrdLuceneStore(lucenedir, 384);
        seedTemperatureKnowledge(lucene);

        // ── Real router → prod 9B ─────────────────────────────────────────
        var backend = new InferenceBackend.LlamaServer(
            "prod9b", new InferenceClient(DRIVE_URL), 10, List.of(), null);
        var router = testKit.spawn(InferenceRouter.create(
            List.of(backend), DRIVE_MODEL, null, Duration.ofMinutes(5)));

        // ── A pending, un-authored draft ──────────────────────────────────
        var draft = SkillDraft.pending(UUID.randomUUID().toString(), DID,
            "celsius_to_fahrenheit", "Convert Celsius to Fahrenheit", "Steward asked.",
            GOOD, "graaljs", List.of("temperature conversion"), null, DRIVE_MODEL);
        store.upsert(draft);
        assertThat(store.get(draft.draftId()).orElseThrow().verificationHarness())
            .as("fresh draft is unverified").isNull();

        // ── The live authoring run (mine → generate → self-consistency → teeth → persist) ──
        var authoring = LiveSkillAuthoring.forAgent(router, testKit.scheduler(), lucene, store, DID);
        int authored = authoring.authorPendingFor(DID);

        // ── Assert a harness was authored + persisted ON the draft ────────
        var reloaded = store.get(draft.draftId()).orElseThrow();
        assertThat(authored).as("the 9B authored a harness for the one pending draft").isEqualTo(1);
        AnchorHarness harness = reloaded.verificationHarness();
        assertThat(harness).as("harness persisted in skill_drafts.harness_json").isNotNull();
        assertThat(harness.cases()).as("harness has at least one anchor-grounded case").isNotEmpty();

        // ── Assert the authored harness actually DISCRIMINATES (pure code, no model) ──
        var verifier = new SkillVerifier(new ItemScriptExecutor());
        var caps = ItemCapabilitySet.of(List.of());
        assertThat(verifier.verify("good", GOOD, harness, StubItemWorldApiProvider.INSTANCE, caps).passed())
            .as("correct skill PASSES the live-authored harness").isTrue();
        assertThat(verifier.verify("buggy", BUGGY, harness, StubItemWorldApiProvider.INSTANCE, caps).passed())
            .as("buggy skill (missing +32) is BLOCKED by the live-authored harness").isFalse();
    }

    private static void seedTemperatureKnowledge(WyrdLuceneStore lucene) {
        var freezeProv = new Provenance(
            new Provenance.Source("wiki", "Celsius", "https://en.wikipedia.org/wiki/Celsius",
                "Celsius — Wikipedia", List.of(), 2026),
            Provenance.TrustTier.WIKI, "CC-BY-SA", null, null, null, null, null, null);
        var boilProv = new Provenance(
            new Provenance.Source("wiki", "Fahrenheit", "https://en.wikipedia.org/wiki/Fahrenheit",
                "Fahrenheit — Wikipedia", List.of(), 2026),
            Provenance.TrustTier.WIKI, "CC-BY-SA", null, null, null, null, null, null);
        lucene.insertKnowledge("k-freeze", "physics", "Celsius",
            "At standard pressure water freezes at 0 degrees Celsius, which is 32 degrees Fahrenheit.",
            "Wikipedia: Celsius", "temperature conversion", null, freezeProv);
        lucene.insertKnowledge("k-boil", "physics", "Fahrenheit",
            "Water boils at 100 degrees Celsius, equal to 212 degrees Fahrenheit, at sea level.",
            "Wikipedia: Fahrenheit", "temperature conversion", null, boilProv);
        lucene.commitAll();
    }

    private static boolean driveReachable() {
        try {
            var resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(DRIVE_URL + "/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
