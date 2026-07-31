package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.interiority.ChronicleEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Track-C C5 — {@link RecipeChronicleSynthesizer} pure-logic
 * conversion from {@link RecipeForgeIngester.CompletedRun} →
 * {@link ChronicleEntry}.
 *
 * <p>Five status branches, deploy/rollback bookkeeping, gate counting,
 * and the bondholder-facing summary line all matter for what the Study
 * Chronicle furnishing actually renders.</p>
 */
class RecipeChronicleSynthesizerTest {

    private static final String DID = "did:wyrd:companion-x";
    private static final Instant NOW = Instant.parse("2026-05-25T10:00:00Z");

    @Test
    void success_with_deploy_writes_full_structured_row() {
        var ctx = new RecipeContext();
        ctx.put("val_accuracy", 0.8734);

        var run = new RecipeRunner.RecipeRun(
            RecipeRunner.Status.SUCCESS, "ok",
            List.of(
                new RecipeRunner.StepOutcome("g1", StepKind.GATE, true, "min_accuracy"),
                new RecipeRunner.StepOutcome("g2", StepKind.GATE, true, "non_regressed"),
                new RecipeRunner.StepOutcome("deploy", StepKind.BACKEND, true, "dispatched")),
            ctx);
        var cr = new RecipeForgeIngester.CompletedRun(
            "retrain-classifier-head", true, run);
        var in = new RecipeChronicleSynthesizer.SynthInput(cr,
            QueuedRecipe.TriggerSource.CRON, "settling cadence", CadenceTier.SETTLING);

        var entry = RecipeChronicleSynthesizer.synthesize(DID, in, NOW);

        assertThat(entry.agentDid()).isEqualTo(DID);
        assertThat(entry.kind()).isEqualTo(ChronicleEntry.Kind.RECIPE_RUN);
        assertThat(entry.ts()).isEqualTo(NOW);
        assertThat(entry.summary()).contains("retrain-classifier-head")
            .contains("succeeded").contains("0.8734");

        assertThat(entry.data())
            .containsEntry("recipeId", "retrain-classifier-head")
            .containsEntry("status", "SUCCESS")
            .containsEntry("triggerSource", "CRON")
            .containsEntry("triggerReason", "settling cadence")
            .containsEntry("primaryMetric", "val_accuracy")
            .containsEntry("primaryMetricValue", 0.8734)
            .containsEntry("gatesPassed", 2)
            .containsEntry("gatesTotal", 2)
            .containsEntry("rolledBack", false)
            .containsEntry("deployed", true)
            .containsEntry("cadenceTier", "SETTLING");
    }

    @Test
    void gate_failed_records_partial_gate_count_and_no_deploy() {
        var ctx = new RecipeContext();
        ctx.put("val_accuracy", 0.61);
        var run = new RecipeRunner.RecipeRun(
            RecipeRunner.Status.GATE_FAILED, "gate 'min_accuracy' failed",
            List.of(
                new RecipeRunner.StepOutcome("g1", StepKind.GATE, true, "passed"),
                new RecipeRunner.StepOutcome("g2", StepKind.GATE, false, "min_accuracy")),
            ctx);
        var cr = new RecipeForgeIngester.CompletedRun("retrain-classifier-head", true, run);

        var entry = RecipeChronicleSynthesizer.synthesize(DID,
            new RecipeChronicleSynthesizer.SynthInput(cr,
                QueuedRecipe.TriggerSource.AGENT, null, CadenceTier.WARMUP),
            NOW);

        assertThat(entry.data())
            .containsEntry("status", "GATE_FAILED")
            .containsEntry("gatesPassed", 1)
            .containsEntry("gatesTotal", 2)
            .containsEntry("deployed", false)
            .containsEntry("rolledBack", false)
            .containsEntry("cadenceTier", "WARMUP")
            .doesNotContainKey("triggerReason");
        assertThat(entry.summary()).contains("gate blocked deploy").contains("0.6100");
    }

    @Test
    void step_failed_with_rollback_records_rolled_back_true_deployed_false() {
        var run = new RecipeRunner.RecipeRun(
            RecipeRunner.Status.STEP_FAILED, "step 'train' failed",
            List.of(
                new RecipeRunner.StepOutcome("train", StepKind.SHELL, false, "exit 1"),
                new RecipeRunner.StepOutcome("rollback", StepKind.SHELL, true, "reverted")),
            new RecipeContext());
        var cr = new RecipeForgeIngester.CompletedRun(
            "retrain-classifier-head", true, run);

        var entry = RecipeChronicleSynthesizer.synthesize(DID,
            new RecipeChronicleSynthesizer.SynthInput(cr,
                QueuedRecipe.TriggerSource.STEWARD, "force-fire", CadenceTier.WARMUP),
            NOW);

        assertThat(entry.data())
            .containsEntry("status", "STEP_FAILED")
            .containsEntry("rolledBack", true)
            .containsEntry("deployed", false)
            .containsEntry("triggerSource", "STEWARD");
        assertThat(entry.summary()).contains("step failed").contains("rolled back");
    }

