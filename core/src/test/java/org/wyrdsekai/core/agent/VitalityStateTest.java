package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VitalityStateTest {

    @Test void initial_has_full_energy() {
        var state = VitalityState.initial();
        assertThat(state.energy()).isEqualTo(1.0);
    }

    @Test void initial_has_no_error_pressure() {
        var state = VitalityState.initial();
        assertThat(state.errorPressure()).isEqualTo(0.0);
    }

    @Test void initial_has_no_momentum() {
        var state = VitalityState.initial();
        assertThat(state.momentum()).isEqualTo(0.0);
    }

    @Test void initial_has_moderate_values() {
        var state = VitalityState.initial();
        assertThat(state.contextBudget()).isEqualTo(0.5);
        assertThat(state.confidence()).isEqualTo(0.5);
        assertThat(state.focus()).isEqualTo(0.5);
    }

    // --- Clamping ---

    @Test void clamp_above_one() {
        var state = new VitalityState(1.5, 2.0, 1.1, 0.5, 0.5, 0.5, 0.5, 0.5).clamped();
        assertThat(state.contextBudget()).isEqualTo(1.0);
        assertThat(state.confidence()).isEqualTo(1.0);
        assertThat(state.energy()).isEqualTo(1.0);
    }

    @Test void clamp_below_zero() {
        var state = new VitalityState(-0.1, -1.0, 0.5, 0.5, -0.5, 0.5, 0.5, 0.5).clamped();
        assertThat(state.contextBudget()).isEqualTo(0.0);
        assertThat(state.confidence()).isEqualTo(0.0);
        assertThat(state.errorPressure()).isEqualTo(0.0);
    }

    // --- Tick ---

    @Test void tick_energy_drains_while_awake() {
        // TICK_ENERGY_RATE is negative (being awake costs energy)
        var state = VitalityState.initial().withEnergy(0.5);
        var ticked = state.tick();
        assertThat(ticked.energy()).isLessThan(0.5);
    }

    @Test void passive_drain_is_day_scale() {
        // Day-scale calibration (2026-07-18): from the post-sleep baseline (~0.65)
        // to the sleep threshold (0.15) on passive drain ALONE must take at least a
        // long human day — activity, not mere wakefulness, is what mainly tires a
        // companion. And it must still be finite: an agent that never tires has no
        // sleep rhythm at all. (Guarded in hours, not against the constant's exact
        // value, so retuning within the day-scale band doesn't churn this test.)
        double budget = 0.65 - 0.15;
        double hoursAwakeOnPassiveAlone = budget / (-VitalityState.TICK_ENERGY_RATE * 3600.0);
        assertThat(hoursAwakeOnPassiveAlone).isGreaterThan(24.0);
        assertThat(hoursAwakeOnPassiveAlone).isLessThan(96.0);
    }

    @Test void tick_error_pressure_decays() {
        var state = VitalityState.initial().withErrorPressure(0.8);
        var ticked = state.tick();
        assertThat(ticked.errorPressure()).isLessThan(0.8);
    }

    @Test void tick_momentum_decays() {
        var state = VitalityState.initial().withMomentum(0.5);
        var ticked = state.tick();
        assertThat(ticked.momentum()).isLessThan(0.5);
    }

    @Test void tick_clamps_at_zero_floor() {
        // errorPressure at 0.0 stays 0.0 after decay
        var state = VitalityState.initial(); // errorPressure = 0.0
        var ticked = state.tick();
        assertThat(ticked.errorPressure()).isEqualTo(0.0);
    }

    @Test void tick_energy_drains_from_full() {
        // energy at 1.0 drains slightly after tick (being awake costs energy)
        var state = VitalityState.initial(); // energy = 1.0
        var ticked = state.tick();
        assertThat(ticked.energy()).isLessThan(1.0);
        assertThat(ticked.energy()).isGreaterThan(0.99); // a whisper per tick, not a bite
    }

    // --- With methods ---

    @Test void withEnergy_clamps_above_one() {
        var state = VitalityState.initial().withEnergy(1.5);
        assertThat(state.energy()).isEqualTo(1.0);
    }

    @Test void withErrorPressure_clamps_below_zero() {
        var state = VitalityState.initial().withErrorPressure(-0.1);
        assertThat(state.errorPressure()).isEqualTo(0.0);
    }

    // --- Describe ---

    @Test void describe_exhausted() {
        var state = VitalityState.initial().withEnergy(0.1);
        assertThat(state.describe()).contains("exhausted");
    }

    @Test void describe_confident() {
        var state = VitalityState.initial().withConfidence(0.9);
        assertThat(state.describe()).contains("confident");
    }

    @Test void describe_cautious_under_error_pressure() {
        var state = VitalityState.initial().withErrorPressure(0.7);
        assertThat(state.describe()).contains("cautious");
    }

    // --- Appearance ---

    @Test void appearance_radiant_when_high_energy_and_focus() {
        var state = VitalityState.initial().withEnergy(0.9).withFocus(0.8);
        assertThat(state.appearance()).isEqualTo("radiant and alert");
    }

    @Test void appearance_dim_when_low_energy() {
        var state = VitalityState.initial().withEnergy(0.1);
        assertThat(state.appearance()).isEqualTo("dim and flickering");
    }

    @Test void appearance_warm_when_good_energy_and_rapport() {
        var state = VitalityState.initial().withEnergy(0.6).withRapport(0.8).withFocus(0.4);
        assertThat(state.appearance()).isEqualTo("warm and attentive");
    }

    // ── Regression: applyColoringFeedback must NOT zero the deprivation tanks ──
    // The companion's per-tick drive→coloring feedback once rebuilt VitalityState
    // through the 10-arg coloring-only constructor, which hardcodes the 14
    // deprivation/protective tanks to defaults. That silently wiped restlessness/
    // loneliness/stagnation/generativity/etc. EVERY vitality tick, so boredom and
    // the social/generative drives could never accumulate in the live runtime.

    @Test void applyColoringFeedback_preserves_deprivation_tanks() {
        // A state with every deprivation/protective tank meaningfully non-default.
        var state = VitalityState.initial()
            .withRestlessness(0.6).withLoneliness(0.5).withStagnation(0.4)
            .withAutonomyPressure(0.3).withSignificance(0.35).withAmae(0.45)
            .withSaudade(0.55).withObligation(0.25).withHarmony(0.65).withStanding(0.15)
            .withSoothing(0.7).withAllostaticLoad(0.8).withEquanimity(0.9)
            .withGenerativity(0.5);

        // Non-trivial feedback on all 10 coloring tanks.
        double[] deltas = {0.1, 0.1, -0.1, 0.05, -0.05, 0.2, 0.1, -0.2, 0.0, 0.1};
        var after = state.applyColoringFeedback(deltas, 1.0);

        // Every deprivation/protective tank is untouched.
        assertThat(after.restlessness()).isEqualTo(0.6);
        assertThat(after.loneliness()).isEqualTo(0.5);
        assertThat(after.stagnation()).isEqualTo(0.4);
        assertThat(after.autonomyPressure()).isEqualTo(0.3);
        assertThat(after.significance()).isEqualTo(0.35);
        assertThat(after.amae()).isEqualTo(0.45);
        assertThat(after.saudade()).isEqualTo(0.55);
        assertThat(after.obligation()).isEqualTo(0.25);
        assertThat(after.harmony()).isEqualTo(0.65);
        assertThat(after.standing()).isEqualTo(0.15);
        assertThat(after.soothing()).isEqualTo(0.7);
        assertThat(after.allostaticLoad()).isEqualTo(0.8);
        assertThat(after.equanimity()).isEqualTo(0.9);
        assertThat(after.generativity()).isEqualTo(0.5);

        // …and the coloring deltas WERE applied (clamped).
        assertThat(after.confidence()).isEqualTo(0.6, within(1e-9));   // 0.5 + 0.1
        assertThat(after.momentum()).isEqualTo(0.2, within(1e-9));     // 0.0 + 0.2
        assertThat(after.energy()).isEqualTo(0.9, within(1e-9));       // 1.0 - 0.1
    }

    @Test void applyColoringFeedback_tolerates_short_delta_array() {
        var state = VitalityState.initial().withRestlessness(0.7).withGenerativity(0.3);
        var after = state.applyColoringFeedback(new double[]{0.05, 0.05}, 1.0); // only 2 deltas
        assertThat(after.restlessness()).isEqualTo(0.7);  // still preserved
        assertThat(after.generativity()).isEqualTo(0.3);
        assertThat(after.contextBudget()).isEqualTo(0.55, within(1e-9)); // 0.5 + 0.05
    }
}
