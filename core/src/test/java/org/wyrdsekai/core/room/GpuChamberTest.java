package org.wyrdsekai.core.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GpuChamberTest {

    private GpuChamber chamber;

    @BeforeEach void setUp() {
        chamber = new GpuChamber(3); // 3 GPU slots
    }

    @Test void reserve_succeeds_with_available_slots() {
        var reservation = chamber.reserve("agent-1", Duration.ofMinutes(5));
        assertThat(reservation).isPresent();
        assertThat(chamber.activeCount()).isEqualTo(1);
        assertThat(chamber.availableSlots()).isEqualTo(2);
    }

    @Test void reserve_fails_when_full() {
        chamber.reserve("agent-1", Duration.ofMinutes(5));
        chamber.reserve("agent-2", Duration.ofMinutes(5));
        chamber.reserve("agent-3", Duration.ofMinutes(5));

        var fourth = chamber.reserve("agent-4", Duration.ofMinutes(5));
        assertThat(fourth).isEmpty();
    }

    @Test void reserve_fails_for_duplicate_agent() {
        chamber.reserve("agent-1", Duration.ofMinutes(5));
        var dup = chamber.reserve("agent-1", Duration.ofMinutes(5));
        assertThat(dup).isEmpty();
    }

    @Test void release_frees_slot() {
        var r = chamber.reserve("agent-1", Duration.ofMinutes(5)).orElseThrow();
        assertThat(chamber.release(r.id())).isTrue();
        assertThat(chamber.availableSlots()).isEqualTo(3);
    }

    @Test void describe_shows_availability() {
        chamber.reserve("agent-1", Duration.ofMinutes(5));
        var desc = chamber.describe();
        assertThat(desc).contains("1/3 slots in use");
        assertThat(desc).contains("agent-1");
    }
}
