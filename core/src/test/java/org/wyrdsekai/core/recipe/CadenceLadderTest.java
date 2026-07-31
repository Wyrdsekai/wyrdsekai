package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.wyrdsekai.core.recipe.CadenceLadder.Outcome;
import static org.wyrdsekai.core.recipe.CadenceLadder.advance;

/**
 * Track-C C2 — pure-logic ladder transitions. Every path the
 * scheduler can take is exercised here so the actor test (which is heavier)
 * can trust the policy and focus on dispatch + persistence.
 */
class CadenceLadderTest {

    // ── WARMUP → SETTLING promotion ─────────────────────────────────────

    @Test
    void warmup_first_success_holds_at_warmup_count_1() {
        var next = advance(CadenceTier.WARMUP, 0, Outcome.SUCCESS);
        assertThat(next.tier()).isEqualTo(CadenceTier.WARMUP);
        assertThat(next.consecutiveSuccesses()).isEqualTo(1);
    }

    @Test
    void warmup_second_success_holds_at_warmup_count_2() {
        var next = advance(CadenceTier.WARMUP, 1, Outcome.SUCCESS);
        assertThat(next.tier()).isEqualTo(CadenceTier.WARMUP);
        assertThat(next.consecutiveSuccesses()).isEqualTo(2);
    }

    @Test
    void warmup_third_success_promotes_to_settling_with_count_reset() {
        var next = advance(CadenceTier.WARMUP, 2, Outcome.SUCCESS);
        assertThat(next.tier()).isEqualTo(CadenceTier.SETTLING);
        // Count resets so SETTLING starts its own ladder toward MATURE.
        assertThat(next.consecutiveSuccesses()).isEqualTo(0);
    }

    // ── SETTLING → MATURE promotion ─────────────────────────────────────

    @Test
    void settling_holds_until_the_fifth_consecutive_success() {
        // After 4 successes at SETTLING, still SETTLING with count=4.
        var next = advance(CadenceTier.SETTLING, 3, Outcome.SUCCESS);
        assertThat(next.tier()).isEqualTo(CadenceTier.SETTLING);
        assertThat(next.consecutiveSuccesses()).isEqualTo(4);
    }

    @Test
    void settling_fifth_consecutive_success_promotes_to_mature() {
        var next = advance(CadenceTier.SETTLING, 4, Outcome.SUCCESS);
        assertThat(next.tier()).isEqualTo(CadenceTier.MATURE);
        assertThat(next.consecutiveSuccesses()).isEqualTo(0);
    }

    // ── MATURE — terminal promotion floor ───────────────────────────────

    @Test
    void mature_holds_on_success_and_keeps_counting() {
        var next = advance(CadenceTier.MATURE, 12, Outcome.SUCCESS);
        assertThat(next.tier()).isEqualTo(CadenceTier.MATURE);
        // Count continues for diagnostic — there's no higher tier to promote to.
        assertThat(next.consecutiveSuccesses()).isEqualTo(13);
    }

    // ── Demotion paths — every non-SUCCESS outcome → WARMUP/0 ───────────

    @Test
    void mature_gate_failure_demotes_all_the_way_to_warmup() {
        var next = advance(CadenceTier.MATURE, 42, Outcome.GATE_FAILED);
        assertThat(next.tier()).isEqualTo(CadenceTier.WARMUP);
        assertThat(next.consecutiveSuccesses()).isZero();
    }

    @Test
    void rollback_fired_demotes_from_settling() {
        var next = advance(CadenceTier.SETTLING, 3, Outcome.ROLLBACK_FIRED);
        assertThat(next.tier()).isEqualTo(CadenceTier.WARMUP);
        assertThat(next.consecutiveSuccesses()).isZero();
    }

    @Test
    void steward_override_demotes_from_mature() {
        var next = advance(CadenceTier.MATURE, 7, Outcome.STEWARD_OVERRIDE);
        assertThat(next.tier()).isEqualTo(CadenceTier.WARMUP);
        assertThat(next.consecutiveSuccesses()).isZero();
    }

    @Test
    void step_failed_demotes_from_warmup_count_resets() {
        var next = advance(CadenceTier.WARMUP, 2, Outcome.STEP_FAILED);
        assertThat(next.tier()).isEqualTo(CadenceTier.WARMUP);
        assertThat(next.consecutiveSuccesses()).isZero();
    }

    @Test
    void error_outcome_demotes_count_resets() {
        var next = advance(CadenceTier.SETTLING, 4, Outcome.ERROR);
        assertThat(next.tier()).isEqualTo(CadenceTier.WARMUP);
        assertThat(next.consecutiveSuccesses()).isZero();
    }

    // ── Defensive defaults ──────────────────────────────────────────────

    @Test
    void null_tier_defaults_to_warmup() {
        var next = advance(null, 0, Outcome.SUCCESS);
        assertThat(next.tier()).isEqualTo(CadenceTier.WARMUP);
        assertThat(next.consecutiveSuccesses()).isEqualTo(1);
    }

    @Test
    void negative_count_clamps_to_zero_then_increments_on_success() {
        var next = advance(CadenceTier.WARMUP, -5, Outcome.SUCCESS);
        assertThat(next.tier()).isEqualTo(CadenceTier.WARMUP);
        assertThat(next.consecutiveSuccesses()).isEqualTo(1);
    }

    // ── Full promotion arc — 3 SUCCESSes + 5 SUCCESSes lifts to MATURE ──

    @Test
    void full_arc_warmup_to_mature_takes_eight_successes() {
        var s = new CadenceLadder.State(CadenceTier.WARMUP, 0);
        // 3 successes promote WARMUP → SETTLING
        for (int i = 0; i < 3; i++) {
            s = advance(s.tier(), s.consecutiveSuccesses(), Outcome.SUCCESS);
        }
        assertThat(s.tier()).isEqualTo(CadenceTier.SETTLING);
        // 5 more successes promote SETTLING → MATURE
        for (int i = 0; i < 5; i++) {
            s = advance(s.tier(), s.consecutiveSuccesses(), Outcome.SUCCESS);
        }
        assertThat(s.tier()).isEqualTo(CadenceTier.MATURE);
        assertThat(s.consecutiveSuccesses()).isZero();
    }

    @Test
    void demotion_at_settling_step_2_resets_arc() {
        var s = new CadenceLadder.State(CadenceTier.WARMUP, 0);
        for (int i = 0; i < 3; i++) {
            s = advance(s.tier(), s.consecutiveSuccesses(), Outcome.SUCCESS);
        }
        assertThat(s.tier()).isEqualTo(CadenceTier.SETTLING);
        // Two successes at SETTLING, then a gate failure — back to WARMUP/0.
        s = advance(s.tier(), s.consecutiveSuccesses(), Outcome.SUCCESS);
        s = advance(s.tier(), s.consecutiveSuccesses(), Outcome.SUCCESS);
        s = advance(s.tier(), s.consecutiveSuccesses(), Outcome.GATE_FAILED);
        assertThat(s.tier()).isEqualTo(CadenceTier.WARMUP);
        assertThat(s.consecutiveSuccesses()).isZero();
    }
}
