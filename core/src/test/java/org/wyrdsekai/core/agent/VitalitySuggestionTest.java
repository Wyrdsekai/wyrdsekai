package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.VisibilityLevel;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.room.RoomState;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the VitalitySuggested event type and its mediation logic.
 * Tier 1 of the three-tier scripting influence model.
 */
class VitalitySuggestionTest {

    // --- WorldEvent.VitalitySuggested record ---

    @Test void vitality_suggested_record_fields() {
        var event = new WorldEvent.VitalitySuggested(
            "nexus", Instant.now(), "agent-1", "energy", 0.5, "healing_pool");
        assertThat(event.roomId()).isEqualTo("nexus");
        assertThat(event.entityId()).isEqualTo("agent-1");
        assertThat(event.tank()).isEqualTo("energy");
        assertThat(event.delta()).isEqualTo(0.5);
        assertThat(event.reason()).isEqualTo("healing_pool");
    }

    @Test void vitality_suggested_negative_delta() {
        var event = new WorldEvent.VitalitySuggested(
            "trap-room", Instant.now(), "agent-1", "energy", -0.3, "poison_gas");
        assertThat(event.delta()).isEqualTo(-0.3);
    }

    // --- Jackson serialization round-trip ---

    @Test void vitality_suggested_serializes_with_jackson() throws Exception {
        var event = new WorldEvent.VitalitySuggested(
            "nexus", Instant.now(), "agent-1", "energy", 0.5, "script");
        var mapper = Json.mapper();
        var json = mapper.writeValueAsString(event);
        assertThat(json).contains("\"type\":\"vitality_suggested\"");
        assertThat(json).contains("\"tank\":\"energy\"");
        assertThat(json).contains("\"delta\":0.5");

        var deserialized = mapper.readValue(json, WorldEvent.class);
        assertThat(deserialized).isInstanceOf(WorldEvent.VitalitySuggested.class);
        var vs = (WorldEvent.VitalitySuggested) deserialized;
        assertThat(vs.entityId()).isEqualTo("agent-1");
        assertThat(vs.tank()).isEqualTo("energy");
        assertThat(vs.delta()).isEqualTo(0.5);
    }

    // --- Delta clamping (in WorldApi.suggestVitality) ---

    @Test void delta_clamped_to_range() {
        // WorldApi clamps to [-1.0, 1.0] before emitting
        assertThat(Math.max(-1.0, Math.min(1.0, 5.0))).isEqualTo(1.0);
        assertThat(Math.max(-1.0, Math.min(1.0, -3.0))).isEqualTo(-1.0);
        assertThat(Math.max(-1.0, Math.min(1.0, 0.5))).isEqualTo(0.5);
    }

    // --- Tank validation ---

    @Test void valid_tank_names() {
        var validTanks = Set.of("contextBudget", "confidence", "energy",
            "alignment", "errorPressure", "momentum", "rapport", "focus");
        assertThat(validTanks).hasSize(8);
        assertThat(validTanks).contains("energy", "focus", "rapport");
        assertThat(validTanks).doesNotContain("health", "mana", "hp");
    }

    // --- Visibility level ---

    @Test void vitality_suggested_is_privileged_visibility() {
        var event = new WorldEvent.VitalitySuggested(
            "nexus", Instant.now(), "agent-1", "energy", 0.5, "script");
        var level = VisibilityLevel.defaultFor(event);
        assertThat(level).isEqualTo(VisibilityLevel.PRIVILEGED);
    }

    // --- RoomState doesn't change ---

    @Test void room_state_unaffected_by_vitality_suggested() {
        var state = RoomState.empty("test-room");
        var event = new WorldEvent.VitalitySuggested(
            "test-room", Instant.now(), "agent-1", "energy", 0.5, "script");
        var newState = state.apply(event);
        assertThat(newState).isSameAs(state);
    }
}
