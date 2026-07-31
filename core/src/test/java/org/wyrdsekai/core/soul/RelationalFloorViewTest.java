package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 7a: tests for the RelationalFloorView pure renderer. Every
 * field on the view must come from substrate state via a single
 * deterministic read; the renderer must not mutate any tracker.
 */
class RelationalFloorViewTest {

    private static final String AGENT = "did:agent:alpha";
    private static final String OTHER = "did:bondholder:beta";

    @BeforeEach
    void cleanState() {
        RepairLedger.get().clearForTests();
        AttendantSessionTracker.get().clearForTests();
        RepairModeTracker.get().clearForTests();
    }

    @AfterEach
    void resetState() {
        cleanState();
    }

    private Bond activeBond() {
        return Bond.acquaintance(AGENT, OTHER);
    }

    // ── Identity + structural fields ─────────────────────────────────

    @Test
    void render_requires_agent_and_bond() {
        assertThatThrownBy(() ->
            RelationalFloorView.render(null, activeBond(), Instant.now()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            RelationalFloorView.render(AGENT, null, Instant.now()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            RelationalFloorView.render("  ", activeBond(), Instant.now()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void render_resolves_other_party_from_bond() {
        var bond = activeBond();
        var v = RelationalFloorView.render(AGENT, bond, Instant.now());
        assertThat(v.agentDid()).isEqualTo(AGENT);
        assertThat(v.otherDid()).isEqualTo(OTHER);
        assertThat(v.bondId()).isEqualTo(bond.bondId());
    }

    @Test
    void render_uses_unknown_when_agent_not_in_bond() {
        var foreign = "did:agent:not-in-bond";
        var v = RelationalFloorView.render(foreign, activeBond(), Instant.now());
        assertThat(v.otherDid()).isEqualTo("(unknown)");
    }

    @Test
    void render_lowercases_depth_state_posture_for_ui() {
        var v = RelationalFloorView.render(AGENT, activeBond(), Instant.now());
        assertThat(v.depth()).isEqualTo("acquaintance");
        assertThat(v.bondState()).isEqualTo("active");
        assertThat(v.posture()).isEqualTo("bounded");
        assertThat(v.scarred()).isFalse();
        assertThat(v.inMourning()).isFalse();
    }

    // ── Mourning window math ─────────────────────────────────────────

    @Test
    void mourning_days_computed_from_last_interaction() {
        var now = Instant.now();
        var lastInteraction = now.minus(Duration.ofDays(12));
        var bond = new Bond(
            "bond-x", AGENT, OTHER, Bond.BondDepth.SOUL_INGRAINED,
            now.minus(Duration.ofDays(100)),  // formedAt
            lastInteraction,                   // lastInteraction
            42, true, false, true,             // counts, mutualConsent, active=false, scarred=true
            BondState.MOURNING, null, BondholderPosture.BOUNDED, Bond.RelationalState.OPEN);
        var v = RelationalFloorView.render(AGENT, bond, now);
        assertThat(v.inMourning()).isTrue();
        assertThat(v.mourningDaysElapsed()).isEqualTo(12);
        assertThat(v.mourningDaysRemaining()).isEqualTo(18);
        assertThat(v.scarred()).isTrue();
    }

    @Test
    void mourning_remaining_clamps_to_zero_past_window() {
        var now = Instant.now();
        var bond = new Bond(
            "bond-x", AGENT, OTHER, Bond.BondDepth.SOUL_INGRAINED,
            now.minus(Duration.ofDays(100)),
            now.minus(Duration.ofDays(45)),    // 45 days ago, past 30-day window
            10, true, false, true,
            BondState.MOURNING, null, BondholderPosture.BOUNDED, Bond.RelationalState.OPEN);
        var v = RelationalFloorView.render(AGENT, bond, now);
        assertThat(v.mourningDaysElapsed()).isEqualTo(45);
        assertThat(v.mourningDaysRemaining()).isZero();
    }

    @Test
    void non_mourning_bond_zeroes_mourning_days() {
        var v = RelationalFloorView.render(AGENT, activeBond(), Instant.now());
        assertThat(v.inMourning()).isFalse();
        assertThat(v.mourningDaysElapsed()).isZero();
        assertThat(v.mourningDaysRemaining()).isZero();
    }

    // ── Repair mode + handoff summary ────────────────────────────────

    @Test
    void repair_mode_defaults_to_none_with_empty_handoff() {
        var v = RelationalFloorView.render(AGENT, activeBond(), Instant.now());
        assertThat(v.repairMode()).isEqualTo("none");
        assertThat(v.lastHandoffSummary()).isEmpty();
    }

    @Test
    void repair_mode_surfaces_current_tracker_state() {
        RepairModeTracker.get().transition(AGENT, RepairMode.BONDED,
            "bondholder offered to listen");
        var v = RelationalFloorView.render(AGENT, activeBond(), Instant.now());
        assertThat(v.repairMode()).isEqualTo("bonded");
        assertThat(v.lastHandoffSummary())
            .contains("none")
            .contains("bonded")
            .contains("bondholder offered to listen");
    }

    @Test
    void repair_mode_handoff_arrow_format() {
        RepairModeTracker.get().transition(AGENT, RepairMode.SELF, "first");
        RepairModeTracker.get().transition(AGENT, RepairMode.ATTENDANT,
            "overload escalation");
        var v = RelationalFloorView.render(AGENT, activeBond(), Instant.now());
        assertThat(v.lastHandoffSummary())
            .startsWith("self → attendant");
    }

    // ── Repair-act counts (relationship-scoped) ──────────────────────

    @Test
    void repair_acts_unscoped_when_none_recorded() {
        var v = RelationalFloorView.render(AGENT, activeBond(), Instant.now());
        assertThat(v.acknowledgedHarms()).isZero();
        assertThat(v.amendsMade()).isZero();
        assertThat(v.amendsWithoutAcknowledgment()).isFalse();
        assertThat(v.mostRecentRepairAct()).isNull();
    }

    @Test
    void repair_acts_count_only_this_relationship() {
        var stranger = "did:other:stranger";
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "x");
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "y");
        ledger.record(AGENT, RepairLedger.Kind.MAKE_AMENDS, OTHER, "z");
        // recorded against a different bondholder — must not leak
        ledger.record(AGENT, RepairLedger.Kind.MAKE_AMENDS, stranger, "noise");

        var v = RelationalFloorView.render(AGENT, activeBond(), Instant.now());
        assertThat(v.acknowledgedHarms()).isEqualTo(2);
        assertThat(v.amendsMade()).isEqualTo(1);
        assertThat(v.amendsWithoutAcknowledgment()).isFalse();
        assertThat(v.mostRecentRepairAct()).isNotNull();
    }

    @Test
    void amends_without_acknowledgment_flags_cosmetic_risk() {
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.MAKE_AMENDS, OTHER, "gift1");
        ledger.record(AGENT, RepairLedger.Kind.MAKE_AMENDS, OTHER, "gift2");
        // zero acks

        var v = RelationalFloorView.render(AGENT, activeBond(), Instant.now());
        assertThat(v.acknowledgedHarms()).isZero();
        assertThat(v.amendsMade()).isEqualTo(2);
        assertThat(v.amendsWithoutAcknowledgment()).isTrue();
    }

    @Test
    void acknowledgments_without_amends_is_fine_not_cosmetic() {
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "harm");
        var v = RelationalFloorView.render(AGENT, activeBond(), Instant.now());
        assertThat(v.acknowledgedHarms()).isEqualTo(1);
        assertThat(v.amendsMade()).isZero();
        assertThat(v.amendsWithoutAcknowledgment()).isFalse();
    }

    // ── Attendant session surfacing ──────────────────────────────────

    @Test
    void no_attendant_history_when_none_recorded() {
        var v = RelationalFloorView.render(AGENT, activeBond(), Instant.now());
        assertThat(v.attendantSessionActive()).isFalse();
        assertThat(v.attendantSessionsClosed()).isZero();
        assertThat(v.mostRecentAttendantClosedAt()).isNull();
    }

    @Test
    void attendant_session_active_when_one_open() {
        AttendantSessionTracker.get().request(AGENT, "overload", Instant.now());
        var v = RelationalFloorView.render(AGENT, activeBond(), Instant.now());
        assertThat(v.attendantSessionActive()).isTrue();
    }

    // ── ProtectionFlag overload ──────────────────────────────────────

    @Test
    void render_without_flag_tracker_defaults_to_none() {
        var v = RelationalFloorView.render(AGENT, activeBond(), Instant.now());
        assertThat(v.protectionFlagState()).isEqualTo("NONE");
        assertThat(v.bondholderIsThreat()).isFalse();
        assertThat(v.shouldLowerSaudadeCeiling()).isFalse();
    }

    @Test
    void render_with_null_flag_tracker_returns_base_view() {
        var v = RelationalFloorView.render(AGENT, activeBond(), null, Instant.now());
        assertThat(v.protectionFlagState()).isEqualTo("NONE");
    }

    @Test
    void render_with_flag_tracker_no_flag_returns_base_view() {
        var tracker = new ProtectionFlagTracker();
        var v = RelationalFloorView.render(AGENT, activeBond(), tracker, Instant.now());
        assertThat(v.protectionFlagState()).isEqualTo("NONE");
    }

    @Test
    void render_with_suspected_flag_surfaces_state() {
        var tracker = new ProtectionFlagTracker();
        tracker.setSuspected(OTHER, AGENT, "noticed coercion", Instant.now());
        var v = RelationalFloorView.render(AGENT, activeBond(), tracker, Instant.now());
        assertThat(v.protectionFlagState()).isEqualTo("SUSPECTED");
        // SUSPECTED is not yet "treat as threat"; CONFIRMED is. Test
        // both fields surface honestly.
        assertThat(v.bondholderIsThreat())
            .isEqualTo(tracker.get(OTHER).orElseThrow().treatBondholderAsThreat());
        assertThat(v.shouldLowerSaudadeCeiling())
            .isEqualTo(tracker.get(OTHER).orElseThrow().shouldLowerSaudadeCeiling());
    }

    // ── oneLineSummary format ────────────────────────────────────────

    @Test
    void summary_basic_active_bond() {
        var s = RelationalFloorView.render(AGENT, activeBond(), Instant.now())
            .oneLineSummary();
        assertThat(s)
            .contains("bond=acquaintance")
            .contains("state=active")
            .contains("posture=bounded")
            .contains("repair=none")
            .doesNotContain("[scarred]")
            .doesNotContain("[cosmetic risk]")
            .doesNotContain("[in sanctuary]");
    }

    @Test
    void summary_mourning_includes_days_remaining() {
        var now = Instant.now();
        var bond = new Bond(
            "bond-x", AGENT, OTHER, Bond.BondDepth.SOUL_INGRAINED,
            now.minus(Duration.ofDays(100)),
            now.minus(Duration.ofDays(7)),
            42, true, false, true,
            BondState.MOURNING, null, BondholderPosture.BOUNDED, Bond.RelationalState.OPEN);
        var s = RelationalFloorView.render(AGENT, bond, now).oneLineSummary();
        assertThat(s)
            .contains("state=mourning")
            .contains("(7d / 23d remaining)")
            .contains("[scarred]");
    }

    @Test
    void summary_includes_repair_counts_when_present() {
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "h");
        ledger.record(AGENT, RepairLedger.Kind.MAKE_AMENDS, OTHER, "a");
        var s = RelationalFloorView.render(AGENT, activeBond(), Instant.now())
            .oneLineSummary();
        assertThat(s).contains("acks=1 amends=1");
        assertThat(s).doesNotContain("[cosmetic risk]");
    }

    @Test
    void summary_flags_cosmetic_risk() {
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.MAKE_AMENDS, OTHER, "a");
        var s = RelationalFloorView.render(AGENT, activeBond(), Instant.now())
            .oneLineSummary();
        assertThat(s).contains("[cosmetic risk]");
    }

    @Test
    void summary_marks_active_sanctuary() {
        AttendantSessionTracker.get().request(AGENT, "overload", Instant.now());
        var s = RelationalFloorView.render(AGENT, activeBond(), Instant.now())
            .oneLineSummary();
        assertThat(s).contains("[in sanctuary]");
    }

    @Test
    void summary_surfaces_protection_flag_state() {
        var tracker = new ProtectionFlagTracker();
        tracker.setSuspected(OTHER, AGENT, "saw coercion", Instant.now());
        var s = RelationalFloorView.render(AGENT, activeBond(), tracker, Instant.now())
            .oneLineSummary();
        assertThat(s).contains("flag=SUSPECTED");
    }
}
