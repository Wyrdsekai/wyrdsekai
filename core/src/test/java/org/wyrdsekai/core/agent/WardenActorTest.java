package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WardenActorTest {

    // --- Profile ---

    @Test void profile_has_correct_defaults() {
        var profile = Wardens.WARD_WARDEN;
        assertThat(profile.name()).isEqualTo("Sentinel");
        assertThat(profile.entityId()).isEqualTo("warden-sentinel");
        assertThat(profile.entityType()).isEqualTo("agent");
        assertThat(profile.contextWindowTokens()).isEqualTo(4096);
        assertThat(profile.maxResponseTokens()).isEqualTo(384);
        assertThat(profile.temperature()).isEqualTo(0.4);
    }

    @Test void profile_system_prompt_mentions_warden() {
        var profile = Wardens.WARD_WARDEN;
        assertThat(profile.systemPrompt()).contains("Sentinel");
        assertThat(profile.systemPrompt()).contains("Warden");
        assertThat(profile.systemPrompt()).contains("Ward Room");
    }

    @Test void profile_system_prompt_mentions_circuit_breaker() {
        var profile = Wardens.WARD_WARDEN;
        assertThat(profile.systemPrompt()).contains("circuit breaker");
    }

    // --- Vitality tuning ---

    @Test void initial_vitality_tuned_for_warden() {
        var vitality = VitalityState.initial()
            .withConfidence(0.7)
            .withAlignment(0.6)
            .withRapport(0.2)
            .withFocus(0.8);

        assertThat(vitality.confidence()).isEqualTo(0.7);
        assertThat(vitality.alignment()).isEqualTo(0.6);
        assertThat(vitality.rapport()).isEqualTo(0.2);
        assertThat(vitality.focus()).isEqualTo(0.8);
    }

    @Test void modulation_with_warden_profile() {
        var vitality = VitalityState.initial()
            .withConfidence(0.7)
            .withFocus(0.8);
        var mod = VitalityModulation.compute(vitality, Wardens.WARD_WARDEN);

        // DriveModulatedSampling uses base 0.7, modulated by drives+tanks
        assertThat(mod.temperature()).isGreaterThan(0.0);
        assertThat(mod.maxResponseTokens()).isGreaterThan(0);
    }

    // --- Security context ---

    @Test void security_context_includes_circuit_breaker() {
        var profile = Wardens.WARD_WARDEN;
        var messages = PromptAssembler.assemble(
            profile, null, List.of(), null,
            VitalityState.initial(), List.of(),
            "[Security status]\nCircuit breaker: Judgment clear (authority: 100%)\n");

        var systemMessages = messages.stream()
            .filter(m -> m.role().equals("system"))
            .toList();
        assertThat(systemMessages.stream()
            .anyMatch(m -> m.content().contains("Security status"))).isTrue();
    }

    @Test void security_context_observation_mode() {
        var messages = PromptAssembler.assemble(
            Wardens.WARD_WARDEN, null, List.of(), null,
            VitalityState.initial(), List.of(),
            "[Security status]\nMODE: Observation only\n");

        assertThat(messages.stream()
            .anyMatch(m -> m.content().contains("Observation only"))).isTrue();
    }

    // --- Circuit breaker integration ---

    @Test void circuit_breaker_describe_in_context() {
        var cb = CircuitBreaker.initial();
        assertThat(cb.describe()).contains("clear");
        assertThat(cb.describe()).contains("100%");
    }

    @Test void circuit_breaker_strained_in_context() {
        var cb = new CircuitBreaker(50, 0, 100, 0.2);
        assertThat(cb.describe()).contains("observing only");
        assertThat(cb.describe()).contains("20%");
    }

    // --- Prompt assembly with security context ---

    @Test void prompt_assembly_includes_security_before_vitality() {
        var messages = PromptAssembler.assemble(
            Wardens.WARD_WARDEN, null, List.of(), null,
            VitalityState.initial().withEnergy(0.1), List.of(),
            "[Security status]\nAll clear\n");

        int secIdx = -1;
        int vitIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).content().contains("Security status")) secIdx = i;
            if (messages.get(i).content().contains("exhausted")) vitIdx = i;
        }
        assertThat(secIdx).isGreaterThan(0);
        if (vitIdx >= 0) {
            assertThat(secIdx).isLessThan(vitIdx);
        }
    }
}
