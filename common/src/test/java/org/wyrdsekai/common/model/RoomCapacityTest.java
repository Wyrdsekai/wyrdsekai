package org.wyrdsekai.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoomCapacityTest {

    @Test void defaults_has_standard_limits() {
        var cap = RoomCapacity.defaults();
        assertThat(cap.maxEntities()).isEqualTo(50);
        assertThat(cap.maxAgents()).isEqualTo(10);
        assertThat(cap.maxObjects()).isEqualTo(100);
    }

    @Test void canAddEntity_allows_below_limit() {
        var cap = new RoomCapacity(5, 3, 20);
        assertThat(cap.canAddEntity(4)).isTrue();
        assertThat(cap.canAddEntity(5)).isFalse();
    }

    @Test void canAddAgent_respects_agent_limit() {
        var cap = new RoomCapacity(50, 3, 100);
        assertThat(cap.canAddAgent(2)).isTrue();
        assertThat(cap.canAddAgent(3)).isFalse();
    }

    @Test void describe_empty_for_plenty_of_space() {
        var cap = RoomCapacity.defaults();
        assertThat(cap.describe(5)).isEmpty();
    }

    @Test void describe_warns_when_nearly_full() {
        var cap = new RoomCapacity(10, 5, 20);
        assertThat(cap.describe(8)).contains("nearly full");
        assertThat(cap.describe(8)).contains("2 spots");
    }

    @Test void describe_full_when_at_capacity() {
        var cap = new RoomCapacity(10, 5, 20);
        assertThat(cap.describe(10)).contains("full");
    }
}
