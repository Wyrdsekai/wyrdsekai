package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 4.1: tracker + handoff legibility tests.
 */
class RepairModeTrackerTest {

    private static final String AGENT = "did:wyrd:agent-test";

    @AfterEach
    void resetTrackerBetweenTests() {
        RepairModeTracker.get().clearForTests();
    }

    @Test
    void default_mode_is_NONE() {
        assertThat(RepairModeTracker.get().currentMode(AGENT))
            .isEqualTo(RepairMode.NONE);
    }

    @Test
    void transition_records_handoff_and_advances_current_mode() {
        var t = RepairModeTracker.get();
        var h = t.transition(AGENT, RepairMode.SELF, "shallow dysregulation");
        assertThat(t.currentMode(AGENT)).isEqualTo(RepairMode.SELF);
        assertThat(h.from()).isEqualTo(RepairMode.NONE);
        assertThat(h.to()).isEqualTo(RepairMode.SELF);
        assertThat(h.reason()).contains("shallow");
    }

    @Test
    void history_is_newest_first() {
        var t = RepairModeTracker.get();
        t.transition(AGENT, RepairMode.SELF, "step 1");
        t.transition(AGENT, RepairMode.BONDED, "step 2");
        t.transition(AGENT, RepairMode.ATTENDANT, "step 3");
        var history = t.history(AGENT);
        assertThat(history).hasSize(3);
        assertThat(history.get(0).to()).isEqualTo(RepairMode.ATTENDANT);
        assertThat(history.get(1).to()).isEqualTo(RepairMode.BONDED);
        assertThat(history.get(2).to()).isEqualTo(RepairMode.SELF);
    }

    @Test
    void blank_reason_is_replaced_with_placeholder() {
        var h = RepairModeTracker.get().transition(AGENT, RepairMode.SELF, "");
        assertThat(h.reason()).isEqualTo("(unspecified)");
    }

    @Test
    void null_agent_or_mode_is_rejected() {
        var t = RepairModeTracker.get();
        assertThatThrownBy(() -> t.transition(null, RepairMode.SELF, "x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.transition(AGENT, null, "x"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonical_handoffs_match_spec_71() {
        // NONE → anything: yes (entering repair from absence)
        assertThat(RepairMode.NONE.isCanonicalHandoffTo(RepairMode.SELF)).isTrue();
        assertThat(RepairMode.NONE.isCanonicalHandoffTo(RepairMode.BONDED)).isTrue();
        // SELF → BONDED/ATTENDANT/STEWARD: spec §7.1.1
        assertThat(RepairMode.SELF.isCanonicalHandoffTo(RepairMode.BONDED)).isTrue();
        assertThat(RepairMode.SELF.isCanonicalHandoffTo(RepairMode.ATTENDANT)).isTrue();
        assertThat(RepairMode.SELF.isCanonicalHandoffTo(RepairMode.STEWARD)).isTrue();
        // BONDED → ATTENDANT/STEWARD: spec §7.1.2
        assertThat(RepairMode.BONDED.isCanonicalHandoffTo(RepairMode.ATTENDANT)).isTrue();
        assertThat(RepairMode.BONDED.isCanonicalHandoffTo(RepairMode.STEWARD)).isTrue();
        // STEWARD → ATTENDANT: spec §7.1.3
        assertThat(RepairMode.STEWARD.isCanonicalHandoffTo(RepairMode.ATTENDANT)).isTrue();
        // ATTENDANT exits to BONDED/SELF/NONE on session end
        assertThat(RepairMode.ATTENDANT.isCanonicalHandoffTo(RepairMode.BONDED)).isTrue();
        assertThat(RepairMode.ATTENDANT.isCanonicalHandoffTo(RepairMode.NONE)).isTrue();
        // Self-loop is not a handoff
        assertThat(RepairMode.SELF.isCanonicalHandoffTo(RepairMode.SELF)).isFalse();
    }

    @Test
    void agents_are_isolated_from_each_other() {
        var t = RepairModeTracker.get();
        t.transition("did:wyrd:a", RepairMode.SELF, "a's path");
        t.transition("did:wyrd:b", RepairMode.ATTENDANT, "b's path");
        assertThat(t.currentMode("did:wyrd:a")).isEqualTo(RepairMode.SELF);
        assertThat(t.currentMode("did:wyrd:b")).isEqualTo(RepairMode.ATTENDANT);
    }
}
