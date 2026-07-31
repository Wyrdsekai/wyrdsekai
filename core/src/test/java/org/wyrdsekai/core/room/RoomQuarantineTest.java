package org.wyrdsekai.core.room;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoomQuarantineTest {

    @Test void isQuarantined_false_by_default() {
        var state = RoomState.empty("test-room");
        assertThat(state.properties().get("quarantine")).isNull();
    }

    @Test void propertyChanged_sets_quarantine() {
        var state = RoomState.empty("test-room");
        var event = new WorldEvent.PropertyChanged("test-room", Instant.now(), "quarantine", null, "true");
        var newState = state.apply(event);
        assertThat(newState.properties().get("quarantine")).isEqualTo("true");
    }

    @Test void propertyChanged_clears_quarantine() {
        // Start quarantined
        var state = RoomState.empty("test-room")
            .apply(new WorldEvent.PropertyChanged("test-room", Instant.now(), "quarantine", null, "true"));
        assertThat(state.properties().get("quarantine")).isEqualTo("true");

        // Unquarantine: setting to null removes key
        var event = new WorldEvent.PropertyChanged("test-room", Instant.now(), "quarantine", "true", null);
        var newState = state.apply(event);
        assertThat(newState.properties().get("quarantine")).isNull();
    }

    @Test void quarantine_state_persists_through_other_events() {
        var state = RoomState.empty("test-room")
            .apply(new WorldEvent.RoomCreated("test-room", Instant.now(), "Test", "A test room", "zone1"))
            .apply(new WorldEvent.PropertyChanged("test-room", Instant.now(), "quarantine", null, "true"))
            .apply(new WorldEvent.Said("test-room", Instant.now(), "alice", "Alice", "hello"));

        assertThat(state.properties().get("quarantine")).isEqualTo("true");
    }
}
