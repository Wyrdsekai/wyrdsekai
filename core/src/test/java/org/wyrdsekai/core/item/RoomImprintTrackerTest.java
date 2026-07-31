package org.wyrdsekai.core.item;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.empathy.EpigeneticModifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoomImprintTrackerTest {

    private RoomImprintTracker tracker;

    @BeforeEach void setUp() {
        tracker = new RoomImprintTracker();
    }

    @Test void registers_imprint() {
        tracker.registerImprint("library", Map.of("curiosity", 0.3), "scholarly study", 300);
        assertThat(tracker.hasImprint("library")).isTrue();
        assertThat(tracker.imprintCount()).isEqualTo(1);
    }

    @Test void tick_increments_counter() {
        tracker.tick("agent1", "library", null);
        assertThat(tracker.ticksInRoom("agent1")).isEqualTo(1);
        tracker.tick("agent1", "library", null);
        assertThat(tracker.ticksInRoom("agent1")).isEqualTo(2);
    }

    @Test void tick_resets_on_room_change() {
        tracker.tick("agent1", "library", null);
        tracker.tick("agent1", "library", null);
        assertThat(tracker.ticksInRoom("agent1")).isEqualTo(2);

        tracker.tick("agent1", "study", null);
        assertThat(tracker.ticksInRoom("agent1")).isEqualTo(1);
        assertThat(tracker.currentRoom("agent1")).isEqualTo("study");
    }

    @Test void fires_impression_at_threshold() {
        var modifier = new EpigeneticModifier();
        tracker.registerImprint(new RoomImprintTracker.RoomImprint(
            "library", Map.of("curiosity", 0.3), "scholarly study", 5));

        boolean fired = false;
        for (int i = 0; i < 5; i++) {
            fired = tracker.tick("agent1", "library", modifier);
        }
        assertThat(fired).isTrue();
    }

    @Test void does_not_fire_before_threshold() {
        var modifier = new EpigeneticModifier();
        tracker.registerImprint(new RoomImprintTracker.RoomImprint(
            "library", Map.of("curiosity", 0.3), "study", 10));

        boolean fired = false;
        for (int i = 0; i < 9; i++) {
            fired = tracker.tick("agent1", "library", modifier);
        }
        assertThat(fired).isFalse();
    }

    @Test void null_modifier_still_returns_true_on_threshold() {
        tracker.registerImprint("library", Map.of("curiosity", 0.3), "study", 3);
        for (int i = 0; i < 2; i++) tracker.tick("agent1", "library", null);
        assertThat(tracker.tick("agent1", "library", null)).isTrue();
    }

    @Test void no_imprint_room_never_fires() {
        for (int i = 0; i < 1000; i++) {
            assertThat(tracker.tick("agent1", "no-imprint", null)).isFalse();
        }
    }

    @Test void default_threshold_applied() {
        var imprint = new RoomImprintTracker.RoomImprint("test", Map.of(), "desc", 0);
        assertThat(imprint.threshold()).isEqualTo(RoomImprintTracker.DEFAULT_THRESHOLD);
    }

    @Test void multiple_agents_independent() {
        tracker.registerImprint("library", Map.of("curiosity", 0.3), "study", 3);
        tracker.tick("agent1", "library", null);
        tracker.tick("agent1", "library", null);
        tracker.tick("agent2", "library", null);

        assertThat(tracker.ticksInRoom("agent1")).isEqualTo(2);
        assertThat(tracker.ticksInRoom("agent2")).isEqualTo(1);
    }

    @Test void currentRoom_null_for_unknown_agent() {
        assertThat(tracker.currentRoom("unknown")).isNull();
        assertThat(tracker.ticksInRoom("unknown")).isEqualTo(0);
    }
}
