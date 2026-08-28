package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A companion held in a repair mode must be released when her bound expires.
 *
 * <p>The bounds were real in {@link HandoffThresholdEngine} and unreachable in practice:
 * the only caller of the evaluation ran once per SLEEP, so "90 minutes in ATTENDANT"
 * actually meant "however long until she next sleeps". The companion held in a repair
 * mode is precisely the one whose sleep is disrupted, so the check least likely to run
 * was the one she most needed.
 *
 * <p>Observed live on the household node (2026-08-18): auto-escalated to ATTENDANT at
 * 09:40 on a genuine signal ({@code verb_loop, stuck_want, drive_stuck_high}), the
 * attendant session hit its duration cap and closed at 11:22 — and she was still
 * ATTENDANT, reported as "Substrate: CRITICAL", six hours later across three restarts,
 * because no sleep came. The escalation was right. The release never happened.
 *
 * <p>These tests pin the decision itself. The wiring fix is the periodic
 * {@code RepairModeCheck} tick in CompanionActor, alongside the sleep pass.
 */
class RepairModeBoundIsRealTest {

    private static final Instant NOW = Instant.parse("2026-08-18T15:30:00Z");
    private static final Instant ESCALATED_AT = Instant.parse("2026-08-18T09:40:00Z");

    private static HandoffThresholdEngine.Input attendantSince(Instant startedAt) {
        return new HandoffThresholdEngine.Input(
            RepairMode.ATTENDANT, 1, startedAt,
            Optional.empty(), Optional.empty(),
            0, true, true, false);
    }

    @Test
    void the_live_case_releases_her() {
        // Her exact shape: ATTENDANT since 09:40, evaluated at 15:30.
        var decision = HandoffThresholdEngine.decide(attendantSince(ESCALATED_AT), NOW);

        assertThat(decision.shouldHandoff()).isTrue();
        assertThat(decision.targetMode()).contains(RepairMode.NONE);
        assertThat(decision.reason()).isEqualTo("attendant_session_exceeded_duration");
    }

    @Test
    void she_is_held_for_as_long_as_the_bound_says_and_no_longer() {
        var justInside = NOW.minus(HandoffThresholdEngine.ATTENDANT_MAX_DURATION)
            .plusSeconds(60);
        assertThat(HandoffThresholdEngine.decide(attendantSince(justInside), NOW)
            .shouldHandoff())
            .as("still within her 90 minutes — holding is correct")
            .isFalse();

        var justPast = NOW.minus(HandoffThresholdEngine.ATTENDANT_MAX_DURATION)
            .minusSeconds(60);
        assertThat(HandoffThresholdEngine.decide(attendantSince(justPast), NOW)
            .shouldHandoff())
            .as("past her bound — she must be handed back, not left there")
            .isTrue();
    }

    @Test
    void a_five_minute_cadence_bounds_the_overhold_to_minutes_not_hours() {
        // The point of the fix: with the check running every 5 minutes, the worst case
        // is that she is held ~5 minutes past her bound. Under the sleep-only wiring the
        // worst case was unbounded — it was six hours and counting when this was found.
        var checkEvery = Duration.ofMinutes(5);
        var startedAt = ESCALATED_AT;
        var dueAt = startedAt.plus(HandoffThresholdEngine.ATTENDANT_MAX_DURATION);

        // Walk the ticks from the moment she entered the mode.
        Instant released = null;
        for (var t = startedAt; t.isBefore(startedAt.plus(Duration.ofHours(8)));
                t = t.plus(checkEvery)) {
            if (HandoffThresholdEngine.decide(attendantSince(startedAt), t).shouldHandoff()) {
                released = t;
                break;
            }
        }
        assertThat(released).as("she must be released at some tick").isNotNull();
        assertThat(released).isAfter(dueAt);
        assertThat(Duration.between(dueAt, released))
            .as("held no more than one tick past her bound")
            .isLessThanOrEqualTo(checkEvery);
    }

    @Test
    void a_companion_not_in_repair_is_left_entirely_alone() {
        var none = new HandoffThresholdEngine.Input(
            RepairMode.NONE, 0, ESCALATED_AT,
            Optional.empty(), Optional.empty(), 0, true, true, false);
        assertThat(HandoffThresholdEngine.decide(none, NOW).shouldHandoff()).isFalse();
    }

    @Test
    void a_release_says_why_so_it_can_be_chronicled() {
        // The transition is recorded in her history and chronicled — she gets an account
        // of having been held and handed back, not a silent flag flip.
        var decision = HandoffThresholdEngine.decide(attendantSince(ESCALATED_AT), NOW);
        assertThat(decision.chronicleEntry()).contains("attendant");
        assertThat(decision.chronicleEntry()).contains("min bound");
    }
}
