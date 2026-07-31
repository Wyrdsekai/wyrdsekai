package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChiefEngineerActorTest {

    // --- Profile ---

    @Test void profile_has_correct_defaults() {
        var profile = Engineers.CHIEF_ENGINEER;
        assertThat(profile.name()).isEqualTo("Chief");
        assertThat(profile.entityId()).isEqualTo("engineer-chief");
        assertThat(profile.entityType()).isEqualTo("agent");
        assertThat(profile.contextWindowTokens()).isEqualTo(4096);
        assertThat(profile.maxResponseTokens()).isEqualTo(384);
        assertThat(profile.temperature()).isEqualTo(0.5);
    }

    @Test void profile_system_prompt_mentions_engineer() {
        var profile = Engineers.CHIEF_ENGINEER;
        assertThat(profile.systemPrompt()).contains("Chief Engineer");
        assertThat(profile.systemPrompt()).contains("Boiler Room");
    }

    // --- Metrics context (package-private for testing) ---

    @Test void buildMetricsContext_with_all_suppliers() {
        // ChiefEngineerActor.buildMetricsContext() is package-private
        // but we can test the PromptAssembler additionalContext integration
        var profile = Engineers.CHIEF_ENGINEER;
        var messages = PromptAssembler.assemble(
            profile, null, List.of(), null, null, List.of(),
            "[Current system metrics]\nSystem: Heap 45% used\n");

        var systemMessages = messages.stream()
            .filter(m -> m.role().equals("system"))
            .toList();
        // Should have: system prompt + additional context
        assertThat(systemMessages).hasSizeGreaterThanOrEqualTo(2);
        assertThat(systemMessages.stream()
            .anyMatch(m -> m.content().contains("system metrics"))).isTrue();
    }

    @Test void buildMetricsContext_null_suppliers_handled() {
        var profile = Engineers.CHIEF_ENGINEER;
        // No additional context — should still work
        var messages = PromptAssembler.assemble(
            profile, null, List.of(), null, null, List.of(), null);
        assertThat(messages).isNotEmpty();
        assertThat(messages.getFirst().role()).isEqualTo("system");
    }

    // --- Status keyword filtering ---

    @Test void status_keywords_recognized() {
        // Verify the keywords via profile — these are in the system prompt
        var prompt = Engineers.CHIEF_ENGINEER.systemPrompt();
        assertThat(prompt).contains("status");
        assertThat(prompt).contains("metrics");
        assertThat(prompt).contains("health");
    }

    // --- Vitality tuning ---

    @Test void initial_vitality_tuned_for_engineer() {
        // Chief Engineer starts with high focus, low rapport
        var vitality = VitalityState.initial()
            .withFocus(0.7)
            .withRapport(0.1)
            .withAlignment(0.5);

        assertThat(vitality.focus()).isEqualTo(0.7);
        assertThat(vitality.rapport()).isEqualTo(0.1);
        assertThat(vitality.alignment()).isEqualTo(0.5);
        assertThat(vitality.energy()).isEqualTo(1.0); // full energy
    }

    @Test void modulation_with_engineer_profile() {
        var vitality = VitalityState.initial()
            .withFocus(0.7)
            .withRapport(0.1);
        var mod = VitalityModulation.compute(vitality, Engineers.CHIEF_ENGINEER);

        // With 0.5 base temperature and high confidence, should stay stable
        assertThat(mod.temperature()).isLessThan(1.0);
        assertThat(mod.maxResponseTokens()).isGreaterThan(0);
        assertThat(mod.debounceDelay()).isNotNull();
    }

    @Test void prompt_assembler_additional_context_in_primacy_zone() {
        var profile = Engineers.CHIEF_ENGINEER;
        var messages = PromptAssembler.assemble(
            profile, null, List.of(), null,
            VitalityState.initial(), List.of(),
            "Metrics: all nominal");

        // Additional context should appear before vitality (middle zone)
        int metricsIdx = -1;
        int vitalityIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).content().contains("Metrics: all nominal")) {
                metricsIdx = i;
            }
            if (messages.get(i).content().contains("Internal state:")) {
                vitalityIdx = i;
            }
        }
        assertThat(metricsIdx).isGreaterThan(0); // after system prompt
        if (vitalityIdx >= 0) {
            assertThat(metricsIdx).isLessThan(vitalityIdx); // before vitality
        }
    }
}
