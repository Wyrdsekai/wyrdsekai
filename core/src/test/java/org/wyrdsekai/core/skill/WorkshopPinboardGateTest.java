package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.coding.StubItemWorldApiProvider;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * the verification gate wired into {@link WorkshopPinboard#approve}.
 *
 * <p>Proves the gate blocks a draft that fails its anchor harness (draft stays PENDING,
 * materializer never runs), permits one that passes, and is back-compat: a draft with no
 * harness is unverified and still materializes.</p>
 */
class WorkshopPinboardGateTest {

    private SkillDraftStore store;
    private SkillVerifier verifier;

    @BeforeEach
    void setup(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("drafts.db"));
        store = new SkillDraftStore(jdbc);
        verifier = new SkillVerifier(new ItemScriptExecutor());
    }

    @AfterEach
    void cleanup() { SkillDraftStore.resetForTests(); }

    // WorkbenchValidator requires graaljs skills to define `function execute(params)`
    // (the sandbox accepts execute or invoke; the validator is stricter).
    private static final String GOOD = "function execute(p){ return { fahrenheit: p.celsius * 9 / 5 + 32 }; }";
    private static final String BUGGY = "function execute(p){ return { fahrenheit: p.celsius * 9 / 5 }; }";

    private static AnchorHarness converterHarness() {
        return new AnchorHarness("celsius_to_fahrenheit", List.of(
            new AnchorHarness.VerificationCase(Map.of("celsius", 0), "fahrenheit",
                AnchorHarness.Check.numeric(32.0, 1e-9), "freezing point of water"),
            new AnchorHarness.VerificationCase(Map.of("celsius", 100), "fahrenheit",
                AnchorHarness.Check.numeric(212.0, 1e-9), "boiling point of water")));
    }

    private SkillDraft seed(String code) {
        var d = SkillDraft.pending(UUID.randomUUID().toString(), "did:wyrd:wyrd",
            "celsius_to_fahrenheit", "Convert Celsius to Fahrenheit.", "Steward asked.",
            code, "graaljs", List.of("temperature conversion"), null, "9b@rev-1");
        store.upsert(d);
        return d;
    }

    private WorkshopPinboard boardWithHarness(Function<SkillDraft, AnchorHarness> source) {
        var gate = SkillGate.verifying(verifier, source,
            StubItemWorldApiProvider.INSTANCE, ItemCapabilitySet.of(List.of()));
        return new WorkshopPinboard(store, gate);
    }

    @Test
    void gate_blocks_a_draft_that_fails_its_anchors() {
        seed(BUGGY);
        var materialized = new AtomicBoolean(false);
        var board = boardWithHarness(d -> converterHarness());

        var decision = board.approve("did:wyrd:wyrd", 1, "looks fine", d -> materialized.set(true));

        assertThat(decision.ok()).isFalse();
        assertThat(decision.message()).contains("Verification gate blocked").contains("anchors passed");
        assertThat(materialized).as("materializer must NOT run on a blocked draft").isFalse();
        // Draft stays PENDING for revision.
        assertThat(board.pending("did:wyrd:wyrd")).hasSize(1);
    }

    @Test
    void gate_permits_a_draft_that_passes_its_anchors() {
        seed(GOOD);
        var materialized = new AtomicBoolean(false);
        var board = boardWithHarness(d -> converterHarness());

        var decision = board.approve("did:wyrd:wyrd", 1, "verified", d -> materialized.set(true));

        assertThat(decision.ok()).isTrue();
        assertThat(materialized).as("verified draft should materialize").isTrue();
        assertThat(board.pending("did:wyrd:wyrd")).isEmpty();
    }

    @Test
    void unverified_draft_with_no_harness_still_materializes() {
        seed(BUGGY); // even a "buggy" skill passes when there is no harness to fail against
        var materialized = new AtomicBoolean(false);
        var board = boardWithHarness(d -> null); // no harness available → unverified

        var decision = board.approve("did:wyrd:wyrd", 1, "no harness", d -> materialized.set(true));

        assertThat(decision.ok()).isTrue();
        assertThat(materialized).isTrue();
    }
}
