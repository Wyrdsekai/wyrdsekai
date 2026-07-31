package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1142 — the tuner's safety core. Pure logic, no I/O.
 *
 * <p>The load-bearing invariant: a param referenced by a PERMANENT welfare gate
 * condition is a floor and may never be auto-tuned. Everything else is bounded
 * by the caller-supplied {@code [min,max]}. These tests pin both — plus the
 * outcome-stats reconstruction the loop reads to decide whether to act.</p>
 */
class RecipeParamTunerTest {

    /** A manifest with one PERMANENT-gated param (floor), one TEMPORARY-gated
     *  param, and a free soft param — built from YAML so it exercises the real
     *  parser + gate WelfareClass mapping. */
    private static RecipeManifest manifest() {
        return RecipeParser.parseManifest("""
            recipe: tune-target
            params:
              min_separation:   { type: number, default: 0.3 }
              soft_threshold:   { type: number, default: 0.5 }
              lookback_days:    { type: number, default: 30 }
            steps:
              - id: plan
                kind: SHELL
                command: "echo plan"
              - id: gate-floor
                kind: GATE
                condition: "separation >= {{min_separation}}"
                on_fail: STOP
                welfare: permanent
              - id: gate-soft
                kind: GATE
                condition: "score <= {{soft_threshold}}"
                on_fail: STOP
                welfare: temporary
              - id: deploy
                kind: SHELL
                command: "echo deploy"
            """);
    }

    @Test void floor_protected_params_are_exactly_the_permanent_gate_refs() {
        var floors = RecipeParamTuner.floorProtectedParams(manifest());
        // Only min_separation is behind a PERMANENT gate. soft_threshold is
        // behind a TEMPORARY gate → tunable. lookback_days is ungated → tunable.
        assertThat(floors).containsExactly("min_separation");
    }

    @Test void refuses_to_nudge_a_floor_protected_param() {
        var d = RecipeParamTuner.validateNudge(manifest(), "min_separation", 0.9, 0.3, 1.0);
        assertThat(d.allow()).isFalse();
        assertThat(d.refusal()).isEqualTo(RecipeParamTuner.Refusal.FLOOR_PROTECTED);
    }

    @Test void allows_a_nudge_to_a_temporary_gated_param_within_bounds() {
        // A TEMPORARY gate does NOT make a param a floor — it's tunable.
        var d = RecipeParamTuner.validateNudge(manifest(), "soft_threshold", 0.6, 0.5, 0.8);
        assertThat(d.allow()).isTrue();
        assertThat(d.refusal()).isEqualTo(RecipeParamTuner.Refusal.NONE);
    }

    @Test void allows_a_nudge_to_an_ungated_soft_param() {
        var d = RecipeParamTuner.validateNudge(manifest(), "lookback_days", 45, 30, 120);
        assertThat(d.allow()).isTrue();
    }

    @Test void refuses_a_value_outside_the_declared_bounds() {
        var d = RecipeParamTuner.validateNudge(manifest(), "lookback_days", 200, 30, 120);
        assertThat(d.allow()).isFalse();
        assertThat(d.refusal()).isEqualTo(RecipeParamTuner.Refusal.OUT_OF_BOUNDS);
    }

    @Test void refuses_inverted_bounds() {
        var d = RecipeParamTuner.validateNudge(manifest(), "lookback_days", 45, 120, 30);
        assertThat(d.allow()).isFalse();
        assertThat(d.refusal()).isEqualTo(RecipeParamTuner.Refusal.BAD_BOUNDS);
    }

    @Test void refuses_a_param_the_recipe_does_not_declare() {
        var d = RecipeParamTuner.validateNudge(manifest(), "no_such_param", 1, 0, 10);
        assertThat(d.allow()).isFalse();
        assertThat(d.refusal()).isEqualTo(RecipeParamTuner.Refusal.UNKNOWN_PARAM);
    }

    @Test void floor_protection_is_checked_before_bounds() {
        // Even with absurd bounds, a floor-protected param is refused for being
        // a floor (not for the bounds) — ordering matters for the right message.
        var d = RecipeParamTuner.validateNudge(manifest(), "min_separation", 5, 10, 1);
        assertThat(d.refusal()).isEqualTo(RecipeParamTuner.Refusal.FLOOR_PROTECTED);
    }

    @Test void stats_reconstructs_success_fail_and_gate_fail_counts() {
        var rows = List.of(
            terminal(QueuedRecipe.Status.SUCCEEDED, null),
            terminal(QueuedRecipe.Status.SUCCEEDED, null),
            terminal(QueuedRecipe.Status.FAILED, "step 'validate' GATE failed"),
            terminal(QueuedRecipe.Status.FAILED, "process timed out"),
            // PENDING rows are not terminal — excluded from totals.
            QueuedRecipe.newEntry(UUID.randomUUID().toString(), "tune-target",
                Map.of(), "pending", QueuedRecipe.TriggerSource.CRON,
                "did:test:x", CadenceTier.WARMUP, 0));
        var s = RecipeParamTuner.statsFrom(rows);
        assertThat(s.total()).isEqualTo(4);
        assertThat(s.succeeded()).isEqualTo(2);
        assertThat(s.failed()).isEqualTo(2);
        assertThat(s.gateFailed()).isEqualTo(1);   // only the "gate"-message one
        assertThat(s.failRate()).isEqualTo(0.5);
    }

    @Test void stats_on_empty_history_is_a_zero_fail_rate() {
        var s = RecipeParamTuner.statsFrom(List.of());
        assertThat(s.total()).isZero();
        assertThat(s.failRate()).isEqualTo(0.0);
    }

    private static QueuedRecipe terminal(QueuedRecipe.Status status, String message) {
        var now = Instant.now();
        return new QueuedRecipe(UUID.randomUUID().toString(), "tune-target",
            Map.of(), "r", QueuedRecipe.TriggerSource.CRON,
            now, now, now, status, "did:test:x", CadenceTier.WARMUP, 0,
            null, message);
    }
}
