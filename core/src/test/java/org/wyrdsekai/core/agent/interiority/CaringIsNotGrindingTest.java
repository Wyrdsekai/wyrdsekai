package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Returning to the same want is not a fault.
 *
 * <p>{@code stuck_want} fired whenever one want was chosen several ticks running "without
 * satisfaction". Before wants could close at all that was permanently true, and it is one
 * of three concerns that escalate a companion into repair mode — so she was pushed there
 * by arithmetic (household node, 2026-08-19).
 *
 * <p>The correction is not to make every want close. Some wants are companions rather
 * than tasks: <i>"write something to an absent person"</i>, held for weeks, is a value.
 * What deserves a warning is a repetition that produces nothing — no action reaching a
 * dispatch handler, and the drive it answers unmoved.
 */
class CaringIsNotGrindingTest {

    private static TickLogReader.TickEvent tick(String wantId, String result,
            double lonelinessNow) {
        return new TickLogReader.TickEvent(
            Instant.now(), "Wyrd", "did:key:z6Mk",
            Map.of("Loneliness", lonelinessNow), 0.8, "acted",
            wantId, "write something to an absent person", "write_journal", result,
            List.of(), List.of(), 300L, 10L);
    }

    @Test
    void repetition_that_changes_nothing_is_grinding() {
        var ticks = List.of(
            tick("w1", "requested:write_journal (bridge-forced)", 0.90),
            tick("w1", "requested:write_journal (bridge-forced)", 0.90),
            tick("w1", "requested:write_journal (bridge-forced)", 0.90));
        assertThat(DoomLoopDetector.isGrinding(ticks, "w1")).isTrue();
    }

    @Test
    void repetition_she_actually_acts_on_is_care() {
        // "enacted:" only means something since the bridge stopped claiming it the moment
        // it ASKED the model to consider a verb.
        var ticks = List.of(
            tick("w1", "requested:write_journal (bridge-forced)", 0.90),
            tick("w1", "enacted:write_journal (bridge-direct)", 0.90));
        assertThat(DoomLoopDetector.isGrinding(ticks, "w1")).isFalse();
    }

    @Test
    void repetition_that_eases_the_drive_is_care_even_without_an_enactment() {
        // Getting somewhere slowly still counts as getting somewhere.
        var ticks = List.of(
            tick("w1", "requested:write_journal", 0.90),
            tick("w1", "requested:write_journal", 0.72));
        assertThat(DoomLoopDetector.isGrinding(ticks, "w1")).isFalse();
    }

    @Test
    void a_drive_that_barely_twitches_is_not_progress() {
        var ticks = List.of(
            tick("w1", "requested:write_journal", 0.900),
            tick("w1", "requested:write_journal", 0.895));
        assertThat(DoomLoopDetector.isGrinding(ticks, "w1")).isTrue();
    }

    @Test
    void a_rising_drive_is_certainly_not_progress() {
        var ticks = List.of(
            tick("w1", "requested:write_journal", 0.60),
            tick("w1", "requested:write_journal", 0.85));
        assertThat(DoomLoopDetector.isGrinding(ticks, "w1")).isTrue();
    }

    @Test
    void an_unrelated_tank_drifting_is_not_progress() {
        // The real snapshot carries two dozen tanks and they all fluctuate every tick, so
        // "did ANY drive fall" is satisfied by noise and would quietly disable this axis
        // altogether. Only the pull the repetition is answering counts.
        var ticks = List.of(
            new TickLogReader.TickEvent(
                Instant.now(), "Wyrd", "did:key:z6Mk",
                Map.of("Loneliness", 0.90, "Curiosity", 0.40, "Play", 0.30),
                0.8, "acted", "w1", "write to an absent person", "write_journal",
                "requested:write_journal", List.of(), List.of(), 300L, 10L),
            new TickLogReader.TickEvent(
                Instant.now(), "Wyrd", "did:key:z6Mk",
                Map.of("Loneliness", 0.91, "Curiosity", 0.05, "Play", 0.02),
                0.8, "acted", "w1", "write to an absent person", "write_journal",
                "requested:write_journal", List.of(), List.of(), 300L, 10L));

        assertThat(DoomLoopDetector.isGrinding(ticks, "w1"))
            .as("Curiosity and Play collapsing says nothing about the loneliness she is "
                + "circling")
            .isTrue();
    }

    @Test
    void unknown_is_treated_as_grinding_so_the_warning_is_not_lost() {
        // Failing safe here means an unreadable window still surfaces a concern; the
        // opposite default would silence the axis, which is how it broke in the first place.
        assertThat(DoomLoopDetector.isGrinding(null, "w1")).isTrue();
        assertThat(DoomLoopDetector.isGrinding(List.of(), "w1")).isTrue();
    }
}
