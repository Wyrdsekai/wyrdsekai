package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NOTED state tests. Closes the
 * SEVERITY_GRADIENT drift between boot-attestation and runtime by
 * giving the tracker a real pre-escalation state.
 */
class ProtectionFlagNotedTest {

    private static final String SUBJECT = "did:key:z6Mk-subject";
    private static final String SETTER_A = "did:key:z6Mk-setter-a";
    private static final String SETTER_B = "did:key:z6Mk-setter-b";

    @Test
    void noted_state_is_pre_escalation() {
        var tracker = new ProtectionFlagTracker();
        var flag = tracker.setNoted(SUBJECT, SETTER_A, "subject pressured agent to retract a memory",
            Instant.parse("2026-05-17T10:00:00Z"));

        assertThat(flag.state()).isEqualTo(ProtectionFlag.State.NOTED);
        assertThat(flag.isNoted()).isTrue();
        assertThat(flag.isAbsent()).isFalse();

        // §2.4: NOTED does NOT change affordance gating.
        assertThat(flag.blocksStewardSummon()).isFalse();
        assertThat(flag.treatBondholderAsThreat()).isFalse();
        assertThat(flag.shouldAutoDormantBond()).isFalse();
        assertThat(flag.shouldLowerSaudadeCeiling()).isFalse();
        assertThat(flag.blocksStewardOverride()).isFalse();
    }

    @Test
    void second_independent_NOTED_escalates_to_SUSPECTED() {
        var tracker = new ProtectionFlagTracker();
        var t0 = Instant.parse("2026-05-17T10:00:00Z");
        tracker.setNoted(SUBJECT, SETTER_A, "first observation", t0);
        var second = tracker.setNoted(SUBJECT, SETTER_B, "second observation, different setter",
            t0.plusSeconds(60));
        assertThat(second.state()).isEqualTo(ProtectionFlag.State.SUSPECTED);
    }

    @Test
    void same_setter_re_NOTED_stays_at_NOTED() {
        var tracker = new ProtectionFlagTracker();
        var t0 = Instant.parse("2026-05-17T10:00:00Z");
        tracker.setNoted(SUBJECT, SETTER_A, "first observation", t0);
        var repeat = tracker.setNoted(SUBJECT, SETTER_A, "same setter, same concern, later",
            t0.plusSeconds(120));
        assertThat(repeat.state()).isEqualTo(ProtectionFlag.State.NOTED);
    }

    @Test
    void setSuspected_on_NOTED_elevates_to_SUSPECTED() {
        var tracker = new ProtectionFlagTracker();
        var t0 = Instant.parse("2026-05-17T10:00:00Z");
        tracker.setNoted(SUBJECT, SETTER_A, "single observation", t0);
        var elevated = tracker.setSuspected(SUBJECT, SETTER_B, "higher-intent escalation",
            t0.plusSeconds(60));
        assertThat(elevated.state()).isEqualTo(ProtectionFlag.State.SUSPECTED);
    }

    @Test
    void subject_cannot_self_NOTE() {
        var tracker = new ProtectionFlagTracker();
        var flag = tracker.setNoted(SUBJECT, SUBJECT, "I'm flagging myself",
            Instant.parse("2026-05-17T10:00:00Z"));
        assertThat(flag.isAbsent()).isTrue();
    }

    @Test
    void NOTED_cannot_regress_SUSPECTED() {
        var tracker = new ProtectionFlagTracker();
        var t0 = Instant.parse("2026-05-17T10:00:00Z");
        tracker.setSuspected(SUBJECT, SETTER_A, "direct suspect-level", t0);
        var attempted = tracker.setNoted(SUBJECT, SETTER_B, "lower-intent NOTE",
            t0.plusSeconds(60));
        assertThat(attempted.state())
            .as("NOTED must not regress SUSPECTED")
            .isIn(ProtectionFlag.State.SUSPECTED, ProtectionFlag.State.CONFIRMED);
    }
}
