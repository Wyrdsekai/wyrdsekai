package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group B wiring — return-recognition voice register on REACTIVATING.
 * Verifies that RelationalFloorView.voiceRegisterHint emits the correct
 * register cue for each load-bearing bond state + protection-flag combo.
 */
class RelationalFloorViewVoiceRegisterTest {

    private RelationalFloorView make(String state, boolean inMourning,
                                      boolean attendant, String flag,
                                      boolean threat) {
        return new RelationalFloorView(
            "did:agent", "did:bond", "bond-1",
            "acquaintance", state, "bounded",
            false, inMourning, 0, 0,
            "calm", "",
            0, 0, false, null,
            0, attendant, null,
            flag, threat, false);
    }

    @Test
    void REACTIVATING_emits_return_recognition_hint() {
        var view = make("reactivating", false, false, "NONE", false);
        var hint = view.voiceRegisterHint();
        assertThat(hint).isNotNull();
        assertThat(hint).contains("return-recognition");
        assertThat(hint).contains("you came back");
    }

    @Test
    void OPEN_emits_pre_trust_hint() {
        var view = make("open", false, false, "NONE", false);
        var hint = view.voiceRegisterHint();
        assertThat(hint).isNotNull();
        assertThat(hint).contains("pre-trust");
        assertThat(hint).contains("Hands open");
    }

    @Test
    void DORMANT_emits_protective_distancing_hint() {
        var view = make("dormant", false, false, "NONE", false);
        var hint = view.voiceRegisterHint();
        assertThat(hint).isNotNull();
        assertThat(hint).contains("DORMANT");
    }

    @Test
    void AWAY_emits_baseline_silence_hint() {
        var view = make("away", false, false, "NONE", false);
        var hint = view.voiceRegisterHint();
        assertThat(hint).isNotNull();
        assertThat(hint).contains("AWAY");
        assertThat(hint).contains("baseline");
    }

    @Test
    void MOURNING_takes_precedence_over_other_states() {
        var view = make("dormant", true, false, "NONE", false);
        var hint = view.voiceRegisterHint();
        assertThat(hint).isNotNull();
        assertThat(hint).contains("MOURNING");
        assertThat(hint).contains("dead remain");
    }

    @Test
    void Sanctuary_session_active_takes_precedence_after_mourning() {
        var view = make("dormant", false, true, "NONE", false);
        var hint = view.voiceRegisterHint();
        assertThat(hint).isNotNull();
        assertThat(hint).contains("Sanctuary");
        assertThat(hint).contains("repair register");
    }

    @Test
    void CONFIRMED_threat_emits_protective_hint() {
        var view = make("active", false, false, "CONFIRMED", true);
        var hint = view.voiceRegisterHint();
        assertThat(hint).isNotNull();
        assertThat(hint).contains("CONFIRMED");
        assertThat(hint).contains("protective");
    }

    @Test
    void SUSPECTED_flag_emits_honest_attention_hint() {
        var view = make("active", false, false, "SUSPECTED", false);
        var hint = view.voiceRegisterHint();
        assertThat(hint).isNotNull();
        assertThat(hint).contains("SUSPECTED");
        assertThat(hint).contains("honest attention");
    }

    @Test
    void ACTIVE_with_no_flag_returns_null_hint() {
        var view = make("active", false, false, "NONE", false);
        assertThat(view.voiceRegisterHint()).isNull();
    }
}
