package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class VitalityModulationTest {

    private static final AgentProfile PROFILE = new AgentProfile(
        "TestAgent", "test-agent", "agent", "A test agent",
        "You are a test agent.", 4096, 512, 0.7
    );

    @Test void compute_full_energy_near_max_tokens() {
        var vitality = VitalityState.initial(); // energy = 1.0
        var mod = VitalityModulation.compute(vitality, PROFILE);
        // DriveModulatedSampling with null drives, energy=1.0, focus=1.0 → near max
        assertThat(mod.maxResponseTokens()).isGreaterThan(400);
        assertThat(mod.maxResponseTokens()).isLessThanOrEqualTo(512);
    }

    @Test void compute_low_energy_reduces_tokens() {
        var vitality = VitalityState.initial().withEnergy(0.0);
        var mod = VitalityModulation.compute(vitality, PROFILE);
        assertThat(mod.maxResponseTokens()).isLessThan(PROFILE.maxResponseTokens());
        assertThat(mod.maxResponseTokens()).isGreaterThanOrEqualTo(64);
    }

    @Test void compute_min_token_floor() {
        var vitality = VitalityState.initial().withEnergy(0.0);
        var mod = VitalityModulation.compute(vitality, PROFILE);
        assertThat(mod.maxResponseTokens()).isGreaterThanOrEqualTo(64);
    }

    @Test void compute_high_confidence_vs_low_confidence_temperature() {
        // Higher confidence → higher top_p (bolder) via DriveModulatedSampling
        var highConf = VitalityState.initial().withConfidence(1.0);
        var lowConf = VitalityState.initial().withConfidence(0.0);
        var modHigh = VitalityModulation.compute(highConf, PROFILE);
        var modLow = VitalityModulation.compute(lowConf, PROFILE);
        assertThat(modHigh.topP()).isGreaterThan(modLow.topP());
    }

    @Test void compute_with_drives_modulates_temperature() {
        var vitality = VitalityState.initial();
        var creative = new DriveState(0, 0, 0, 0, 0, 0, 0, 0.9);
        var grieving = new DriveState(0, 0, 0, 0, 0, 0.9, 0, 0);

        var modCreative = VitalityModulation.compute(vitality, creative, PROFILE);
        var modGrieving = VitalityModulation.compute(vitality, grieving, PROFILE);

        assertThat(modCreative.temperature()).isGreaterThan(modGrieving.temperature());
    }

    @Test void compute_backward_compatible_without_drives() {
        var vitality = VitalityState.initial();
        // Old 2-arg call should still work
        var mod = VitalityModulation.compute(vitality, PROFILE);
        assertThat(mod.temperature()).isGreaterThan(0.0);
        assertThat(mod.maxResponseTokens()).isGreaterThan(0);
        assertThat(mod.debounceDelay()).isGreaterThan(Duration.ZERO);
    }

    @Test void compute_high_momentum_shorter_debounce() {
        var highMomentum = VitalityState.initial().withMomentum(1.0);
        var lowMomentum = VitalityState.initial().withMomentum(0.0);
        var modHigh = VitalityModulation.compute(highMomentum, PROFILE);
        var modLow = VitalityModulation.compute(lowMomentum, PROFILE);
        assertThat(modHigh.debounceDelay()).isLessThan(modLow.debounceDelay());
    }

    @Test void compute_high_focus_larger_history() {
        var highFocus = VitalityState.initial().withFocus(1.0);
        var lowFocus = VitalityState.initial().withFocus(0.0);
        var modHigh = VitalityModulation.compute(highFocus, PROFILE);
        var modLow = VitalityModulation.compute(lowFocus, PROFILE);
        assertThat(modHigh.conversationHistorySize())
            .isGreaterThan(modLow.conversationHistorySize());
    }

    @Test void compute_low_focus_min_history() {
        var vitality = VitalityState.initial().withFocus(0.0);
        var mod = VitalityModulation.compute(vitality, PROFILE);
        assertThat(mod.conversationHistorySize()).isGreaterThanOrEqualTo(5);
    }

    @Test void compute_includes_new_sampling_params() {
        var vitality = VitalityState.initial();
        var drives = new DriveState(0.8, 0, 0, 0, 0, 0, 0, 0); // seeking=0.8
        var mod = VitalityModulation.compute(vitality, drives, PROFILE);
        // Should have topP and penalties populated from DriveModulatedSampling
        assertThat(mod.topP()).isGreaterThan(0.0);
        assertThat(mod.presencePenalty()).isGreaterThan(0.0);
        assertThat(mod.repetitionPenalty()).isGreaterThan(0.0);
    }
}
