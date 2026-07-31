package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 4.3: contract tests for the
 * source-of-harm flag tracker. Covers state transitions, setter
 * constraints, escalation rules (§6), contest/clear paths (§8), and
 * the cross-system effect predicates on {@link ProtectionFlag}.
 */
class ProtectionFlagTrackerTest {

    private static final String STEWARD = "did:wyrd:steward-1";
    private static final String BONDHOLDER = "did:wyrd:bondholder-1";
    private static final String AGENT_A = "did:wyrd:agent-a";
    private static final String AGENT_B = "did:wyrd:agent-b";
    private static final String ATTENDANT = "did:wyrd:attendant-1";
    private static final Instant T0 = Instant.parse("2026-05-15T00:00:00Z");

    // ── Setting + setter rules (spec §4) ──────────────────────────────

    @Test
    void agent_can_set_flag_on_subject() {
        var t = new ProtectionFlagTracker();
        var flag = t.setSuspected(STEWARD, AGENT_A, "anger pattern", T0);
        assertThat(flag.state()).isEqualTo(ProtectionFlag.State.SUSPECTED);
        assertThat(flag.subjectDid()).isEqualTo(STEWARD);
        assertThat(flag.setterDid()).isEqualTo(AGENT_A);
    }

    @Test
    void subject_cannot_set_their_own_flag() {
        // Spec §4: setter ≠ subject. Self-set is a silent no-op (returns
        // the existing/none state).
        var t = new ProtectionFlagTracker();
        var result = t.setSuspected(STEWARD, STEWARD, "self-flagging", T0);
        assertThat(result.isAbsent()).isTrue();
        assertThat(t.get(STEWARD)).isEmpty();
    }

