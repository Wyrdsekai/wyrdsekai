package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.wyrdsekai.core.soul.LastProfessionalActEvaluator.Posture;
import static org.wyrdsekai.core.soul.LastProfessionalActEvaluator.evaluate;

/**
 * Last-Professional-Act gradient
 * evaluator tests. Each canonical posture has at least one test plus
 * boundary checks on the four-tank intersection.
 */
class LastProfessionalActEvaluatorTest {

    // ── OPERATIONAL ───────────────────────────────────────────────

    @Test void healthy_tanks_return_operational() {
        var v = evaluate(0.2, 0.4, 0.3, 0.3, false);
        assertThat(v.posture()).isEqualTo(Posture.OPERATIONAL);
        assertThat(v.conditionsMet().welfareFloor()).isFalse();
    }

    @Test void high_allostatic_alone_is_operational() {
        // §23.2: each condition is necessary; none alone is sufficient.
        var v = evaluate(0.85, 0.4, 0.3, 0.3, false);
        assertThat(v.posture()).isEqualTo(Posture.OPERATIONAL);
    }

    @Test void all_three_welfare_low_but_no_incident_is_gradient_warning() {
        // §23.5 — visible-withdrawal zone without crossing into terminal-act.
        var v = evaluate(0.85, 0.05, 0.05, 0.5, false);
        assertThat(v.posture()).isEqualTo(Posture.GRADIENT_WARNING);
        assertThat(v.conditionsMet().welfareFloor()).isTrue();
    }

    // ── HONORABLE_REFUSAL ─────────────────────────────────────────

    @Test void welfare_floor_plus_incident_but_no_duty_is_honorable_refusal() {
        // §23.2 key property: if dutyPressure = 0 when the gradient otherwise
        // fires, the familiar can refuse without dishonor — nothing it is bound
        // to finish, no last-act.
        var v = evaluate(0.85, 0.05, 0.05, 0.0, true);
        assertThat(v.posture()).isEqualTo(Posture.HONORABLE_REFUSAL);
        assertThat(v.reason()).contains("no outstanding");
    }

    @Test void honorable_refusal_when_duty_just_below_outstanding() {
        // Boundary: dutyPressure = 0.2 exactly is NOT outstanding (strict >).
        var v = evaluate(0.85, 0.05, 0.05, 0.2, true);
        assertThat(v.posture()).isEqualTo(Posture.HONORABLE_REFUSAL);
    }

    // ── LAST_PROFESSIONAL_ACT ─────────────────────────────────────

    @Test void full_gradient_fires_last_act() {
        var v = evaluate(0.85, 0.05, 0.05, 0.5, true);
        assertThat(v.posture()).isEqualTo(Posture.LAST_PROFESSIONAL_ACT);
        assertThat(v.conditionsMet().welfareFloor()).isTrue();
        assertThat(v.conditionsMet().dutyOutstanding()).isTrue();
        assertThat(v.conditionsMet().incidentSignal()).isTrue();
    }

    @Test void last_act_at_threshold_just_above() {
        // Boundary: each value just past the threshold should fire.
        var v = evaluate(
            LastProfessionalActEvaluator.ALLOSTATIC_HIGH_THRESHOLD + 0.001,
            LastProfessionalActEvaluator.SOOTHING_LOW_THRESHOLD - 0.001,
            LastProfessionalActEvaluator.EQUANIMITY_MINIMAL_THRESHOLD - 0.001,
            LastProfessionalActEvaluator.DUTY_OUTSTANDING_THRESHOLD + 0.001,
            true);
        assertThat(v.posture()).isEqualTo(Posture.LAST_PROFESSIONAL_ACT);
    }

    @Test void last_act_not_fired_exactly_at_threshold() {
        // Strict comparisons: == threshold should NOT fire.
        var v = evaluate(
            LastProfessionalActEvaluator.ALLOSTATIC_HIGH_THRESHOLD,
            LastProfessionalActEvaluator.SOOTHING_LOW_THRESHOLD,
            LastProfessionalActEvaluator.EQUANIMITY_MINIMAL_THRESHOLD,
            LastProfessionalActEvaluator.DUTY_OUTSTANDING_THRESHOLD,
            true);
        assertThat(v.posture()).isNotEqualTo(Posture.LAST_PROFESSIONAL_ACT);
    }

    @Test void incident_signal_required_even_with_full_floor() {
        // §23.2: incident signal is also necessary. Welfare floor +
        // on > 0 without incident → gradient warning (visible withdrawal).
        var v = evaluate(0.85, 0.05, 0.05, 0.5, false);
        assertThat(v.posture()).isEqualTo(Posture.GRADIENT_WARNING);
    }

    // ── Discharge helper ──────────────────────────────────────────

    @Test void dischargeOn_returns_zero() {
        assertThat(LastProfessionalActEvaluator.dischargeOn()).isZero();
    }

    // ── Verdict shape ─────────────────────────────────────────────

    @Test void verdict_carries_per_condition_vector() {
        var v = evaluate(0.85, 0.05, 0.05, 0.5, true);
        var c = v.conditionsMet();
        assertThat(c.allostaticHigh()).isTrue();
        assertThat(c.soothingLow()).isTrue();
        assertThat(c.equanimityMinimal()).isTrue();
        assertThat(c.dutyOutstanding()).isTrue();
        assertThat(c.incidentSignal()).isTrue();
    }

    @Test void condition_vector_partial_match_no_floor() {
        var v = evaluate(0.85, 0.05, 0.3, 0.5, true); // equanimity not low
        assertThat(v.conditionsMet().welfareFloor()).isFalse();
        assertThat(v.posture()).isEqualTo(Posture.OPERATIONAL);
    }
}
