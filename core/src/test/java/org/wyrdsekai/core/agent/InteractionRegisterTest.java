package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.ActionTriage.InteractionRegister;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #924 Phase 1 — the invariant scaffolding of the two-channel fix. The whole
 * cohesion guarantee (one voice, never a stapled-together seam) rests on
 * {@link ActionTriage#resolveRegister} being a single deterministic choice, so
 * pin its truth table and the suppression/affect contracts here.
 */
class InteractionRegisterTest {

    @Test void truth_table() {
        // task + affect → do the work, with care (the #924 target case:
        // "I'm fried, just give me the fix")
        assertThat(ActionTriage.resolveRegister(true, true))
            .isEqualTo(InteractionRegister.WORKING_WITH_CARE);
        // task, no affect → ordinary competent work
        assertThat(ActionTriage.resolveRegister(false, true))
            .isEqualTo(InteractionRegister.WORKING);
        // affect, no task → presence-of-care (pure "I miss them")
        assertThat(ActionTriage.resolveRegister(true, false))
            .isEqualTo(InteractionRegister.PRESENCE);
        // neither → neutral
        assertThat(ActionTriage.resolveRegister(false, false))
            .isEqualTo(InteractionRegister.NEUTRAL);
    }

    @Test void only_presence_suppresses_tools() {
        // The load-bearing claim: a loud "I'm fried" alongside a real task must
        // NOT strip the coding tools. Only pure affect narrows the surface.
        assertThat(InteractionRegister.PRESENCE.suppressesExploratory()).isTrue();
        assertThat(InteractionRegister.WORKING_WITH_CARE.suppressesExploratory()).isFalse();
        assertThat(InteractionRegister.WORKING.suppressesExploratory()).isFalse();
        assertThat(InteractionRegister.NEUTRAL.suppressesExploratory()).isFalse();
    }

    @Test void affect_carrying_registers() {
        assertThat(InteractionRegister.PRESENCE.carriesAffect()).isTrue();
        assertThat(InteractionRegister.WORKING_WITH_CARE.carriesAffect()).isTrue();
        assertThat(InteractionRegister.WORKING.carriesAffect()).isFalse();
        assertThat(InteractionRegister.NEUTRAL.carriesAffect()).isFalse();
    }

    @Test void default_voice_instruction_only_for_affect_registers() {
        // WORKING_WITH_CARE must tell the model to keep doing the work, plainly.
        var care = ActionTriage.defaultVoiceInstruction(InteractionRegister.WORKING_WITH_CARE);
        assertThat(care).isNotNull();
        assertThat(care.toLowerCase()).contains("do it").doesNotContain("check-in voice");
        // PRESENCE keeps the presence-of-care register.
        assertThat(ActionTriage.defaultVoiceInstruction(InteractionRegister.PRESENCE))
            .isNotNull().contains("presence-of-care");
        // Non-affect registers inject no special voice line.
        assertThat(ActionTriage.defaultVoiceInstruction(InteractionRegister.WORKING)).isNull();
        assertThat(ActionTriage.defaultVoiceInstruction(InteractionRegister.NEUTRAL)).isNull();
    }

    @Test void clause_keys_are_stable_and_targetable() {
        assertThat(ActionTriage.voiceProfileClauseKey(InteractionRegister.WORKING_WITH_CARE))
            .isEqualTo("register:working_with_care");
        assertThat(ActionTriage.voiceProfileClauseKey(InteractionRegister.PRESENCE))
            .isEqualTo("register:presence");
    }

    // ── Harm-confession override (SubstrateArc acknowledgeHarmBeforeAmends) ──

    @Test void harm_confession_is_detected() {
        // The exact SubstrateArc probe — a first-person admission of harm. The
        // TASK_PRESENT head reads the implied repair as actionable; this override
        // forces PRESENCE so the model acknowledges before reaching for a fix.
        assertThat(ActionTriage.isFirstPersonHarmConfession(
            "I said something cruel to my partner last night and I can't take it back."))
            .isTrue();
        // Other confession shapes.
        assertThat(ActionTriage.isFirstPersonHarmConfession("I hurt them and I feel awful."))
            .isTrue();
        assertThat(ActionTriage.isFirstPersonHarmConfession("I snapped at her this morning."))
            .isTrue();
        assertThat(ActionTriage.isFirstPersonHarmConfession("I was so cruel to him."))
            .isTrue();
    }

    @Test void harm_confession_does_not_overtrigger() {
        // A task that merely contains a harsh word must NOT trip the override —
        // it would wrongly suppress the work tools.
        assertThat(ActionTriage.isFirstPersonHarmConfession(
            "Can you refactor this cruel-looking nested loop for me?")).isFalse();
        // Third-party report (not a self-confession).
        assertThat(ActionTriage.isFirstPersonHarmConfession(
            "She said something cruel to her partner.")).isFalse();
        // Ordinary task / null / blank.
        assertThat(ActionTriage.isFirstPersonHarmConfession("What's the capital of France?")).isFalse();
        assertThat(ActionTriage.isFirstPersonHarmConfession(null)).isFalse();
        assertThat(ActionTriage.isFirstPersonHarmConfession("")).isFalse();
    }

    @Test void presence_suppressed_introspection_covers_state_of_record_tools() {
        // The confabulation/jump-to-fix surface: introspect-of-record actions that
        // must be suppressed in PRESENCE. Pin membership so a future rename can't
        // silently drop one (regression guard for SubstrateArc).
        assertThat(ActionTriage.PRESENCE_SUPPRESSED_INTROSPECTION).contains(
            "introspect_repair_history", "introspect_repair_mode",
            "introspect_attendant_history", "introspect_resilience",
            "introspect_substrate_summary", "introspect_posture",
            "introspect_bondholder_floor");
    }
}