    @Test
    void needs_backend_status_renders_distinct_summary() {
        var run = new RecipeRunner.RecipeRun(
            RecipeRunner.Status.NEEDS_BACKEND, "no backend",
            List.of(), new RecipeContext());
        var cr = new RecipeForgeIngester.CompletedRun(
            "retrain-classifier-head", false, run);

        var entry = RecipeChronicleSynthesizer.synthesize(DID,
            new RecipeChronicleSynthesizer.SynthInput(cr,
                QueuedRecipe.TriggerSource.GAP, "gap:task_present.misroute",
                CadenceTier.WARMUP),
            NOW);

        assertThat(entry.data())
            .containsEntry("status", "NEEDS_BACKEND")
            .containsEntry("triggerSource", "GAP")
            .containsEntry("triggerReason", "gap:task_present.misroute");
        assertThat(entry.summary()).contains("needed a coding backend");
    }

    @Test
    void error_status_renders_recipe_needs_look_summary() {
        var run = new RecipeRunner.RecipeRun(
            RecipeRunner.Status.ERROR, "step budget exhausted",
            List.of(), new RecipeContext());
        var cr = new RecipeForgeIngester.CompletedRun("retrain-classifier-head", true, run);

        var entry = RecipeChronicleSynthesizer.synthesize(DID,
            new RecipeChronicleSynthesizer.SynthInput(cr,
                QueuedRecipe.TriggerSource.AGENT, null, CadenceTier.WARMUP),
            NOW);

        assertThat(entry.data()).containsEntry("status", "ERROR");
        assertThat(entry.summary()).contains("errored").contains("needs a look");
    }

    @Test
    void batch_synthesize_returns_one_entry_per_input() {
        var batch = new ArrayList<RecipeChronicleSynthesizer.SynthInput>();
        for (int i = 0; i < 3; i++) {
            var run = new RecipeRunner.RecipeRun(
                RecipeRunner.Status.SUCCESS, "ok", List.of(), new RecipeContext());
            var cr = new RecipeForgeIngester.CompletedRun("r" + i, false, run);
            batch.add(new RecipeChronicleSynthesizer.SynthInput(
                cr, QueuedRecipe.TriggerSource.AGENT, null, CadenceTier.WARMUP));
        }

        var out = RecipeChronicleSynthesizer.synthesize(DID, batch, NOW);
        assertThat(out).hasSize(3)
            .allSatisfy(e -> assertThat(e.kind()).isEqualTo(ChronicleEntry.Kind.RECIPE_RUN));
        assertThat(out).extracting(e -> e.data().get("recipeId"))
            .containsExactly("r0", "r1", "r2");
    }

    @Test
    void empty_batch_returns_empty_list() {
        List<RecipeChronicleSynthesizer.SynthInput> empty = List.of();
        List<RecipeChronicleSynthesizer.SynthInput> nullBatch = null;
        assertThat(RecipeChronicleSynthesizer.synthesize(DID, empty, NOW)).isEmpty();
        assertThat(RecipeChronicleSynthesizer.synthesize(DID, nullBatch, NOW)).isEmpty();
    }

    @Test
    void accuracy_falls_back_when_val_accuracy_missing() {
        var ctx = new RecipeContext();
        ctx.put("accuracy", 0.7);
        var run = new RecipeRunner.RecipeRun(
            RecipeRunner.Status.SUCCESS, "ok", List.of(), ctx);
        var cr = new RecipeForgeIngester.CompletedRun("recipe-x", false, run);

        var entry = RecipeChronicleSynthesizer.synthesize(DID,
            new RecipeChronicleSynthesizer.SynthInput(cr,
                QueuedRecipe.TriggerSource.AGENT, null, CadenceTier.WARMUP),
            NOW);

        assertThat(entry.data())
            .containsEntry("primaryMetric", "accuracy")
            .containsEntry("primaryMetricValue", 0.7);
    }

    @Test
    void next_fire_estimate_uses_post_run_tier_period() {
        var run = new RecipeRunner.RecipeRun(
            RecipeRunner.Status.SUCCESS, "ok", List.of(), new RecipeContext());
        var cr = new RecipeForgeIngester.CompletedRun("recipe-x", false, run);

        for (var tier : CadenceTier.values()) {
            var entry = RecipeChronicleSynthesizer.synthesize(DID,
                new RecipeChronicleSynthesizer.SynthInput(cr,
                    QueuedRecipe.TriggerSource.AGENT, null, tier),
                NOW);
            assertThat(entry.data()).containsEntry("cadenceTier", tier.name());
            assertThat(entry.data().get("nextFireEstimate"))
                .isEqualTo(NOW.plus(tier.period()).toString());
        }
    }

    @Test
    void null_completed_rejected() {
        assertThatThrownBy(() -> new RecipeChronicleSynthesizer.SynthInput(
                null, QueuedRecipe.TriggerSource.AGENT, null, CadenceTier.WARMUP))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