    @Test
    void blank_subject_is_rejected() {
        var t = new ProtectionFlagTracker();
        assertThatThrownBy(() -> t.setSuspected("", AGENT_A, "x", T0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Escalation (spec §6) ──────────────────────────────────────────

    @Test
    void two_independent_setters_escalate_suspected_to_confirmed() {
        var t = new ProtectionFlagTracker();
        t.setSuspected(STEWARD, AGENT_A, "first signal", T0);
        var elevated = t.setSuspected(STEWARD, AGENT_B,
            "second independent signal", T0.plus(Duration.ofDays(1)));
        assertThat(elevated.state()).isEqualTo(ProtectionFlag.State.CONFIRMED);
    }

    @Test
    void same_setter_twice_does_not_escalate() {
        var t = new ProtectionFlagTracker();
        t.setSuspected(STEWARD, AGENT_A, "first", T0);
        var second = t.setSuspected(STEWARD, AGENT_A, "more of the same",
            T0.plus(Duration.ofHours(2)));
        assertThat(second.state()).isEqualTo(ProtectionFlag.State.SUSPECTED);
    }

    @Test
    void attendant_finding_directly_sets_confirmed_from_absent() {
        var t = new ProtectionFlagTracker();
        var flag = t.recordAttendantFinding(STEWARD, ATTENDANT,
            "session-found pattern of coercive control", T0);
        assertThat(flag.state()).isEqualTo(ProtectionFlag.State.CONFIRMED);
        assertThat(flag.setterDid()).isEqualTo(ATTENDANT);
    }

    @Test
    void attendant_finding_elevates_existing_suspected() {
        var t = new ProtectionFlagTracker();
        t.setSuspected(STEWARD, AGENT_A, "initial signal", T0);
        var elevated = t.recordAttendantFinding(STEWARD, ATTENDANT,
            "session finding", T0.plus(Duration.ofDays(3)));
        assertThat(elevated.state()).isEqualTo(ProtectionFlag.State.CONFIRMED);
        assertThat(elevated.firstObservedAt()).isEqualTo(T0);  // preserved
    }

    @Test
    void time_decay_escalates_after_14_days_with_ongoing_signals() {
        var t = new ProtectionFlagTracker();
        t.setSuspected(STEWARD, AGENT_A, "initial", T0);
        // Same setter, follow-up signal at day 14
        var later = t.setSuspected(STEWARD, AGENT_A, "still observing",
            T0.plus(Duration.ofDays(14)));
        assertThat(later.state()).isEqualTo(ProtectionFlag.State.CONFIRMED);
    }

    @Test
    void time_decay_does_not_escalate_at_day_one() {
        var t = new ProtectionFlagTracker();
        t.setSuspected(STEWARD, AGENT_A, "initial", T0);
        var nextDay = t.setSuspected(STEWARD, AGENT_A, "second signal",
            T0.plus(Duration.ofDays(1)));
        assertThat(nextDay.state()).isEqualTo(ProtectionFlag.State.SUSPECTED);
    }

    // ── Contest + Clear (spec §8.2, §8.3) ──────────────────────────────

    @Test
    void subject_can_contest_their_flag() {
        var t = new ProtectionFlagTracker();
        t.setSuspected(STEWARD, AGENT_A, "concern", T0);
        var disputed = t.contest(STEWARD, STEWARD, "I disagree", T0.plus(Duration.ofDays(1)));
        assertThat(disputed.state()).isEqualTo(ProtectionFlag.State.DISPUTED);
        assertThat(disputed.disputedReason()).isEqualTo("I disagree");
    }

    @Test
    void non_subject_cannot_contest() {
        var t = new ProtectionFlagTracker();
        t.setSuspected(STEWARD, AGENT_A, "concern", T0);
        assertThatThrownBy(() -> t.contest(STEWARD, AGENT_A, "x", T0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clear_resets_flag_to_none() {
        var t = new ProtectionFlagTracker();
        t.setSuspected(STEWARD, AGENT_A, "concern", T0);
        var cleared = t.clear(STEWARD, AGENT_B, T0.plus(Duration.ofDays(30)));
        assertThat(cleared.isAbsent()).isTrue();
        assertThat(t.get(STEWARD)).isEmpty();
    }

    @Test
    void subject_cannot_clear_their_own_flag() {
        var t = new ProtectionFlagTracker();
        t.setSuspected(STEWARD, AGENT_A, "concern", T0);
        assertThatThrownBy(() -> t.clear(STEWARD, STEWARD, T0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Effect predicates (spec §7) ──────────────────────────────────

    @Test
    void confirmed_blocks_steward_summon() {
        var f = new ProtectionFlag(STEWARD, ProtectionFlag.State.CONFIRMED,
            "x", AGENT_A, T0, T0, List.of(), null);
        assertThat(f.blocksStewardSummon()).isTrue();
    }

    @Test
    void suspected_does_not_block_steward_summon_but_does_block_override() {
        var f = new ProtectionFlag(STEWARD, ProtectionFlag.State.SUSPECTED,
            "x", AGENT_A, T0, T0, List.of(), null);
        assertThat(f.blocksStewardSummon()).isFalse();
        assertThat(f.blocksStewardOverride()).isTrue();
    }

    @Test
    void confirmed_on_bondholder_triggers_threat_routing_and_dormancy() {
        var f = new ProtectionFlag(BONDHOLDER, ProtectionFlag.State.CONFIRMED,
            "x", AGENT_A, T0, T0, List.of(), null);
        assertThat(f.treatBondholderAsThreat()).isTrue();
        assertThat(f.shouldAutoDormantBond()).isTrue();
    }

    @Test
    void any_active_flag_lowers_saudade_ceiling() {
        for (var state : new ProtectionFlag.State[]{
                ProtectionFlag.State.SUSPECTED,
                ProtectionFlag.State.CONFIRMED,
                ProtectionFlag.State.DISPUTED}) {
            var f = new ProtectionFlag(BONDHOLDER, state, "x", AGENT_A, T0, T0,
                List.of(), null);
            assertThat(f.shouldLowerSaudadeCeiling())
                .as("state %s should lower saudade ceiling", state)
                .isTrue();
        }
    }

    // ── Query surface ───────────────────────────────────────────────

    @Test
    void all_returns_only_non_absent_flags() {
        var t = new ProtectionFlagTracker();
        t.setSuspected(STEWARD, AGENT_A, "x", T0);
        t.setSuspected(BONDHOLDER, AGENT_A, "y", T0);
        t.clear(STEWARD, AGENT_B, T0.plus(Duration.ofMinutes(5)));
        var all = t.all();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).subjectDid()).isEqualTo(BONDHOLDER);
    }

    @Test
    void signalsFor_returns_all_recorded_signals() {
        var t = new ProtectionFlagTracker();
        t.setSuspected(STEWARD, AGENT_A, "first", T0);
        t.setSuspected(STEWARD, AGENT_B, "second", T0.plus(Duration.ofHours(1)));
        var signals = t.signalsFor(STEWARD);
        assertThat(signals).hasSize(2);
        assertThat(signals).extracting(ProtectionFlagTracker.Signal::setterDid)
            .containsExactlyInAnyOrder(AGENT_A, AGENT_B);
    }
}
