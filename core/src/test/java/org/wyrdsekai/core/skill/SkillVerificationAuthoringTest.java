package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.coding.StubItemWorldApiProvider;
import org.wyrdsekai.core.library.Provenance;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * (productionize) — proves the harness is authored off the hot path, persisted
 * WITH the draft, survives a store round-trip, and gates approval via the persisted-harness wiring.
 *
 * <ul>
 *   <li>{@link SkillVerificationAuthoring} mines anchors → generates a harness → stores it on the draft;</li>
 *   <li>the harness round-trips through {@link SkillDraftStore} (it travels with the skill);</li>
 *   <li>{@link SkillGate#fromPersistedHarness} reads it off the draft and blocks a buggy skill /
 *       permits a correct one — no harness store lookup, no model at the gate.</li>
 * </ul>
 * Miner + generator are stubs (deterministic); the same code runs against the local 9B / cloud.
 */
class SkillVerificationAuthoringTest {

    private SkillDraftStore store;
    private SkillVerifier verifier;

    private static final String GOOD = "function execute(p){ return { fahrenheit: p.celsius * 9 / 5 + 32 }; }";
    private static final String BUGGY = "function execute(p){ return { fahrenheit: p.celsius * 9 / 5 }; }";

    @BeforeEach
    void setup(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("drafts.db"));
        store = new SkillDraftStore(jdbc);
        verifier = new SkillVerifier(new ItemScriptExecutor());
    }

    @AfterEach
    void cleanup() { SkillDraftStore.resetForTests(); }

    private static final SourcedSnippet FREEZE = new SourcedSnippet(
        "Water freezes at 0 degrees Celsius, which is 32 degrees Fahrenheit.",
        new Provenance.Source("wiki", "Celsius", "https://en.wikipedia.org/wiki/Celsius",
            "Celsius — Wikipedia", List.of(), 2026),
        Provenance.TrustTier.WIKI);

    /** A miner stub that returns one grounded anchor (the real ModelAnchorMiner is tested separately). */
    private AnchorMiner stubMiner() {
        return new ModelAnchorMiner(q -> List.of(FREEZE),
            p -> "{\"anchors\":[{\"fact\":\"water freezes at 0C = 32F\",\"kind\":\"REFERENCE_VALUE\",\"sourceIndex\":0}]}");
    }

    /** A generator stub that turns the mined fact into a deterministic numeric case. */
    private HarnessGenerator stubGenerator() {
        return new ModelHarnessGenerator(p -> """
            {"skillName":"celsius_to_fahrenheit","cases":[
              {"params":{"celsius":0},"outputKey":"fahrenheit",
               "check":{"kind":"NUMERIC_EQUALS","expected":32.0,"epsilon":1e-9},"source":"water freezes at 0C = 32F"}
            ]}""");
    }

    private SkillDraft seed(String code) {
        var d = SkillDraft.pending(UUID.randomUUID().toString(), "did:wyrd:wyrd",
            "celsius_to_fahrenheit", "Convert Celsius to Fahrenheit.", "Steward asked.",
            code, "graaljs", List.of("temperature conversion"), null, "9b@rev-1");
        store.upsert(d);
        return d;
    }

    @Test
    void authoring_mines_generates_and_persists_a_harness_with_the_draft() {
        var draft = seed(GOOD);
        assertThat(draft.verificationHarness()).as("fresh draft is unverified").isNull();

        var authoring = new SkillVerificationAuthoring(stubMiner(), stubGenerator(), store);
        var authored = authoring.author(draft);

        assertThat(authored.verificationHarness()).as("harness authored onto the draft").isNotNull();
        assertThat(authored.verificationHarness().cases()).hasSize(1);

        // The harness travels WITH the skill: it survives a store round-trip (Trading-Post copy).
        var reloaded = store.get(draft.draftId()).orElseThrow();
        assertThat(reloaded.verificationHarness()).as("harness persisted in skill_drafts.harness_json").isNotNull();
        assertThat(reloaded.verificationHarness().skillName()).isEqualTo("celsius_to_fahrenheit");
        assertThat(reloaded.verificationHarness().cases()).hasSize(1);
    }

    @Test
    void authorPendingFor_skips_drafts_that_already_carry_a_harness() {
        seed(GOOD);
        var authoring = new SkillVerificationAuthoring(stubMiner(), stubGenerator(), store);

        assertThat(authoring.authorPendingFor("did:wyrd:wyrd")).as("first pass authors the one pending draft").isEqualTo(1);
        assertThat(authoring.authorPendingFor("did:wyrd:wyrd")).as("second pass is idempotent").isEqualTo(0);
    }

    private SkillVerificationAuthoring fullAuthoring(HarnessGenerator gen) {
        return new SkillVerificationAuthoring(stubMiner(), gen, store, verifier,
            StubItemWorldApiProvider.INSTANCE, ItemCapabilitySet.of(List.of()));
    }

    @Test
    void quality_gates_attach_a_value_harness_that_has_teeth() {
        var authored = fullAuthoring(stubGenerator()).author(seed(GOOD));
        assertThat(authored.verificationHarness())
            .as("a self-consistent, toothed harness is attached").isNotNull();
    }

    @Test
    void quality_gates_drop_a_toothless_harness() {
        // Generator emits a presence-only (NON_EMPTY) harness: the good skill passes it, but so does
        // every wrong version → toothless → must NOT be attached (draft left unverified to escalate).
        var toothless = new ModelHarnessGenerator(p -> """
            {"skillName":"celsius_to_fahrenheit","cases":[
              {"params":{"celsius":0},"outputKey":"fahrenheit","check":{"kind":"NON_EMPTY"},"source":"exists"}
            ]}""");
        var result = fullAuthoring(toothless).author(seed(GOOD));
        assertThat(result.verificationHarness()).as("toothless harness dropped → unverified").isNull();
    }

    @Test
    void quality_gates_drop_a_harness_its_own_skill_fails() {
        // Generator emits a WRONG expected value (miscompiled anchor): the correct skill fails it,
        // which would block the good skill at the gate → must NOT be attached.
        var miscompiled = new ModelHarnessGenerator(p -> """
            {"skillName":"celsius_to_fahrenheit","cases":[
              {"params":{"celsius":0},"outputKey":"fahrenheit",
               "check":{"kind":"NUMERIC_EQUALS","expected":99.0,"epsilon":1e-9},"source":"wrong"}
            ]}""");
        var result = fullAuthoring(miscompiled).author(seed(GOOD));
        assertThat(result.verificationHarness()).as("self-inconsistent harness dropped → unverified").isNull();
    }

    @Test
    void persisted_harness_gate_blocks_buggy_permits_good_with_no_model_at_the_gate() {
        var authoring = new SkillVerificationAuthoring(stubMiner(), stubGenerator(), store);
        var gate = SkillGate.fromPersistedHarness(verifier,
            StubItemWorldApiProvider.INSTANCE, ItemCapabilitySet.of(List.of()));

        // Good skill: author harness, persist, approve → gate permits + materializes.
        var good = authoring.author(seed(GOOD));
        var goodBoard = new WorkshopPinboard(store, gate);
        var goodMaterialized = new AtomicBoolean(false);
        var goodDecision = goodBoard.approve("did:wyrd:wyrd", 1, "ok", d -> goodMaterialized.set(true));
        assertThat(goodDecision.ok()).isTrue();
        assertThat(goodMaterialized).isTrue();
        assertThat(good.verificationHarness()).isNotNull();

        // Buggy skill: SAME authored harness shape, but code is wrong → gate blocks, no materialize.
        SkillDraftStore.resetForTests();
        var buggy = authoring.author(seed(BUGGY));
        assertThat(buggy.verificationHarness()).as("buggy draft still gets a harness authored").isNotNull();
        var buggyBoard = new WorkshopPinboard(store, gate);
        var buggyMaterialized = new AtomicBoolean(false);
        var buggyDecision = buggyBoard.approve("did:wyrd:wyrd", 1, "looks ok to steward",
            d -> buggyMaterialized.set(true));
        assertThat(buggyDecision.ok()).isFalse();
        assertThat(buggyDecision.message()).contains("Verification gate blocked");
        assertThat(buggyMaterialized).as("buggy skill must not materialize").isFalse();
    }
}
