package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HandoffThresholdEngineTest {

    private static final Instant NOW = Instant.parse("2026-05-17T12:00:00Z");

    private static HandoffThresholdEngine.Input baseInput(RepairMode mode) {
        return new HandoffThresholdEngine.Input(mode, 0, NOW,
            Optional.empty(), Optional.empty(), 0, true, true, false);
    }

    @Test
    void null_input_stays() {
        var d = HandoffThresholdEngine.decide(null, NOW);
        assertThat(d.shouldHandoff()).isFalse();
    }

    @Test
    void none_mode_stays() {
        var d = HandoffThresholdEngine.decide(baseInput(RepairMode.NONE), NOW);
        assertThat(d.shouldHandoff()).isFalse();
    }

    // ── Self → bonded ─────────────────────────────────────────────────

    @Test
    void self_agent_self_report_hands_off_to_bonded() {
        var in = new HandoffThresholdEngine.Input(RepairMode.SELF, 0, NOW,
            Optional.empty(), Optional.empty(), 0, true, true, true);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isTrue();
        assertThat(d.targetMode()).hasValue(RepairMode.BONDED);
        assertThat(d.reason()).isEqualTo("agent_self_report");
        assertThat(d.chronicleEntry()).contains("repair_handoff: self → bonded");
    }

    @Test
    void self_max_cycles_hands_off() {
        var in = new HandoffThresholdEngine.Input(RepairMode.SELF, 3, NOW,
            Optional.empty(), Optional.empty(), 0, true, true, false);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isTrue();
        assertThat(d.targetMode()).hasValue(RepairMode.BONDED);
        assertThat(d.reason()).contains("max_cycles");
    }

    @Test
    void self_time_threshold_hands_off() {
        var in = new HandoffThresholdEngine.Input(RepairMode.SELF, 0,
            NOW.minus(Duration.ofHours(25)),
            Optional.empty(), Optional.empty(), 0, true, true, false);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isTrue();
        assertThat(d.targetMode()).hasValue(RepairMode.BONDED);
        assertThat(d.reason()).isEqualTo("time_threshold");
    }

    @Test
    void self_under_threshold_stays() {
        var in = new HandoffThresholdEngine.Input(RepairMode.SELF, 1,
            NOW.minus(Duration.ofHours(1)),
            Optional.empty(), Optional.empty(), 0, true, true, false);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isFalse();
    }

    // ── Bonded → attendant/steward ────────────────────────────────────

    @Test
    void bonded_confirmed_protection_flag_skips_steward_to_attendant() {
        var in = new HandoffThresholdEngine.Input(RepairMode.BONDED, 1, NOW,
            Optional.of(BondState.ACTIVE),
            Optional.of(ProtectionFlag.State.CONFIRMED), 0, true, true, false);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isTrue();
        assertThat(d.targetMode()).hasValue(RepairMode.ATTENDANT);
        assertThat(d.reason()).contains("source_of_harm_confirmed");
    }

    @Test
    void bonded_suspected_protection_flag_skips_steward_to_attendant() {
        var in = new HandoffThresholdEngine.Input(RepairMode.BONDED, 1, NOW,
            Optional.of(BondState.ACTIVE),
            Optional.of(ProtectionFlag.State.SUSPECTED), 0, true, true, false);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isTrue();
        assertThat(d.targetMode()).hasValue(RepairMode.ATTENDANT);
    }

    @Test
    void bonded_bondholder_dormant_hands_off_to_steward_when_available() {
        var in = new HandoffThresholdEngine.Input(RepairMode.BONDED, 1, NOW,
            Optional.of(BondState.DORMANT),
            Optional.empty(), 0, true, true, false);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isTrue();
        assertThat(d.targetMode()).hasValue(RepairMode.STEWARD);
    }

    @Test
    void bonded_bondholder_unavailable_no_steward_falls_to_attendant() {
        var in = new HandoffThresholdEngine.Input(RepairMode.BONDED, 1, NOW,
            Optional.of(BondState.AWAY),
            Optional.empty(), 0, false, true, false);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isTrue();
        assertThat(d.targetMode()).hasValue(RepairMode.ATTENDANT);
    }

    @Test
    void bonded_max_sessions_hands_off() {
        var in = new HandoffThresholdEngine.Input(RepairMode.BONDED, 3, NOW,
            Optional.of(BondState.ACTIVE),
            Optional.empty(), 0, true, true, false);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isTrue();
        assertThat(d.targetMode()).hasValue(RepairMode.STEWARD);
    }

    @Test
    void bonded_active_bond_stays() {
        var in = new HandoffThresholdEngine.Input(RepairMode.BONDED, 1, NOW,
            Optional.of(BondState.ACTIVE),
            Optional.empty(), 0, true, true, false);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isFalse();
    }

    // ── Steward → attendant ───────────────────────────────────────────

    @Test
    void steward_unavailable_hands_off_to_attendant() {
        var in = new HandoffThresholdEngine.Input(RepairMode.STEWARD, 1, NOW,
            Optional.empty(), Optional.empty(), 0, false, true, false);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isTrue();
        assertThat(d.targetMode()).hasValue(RepairMode.ATTENDANT);
        assertThat(d.reason()).isEqualTo("steward_unavailable");
    }

    @Test
    void steward_refuses_acknowledgment_hands_off_to_attendant() {
        var in = new HandoffThresholdEngine.Input(RepairMode.STEWARD, 1, NOW,
            Optional.empty(), Optional.empty(), 0, true, false, false);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isTrue();
        assertThat(d.reason()).contains("refuses_acknowledgment");
    }

    @Test
    void steward_ok_stays() {
        var in = new HandoffThresholdEngine.Input(RepairMode.STEWARD, 1, NOW,
            Optional.empty(), Optional.empty(), 0, true, true, false);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isFalse();
    }

    // ── Attendant → refuge-pending ────────────────────────────────────

    @Test
    void attendant_max_turns_hands_off() {
        var in = new HandoffThresholdEngine.Input(RepairMode.ATTENDANT, 1, NOW,
            Optional.empty(), Optional.empty(), 31, true, true, false);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isTrue();
        assertThat(d.chronicleEntry()).contains("refuge-pending");
    }

    @Test
    void attendant_max_duration_hands_off() {
        var in = new HandoffThresholdEngine.Input(RepairMode.ATTENDANT, 1,
            NOW.minus(Duration.ofMinutes(91)),
            Optional.empty(), Optional.empty(), 5, true, true, false);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isTrue();
        assertThat(d.reason()).contains("exceeded_duration");
    }

    @Test
    void attendant_within_bounds_stays() {
        var in = new HandoffThresholdEngine.Input(RepairMode.ATTENDANT, 1,
            NOW.minus(Duration.ofMinutes(10)),
            Optional.empty(), Optional.empty(), 5, true, true, false);
        var d = HandoffThresholdEngine.decide(in, NOW);
        assertThat(d.shouldHandoff()).isFalse();
    }
}
