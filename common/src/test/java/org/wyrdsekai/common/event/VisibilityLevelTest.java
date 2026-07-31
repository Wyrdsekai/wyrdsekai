package org.wyrdsekai.common.event;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Hint;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VisibilityLevelTest {

    @Test void public_can_see_public_only() {
        assertThat(VisibilityLevel.PUBLIC.canSee(VisibilityLevel.PUBLIC)).isTrue();
        assertThat(VisibilityLevel.PUBLIC.canSee(VisibilityLevel.DIRECTED)).isFalse();
        assertThat(VisibilityLevel.PUBLIC.canSee(VisibilityLevel.PRIVILEGED)).isFalse();
        assertThat(VisibilityLevel.PUBLIC.canSee(VisibilityLevel.SYSTEM)).isFalse();
    }

    @Test void privileged_can_see_public_directed_privileged() {
        assertThat(VisibilityLevel.PRIVILEGED.canSee(VisibilityLevel.PUBLIC)).isTrue();
        assertThat(VisibilityLevel.PRIVILEGED.canSee(VisibilityLevel.DIRECTED)).isTrue();
        assertThat(VisibilityLevel.PRIVILEGED.canSee(VisibilityLevel.PRIVILEGED)).isTrue();
        assertThat(VisibilityLevel.PRIVILEGED.canSee(VisibilityLevel.SYSTEM)).isFalse();
    }

    @Test void system_can_see_everything() {
        assertThat(VisibilityLevel.SYSTEM.canSee(VisibilityLevel.PUBLIC)).isTrue();
        assertThat(VisibilityLevel.SYSTEM.canSee(VisibilityLevel.DIRECTED)).isTrue();
        assertThat(VisibilityLevel.SYSTEM.canSee(VisibilityLevel.PRIVILEGED)).isTrue();
        assertThat(VisibilityLevel.SYSTEM.canSee(VisibilityLevel.SYSTEM)).isTrue();
    }

    @Test void said_event_is_public() {
        var event = new WorldEvent.Said("room1", Instant.now(), "e1", "name", "hello");
        assertThat(VisibilityLevel.defaultFor(event)).isEqualTo(VisibilityLevel.PUBLIC);
    }

    @Test void entity_entered_is_public() {
        var event = new WorldEvent.EntityEntered("room1", Instant.now(), "e1", "name", "player", "north");
        assertThat(VisibilityLevel.defaultFor(event)).isEqualTo(VisibilityLevel.PUBLIC);
    }

    @Test void script_triggered_is_privileged() {
        var event = new WorldEvent.ScriptTriggered("room1", Instant.now(), "test.js", "onSay", Map.of());
        assertThat(VisibilityLevel.defaultFor(event)).isEqualTo(VisibilityLevel.PRIVILEGED);
    }

    @Test void property_changed_is_privileged() {
        var event = new WorldEvent.PropertyChanged("room1", Instant.now(), "key", "old", "new");
        assertThat(VisibilityLevel.defaultFor(event)).isEqualTo(VisibilityLevel.PRIVILEGED);
    }

    @Test void whispered_is_directed() {
        var event = new WorldEvent.Whispered("room1", Instant.now(), "e1", "Alice", "e2", "secret");
        assertThat(VisibilityLevel.defaultFor(event)).isEqualTo(VisibilityLevel.DIRECTED);
    }

    @Test void directed_can_see_public_and_directed() {
        assertThat(VisibilityLevel.DIRECTED.canSee(VisibilityLevel.PUBLIC)).isTrue();
        assertThat(VisibilityLevel.DIRECTED.canSee(VisibilityLevel.DIRECTED)).isTrue();
        assertThat(VisibilityLevel.DIRECTED.canSee(VisibilityLevel.PRIVILEGED)).isFalse();
        assertThat(VisibilityLevel.DIRECTED.canSee(VisibilityLevel.SYSTEM)).isFalse();
    }

    @Test void room_created_is_system() {
        var event = new WorldEvent.RoomCreated("room1", Instant.now(), "Room", "Desc", "zone");
        assertThat(VisibilityLevel.defaultFor(event)).isEqualTo(VisibilityLevel.SYSTEM);
    }
}
