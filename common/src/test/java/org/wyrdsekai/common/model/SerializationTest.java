package org.wyrdsekai.common.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.protocol.C2SMessage;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.common.util.Json;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SerializationTest {

    private final ObjectMapper mapper = Json.mapper();

    private <T> T roundTrip(Object value, Class<T> type) throws JsonProcessingException {
        var json = mapper.writeValueAsString(value);
        return mapper.readValue(json, type);
    }

    // --- S2C Messages ---

    @Test void s2c_prose_roundtrip() throws Exception {
        var hints = List.of(new Hint("Talk to Wyrd", "greet", "say:Hello"));
        var msg = new S2CMessage.Prose(1, "narrator", "Welcome!", hints, null, "normal");
        var result = roundTrip(msg, S2CMessage.class);
        assertThat(result).isInstanceOf(S2CMessage.Prose.class);
        var prose = (S2CMessage.Prose) result;
        assertThat(prose.seq()).isEqualTo(1);
        assertThat(prose.speaker()).isEqualTo("narrator");
        assertThat(prose.text()).isEqualTo("Welcome!");
        assertThat(prose.hints()).hasSize(1);
        assertThat(prose.hints().getFirst().label()).isEqualTo("Talk to Wyrd");
        assertThat(prose.priority()).isEqualTo("normal");
    }

    @Test void s2c_room_state_roundtrip() throws Exception {
        var snapshot = new RoomSnapshot(
            "nexus", "The Nexus", "A hub.", "foundation",
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(new Entity("p1", "Alice", "player", "")),
            List.of(new RoomObject("crystal", "Nexus Crystal", "Glowing.", false)),
            List.of()
        );
        var msg = new S2CMessage.RoomState(2, snapshot, List.of());
        var result = roundTrip(msg, S2CMessage.class);
        assertThat(result).isInstanceOf(S2CMessage.RoomState.class);
        var rs = (S2CMessage.RoomState) result;
        assertThat(rs.room().name()).isEqualTo("The Nexus");
        assertThat(rs.room().exits()).hasSize(1);
    }

    @Test void s2c_error_roundtrip() throws Exception {
        var msg = new S2CMessage.Error(3, "NOT_FOUND", "Room not found", "req-1");
        var result = roundTrip(msg, S2CMessage.class);
        assertThat(result).isInstanceOf(S2CMessage.Error.class);
        assertThat(((S2CMessage.Error) result).code()).isEqualTo("NOT_FOUND");
    }

    @Test void s2c_transit_roundtrip() throws Exception {
        var msg = new S2CMessage.Transit(4, "zone-beta", "ws://host:7070/ws", "tok-1", "Traveling...");
        var result = roundTrip(msg, S2CMessage.class);
        assertThat(result).isInstanceOf(S2CMessage.Transit.class);
        assertThat(((S2CMessage.Transit) result).transitToken()).isEqualTo("tok-1");
    }

    // --- C2S Messages ---

    @Test void c2s_say_roundtrip() throws Exception {
        var msg = new C2SMessage.Say("id-1", "nexus", "Hello world");
        var result = roundTrip(msg, C2SMessage.class);
        assertThat(result).isInstanceOf(C2SMessage.Say.class);
        assertThat(((C2SMessage.Say) result).text()).isEqualTo("Hello world");
    }

    @Test void c2s_go_roundtrip() throws Exception {
        var msg = new C2SMessage.Go("id-2", "nexus", "east");
        var result = roundTrip(msg, C2SMessage.class);
        assertThat(result).isInstanceOf(C2SMessage.Go.class);
        assertThat(((C2SMessage.Go) result).direction()).isEqualTo("east");
    }

    @Test void c2s_hint_select_roundtrip() throws Exception {
        var msg = new C2SMessage.HintSelect("id-3", "nexus", 2);
        var result = roundTrip(msg, C2SMessage.class);
        assertThat(result).isInstanceOf(C2SMessage.HintSelect.class);
        assertThat(((C2SMessage.HintSelect) result).index()).isEqualTo(2);
    }

    // --- Model records ---

    @Test void hint_roundtrip() throws Exception {
        var hint = new Hint("Label", "intent", "say:hello");
        var result = roundTrip(hint, Hint.class);
        assertThat(result).isEqualTo(hint);
    }

    @Test void exit_roundtrip() throws Exception {
        var exit = new Exit("north", "hallway", "The Hallway");
        var result = roundTrip(exit, Exit.class);
        assertThat(result).isEqualTo(exit);
    }

    @Test void room_object_roundtrip() throws Exception {
        var obj = new RoomObject("key", "Golden Key", "An ornate key.", true);
        var result = roundTrip(obj, RoomObject.class);
        assertThat(result).isEqualTo(obj);
    }

    // --- WorldEvent ---

    @Test void world_event_said_roundtrip() throws Exception {
        var event = new WorldEvent.Said("nexus", Instant.now(), "p1", "Alice", "Hello");
        var result = roundTrip(event, WorldEvent.class);
        assertThat(result).isInstanceOf(WorldEvent.Said.class);
        assertThat(((WorldEvent.Said) result).text()).isEqualTo("Hello");
        assertThat(((WorldEvent.Said) result).entityName()).isEqualTo("Alice");
    }

    @Test void world_event_hints_updated_roundtrip() throws Exception {
        var hints = List.of(new Hint("A", "a", "say:a"));
        var event = new WorldEvent.HintsUpdated("nexus", Instant.now(), hints);
        var result = roundTrip(event, WorldEvent.class);
        assertThat(result).isInstanceOf(WorldEvent.HintsUpdated.class);
        assertThat(((WorldEvent.HintsUpdated) result).hints()).hasSize(1);
    }

    @Test void world_event_room_created_roundtrip() throws Exception {
        var event = new WorldEvent.RoomCreated("nexus", Instant.now(), "The Nexus", "A hub.", "foundation");
        var result = roundTrip(event, WorldEvent.class);
        assertThat(result).isInstanceOf(WorldEvent.RoomCreated.class);
        assertThat(((WorldEvent.RoomCreated) result).name()).isEqualTo("The Nexus");
    }

    @Test void world_event_entity_entered_with_description_roundtrip() throws Exception {
        var event = new WorldEvent.EntityEntered("nexus", Instant.now(),
            "agent-1", "Ember", "agent", "north", "A curious entity");
        var result = roundTrip(event, WorldEvent.class);
        assertThat(result).isInstanceOf(WorldEvent.EntityEntered.class);
        var entered = (WorldEvent.EntityEntered) result;
        assertThat(entered.entityName()).isEqualTo("Ember");
        assertThat(entered.description()).isEqualTo("A curious entity");
    }

    @Test void world_event_entity_entered_backward_compat_roundtrip() throws Exception {
        // 6-arg constructor (no description) — should default to ""
        var event = new WorldEvent.EntityEntered("nexus", Instant.now(),
            "player-1", "Alice", "player", "north");
        var result = roundTrip(event, WorldEvent.class);
        assertThat(result).isInstanceOf(WorldEvent.EntityEntered.class);
        var entered = (WorldEvent.EntityEntered) result;
        assertThat(entered.entityName()).isEqualTo("Alice");
        assertThat(entered.description()).isEmpty();
    }

    // --- Alias serialization ---

    @Test void room_snapshot_with_aliases_roundtrip() throws Exception {
        var snapshot = new RoomSnapshot(
            "nexus", "The Nexus", "A hub.", "foundation",
            List.of("nexus", "hub", "center"),
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(new Entity("p1", "Alice", "player", "")),
            List.of(new RoomObject("crystal", "Nexus Crystal", "Glowing.", false, true, true,
                List.of("crystal", "gem"))),
            List.of()
        );
        var result = roundTrip(snapshot, RoomSnapshot.class);
        assertThat(result.aliases()).containsExactly("nexus", "hub", "center");
        assertThat(result.objects().getFirst().aliases()).containsExactly("crystal", "gem");
    }

    @Test void room_snapshot_without_aliases_backward_compat() throws Exception {
        // Deserialize JSON without aliases fields — should default to empty lists
        var json = """
            {"roomId":"nexus","name":"The Nexus","description":"A hub.","zone":"foundation",
             "exits":[],"entities":[],"objects":[],"hints":[]}
            """;
        var result = mapper.readValue(json, RoomSnapshot.class);
        assertThat(result.aliases()).isEmpty();
    }

    @Test void entity_with_aliases_roundtrip() throws Exception {
        var entity = new Entity("comp-1", "Wyrd", "agent", "A companion", null,
            List.of("wyrd", "companion"));
        var result = roundTrip(entity, Entity.class);
        assertThat(result.aliases()).containsExactly("wyrd", "companion");
    }

    @Test void entity_without_aliases_backward_compat() throws Exception {
        var json = """
            {"id":"p1","name":"Alice","type":"player","description":""}
            """;
        var result = mapper.readValue(json, Entity.class);
        assertThat(result.aliases()).isEmpty();
        assertThat(result.did()).isNull();
    }

    @Test void room_object_with_aliases_roundtrip() throws Exception {
        var obj = new RoomObject("sword-1", "iron sword", "A sword", true, true, true,
            List.of("sword", "iron sword", "blade"));
        var result = roundTrip(obj, RoomObject.class);
        assertThat(result.aliases()).containsExactly("sword", "iron sword", "blade");
    }

    @Test void room_object_without_aliases_backward_compat() throws Exception {
        var json = """
            {"id":"key","name":"Golden Key","description":"An ornate key.","takeable":true}
            """;
        var result = mapper.readValue(json, RoomObject.class);
        assertThat(result.aliases()).isEmpty();
        assertThat(result.visible()).isTrue();
        assertThat(result.cloneable()).isTrue();
    }

    @Test void world_event_room_created_with_aliases_roundtrip() throws Exception {
        var event = new WorldEvent.RoomCreated("nexus", Instant.now(), "The Nexus", "A hub.",
            "foundation", List.of("nexus", "hub"));
        var result = roundTrip(event, WorldEvent.class);
        assertThat(result).isInstanceOf(WorldEvent.RoomCreated.class);
        var created = (WorldEvent.RoomCreated) result;
        assertThat(created.aliases()).containsExactly("nexus", "hub");
    }

    @Test void world_event_room_created_backward_compat() throws Exception {
        // Old 5-arg constructor (no aliases)
        var event = new WorldEvent.RoomCreated("nexus", Instant.now(), "The Nexus", "A hub.", "foundation");
        assertThat(event.aliases()).isEmpty();
        var result = roundTrip(event, WorldEvent.class);
        assertThat(((WorldEvent.RoomCreated) result).aliases()).isEmpty();
    }

    // --- — Posture / InnerImprint / Entity.posture / RoomObject.state ---
    // Round-trips for the body-substrate records and the three new WorldEvent types.
    // Also explicit backward-compat tests: pre-existing JSON (no posture / state / new events)
    // must still deserialize cleanly into the new records.

    @Test void posture_roundtrip_minimal() throws Exception {
        var p = new Posture("sat", "study-chair", "settles into the worn leather chair", Instant.parse("2026-05-23T14:32:00Z"));
        var result = roundTrip(p, Posture.class);
        assertThat(result.verb()).isEqualTo("sat");
        assertThat(result.atObject()).isEqualTo("study-chair");
        assertThat(result.descriptor()).isEqualTo("settles into the worn leather chair");
        assertThat(result.setAt()).isEqualTo(Instant.parse("2026-05-23T14:32:00Z"));
        assertThat(result.innerImprint()).isNull();
        assertThat(result.hasImprint()).isFalse();
    }

    @Test void posture_roundtrip_with_inner_imprint() throws Exception {
        var imprint = InnerImprint.ofTanks(
            Map.of("equanimity", 0.02, "energy", 0.005),
            "settled");
        var p = new Posture("sat", "study-chair", "settles into the worn leather chair",
            Instant.parse("2026-05-23T14:32:00Z"), imprint);
        var result = roundTrip(p, Posture.class);
        assertThat(result.hasImprint()).isTrue();
        assertThat(result.innerImprint().tanks()).containsEntry("equanimity", 0.02);
        assertThat(result.innerImprint().tanks()).containsEntry("energy", 0.005);
        assertThat(result.innerImprint().triggersOnSet()).isEqualTo("settled");
    }

    @Test void posture_without_atObject_roundtrip() throws Exception {
        // kneeling by the window — no clean object target, valid posture
        var p = new Posture("knelt", "by the window, hands resting on the sill", Instant.now());
        var result = roundTrip(p, Posture.class);
        assertThat(result.verb()).isEqualTo("knelt");
        assertThat(result.atObject()).isNull();
        assertThat(result.descriptor()).contains("by the window");
    }

    @Test void posture_rejects_blank_verb() {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new Posture("", "study-chair", "x", Instant.now()));
    }

    @Test void posture_rejects_blank_descriptor() {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new Posture("sat", "study-chair", "", Instant.now()));
    }

    @Test void inner_imprint_empty_is_idempotent() throws Exception {
        var result = roundTrip(InnerImprint.NONE, InnerImprint.class);
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.tanks()).isEmpty();
        assertThat(result.drives()).isEmpty();
        assertThat(result.triggersOnSet()).isNull();
    }

    @Test void entity_with_posture_roundtrip() throws Exception {
        var posture = new Posture("sat", "leather-chair", "settles into the worn leather chair, facing the hearth", Instant.now());
        var entity = new Entity("p1", "Operator", "player", "", null, List.of(), posture);
        var result = roundTrip(entity, Entity.class);
        assertThat(result.posture()).isNotNull();
        assertThat(result.posture().verb()).isEqualTo("sat");
        assertThat(result.posture().descriptor()).contains("worn leather chair");
    }

    @Test void entity_without_posture_roundtrip() throws Exception {
        var entity = new Entity("p1", "Operator", "player", "");
        assertThat(entity.posture()).isNull();
        var result = roundTrip(entity, Entity.class);
        assertThat(result.posture()).isNull();
    }

    @Test void entity_legacy_json_no_posture_field_deserializes_cleanly() throws Exception {
        // Simulates a journal entry / saved snapshot written before shipped.
        // The on-disk JSON has no "posture" field at all.
        String legacyJson = "{\"id\":\"p1\",\"name\":\"Operator\",\"type\":\"player\",\"description\":\"\"}";
        var result = mapper.readValue(legacyJson, Entity.class);
        assertThat(result.id()).isEqualTo("p1");
        assertThat(result.posture()).isNull();
        assertThat(result.aliases()).isEmpty();
    }

    @Test void entity_withPosture_helper_returns_copy() {
        var entity = new Entity("p1", "Operator", "player", "");
        var posture = new Posture("sat", "chair-1", "sits", Instant.now());
        var seated = entity.withPosture(posture);
        assertThat(entity.posture()).isNull();              // original untouched
        assertThat(seated.posture()).isSameAs(posture);
        assertThat(seated.id()).isEqualTo("p1");

        var standing = seated.withPosture(null);            // clear
        assertThat(standing.posture()).isNull();
    }

    @Test void room_object_with_state_roundtrip() throws Exception {
        var obj = new RoomObject("study-chair", "leather chair", "A worn leather chair.",
            false, true, false, List.of("chair"),
            Map.of("sittable", "true", "occupied", "false"));
        var result = roundTrip(obj, RoomObject.class);
        assertThat(result.state()).containsEntry("sittable", "true");
        assertThat(result.state()).containsEntry("occupied", "false");
        assertThat(result.isFlag("sittable")).isTrue();
        assertThat(result.isFlag("occupied")).isFalse();
    }

    @Test void room_object_without_state_roundtrip() throws Exception {
        var obj = new RoomObject("key", "Golden Key", "An ornate key.", true);
        assertThat(obj.state()).isEmpty();
        var result = roundTrip(obj, RoomObject.class);
        assertThat(result.state()).isEmpty();
    }

    @Test void room_object_legacy_json_no_state_field_deserializes_cleanly() throws Exception {
        // Simulates a foundation-rooms JSON entry written before shipped.
        String legacyJson = "{\"id\":\"obj-1\",\"name\":\"crystal\",\"description\":\"Glowing.\","
            + "\"takeable\":false,\"visible\":true,\"cloneable\":true,\"aliases\":[]}";
        var result = mapper.readValue(legacyJson, RoomObject.class);
        assertThat(result.id()).isEqualTo("obj-1");
        assertThat(result.state()).isEmpty();
        assertThat(result.visible()).isTrue();
    }

    @Test void room_object_withStateKey_helper_returns_copy() {
        var obj = new RoomObject("hearth", "hearth", "A stone hearth.", false);
        var lit = obj.withStateKey("lit", "true");
        assertThat(obj.state()).isEmpty();                  // original untouched
        assertThat(lit.state()).containsEntry("lit", "true");
        assertThat(lit.id()).isEqualTo("hearth");
    }

    @Test void world_event_posture_changed_roundtrip() throws Exception {
        var from = new Posture("stood", "by-the-door", "standing in the doorway", Instant.parse("2026-05-23T14:00:00Z"));
        var to = new Posture("sat", "study-chair", "settles into the worn leather chair", Instant.parse("2026-05-23T14:32:00Z"));
        var event = new WorldEvent.PostureChanged(
            "study-1", Instant.parse("2026-05-23T14:32:00Z"),
            "p1", "Operator", from, to);
        var result = roundTrip(event, WorldEvent.class);
        assertThat(result).isInstanceOf(WorldEvent.PostureChanged.class);
        var pc = (WorldEvent.PostureChanged) result;
        assertThat(pc.entityName()).isEqualTo("Operator");
        assertThat(pc.previous().verb()).isEqualTo("stood");
        assertThat(pc.current().verb()).isEqualTo("sat");
        assertThat(pc.current().descriptor()).contains("worn leather chair");
    }

    @Test void world_event_posture_changed_set_from_default_roundtrip() throws Exception {
        // previous=null means posture was set from the default (no prior posture).
        var to = new Posture("sat", "study-chair", "sits", Instant.now());
        var event = new WorldEvent.PostureChanged("study-1", Instant.now(), "p1", "Operator", null, to);
        var result = roundTrip(event, WorldEvent.class);
        var pc = (WorldEvent.PostureChanged) result;
        assertThat(pc.previous()).isNull();
        assertThat(pc.current().verb()).isEqualTo("sat");
    }

    @Test void world_event_posture_cleared_roundtrip() throws Exception {
        // current=null means posture was cleared (back to default).
        var from = new Posture("sat", "study-chair", "sits", Instant.now());
        var event = new WorldEvent.PostureChanged("study-1", Instant.now(), "p1", "Operator", from, null);
        var result = roundTrip(event, WorldEvent.class);
        var pc = (WorldEvent.PostureChanged) result;
        assertThat(pc.previous().verb()).isEqualTo("sat");
        assertThat(pc.current()).isNull();
    }

    @Test void world_event_looked_at_with_manner_roundtrip() throws Exception {
        var event = new WorldEvent.LookedAt(
            "study-1", Instant.now(),
            "p1", "Operator", "p2", "Ember", "studying her face");
        var result = roundTrip(event, WorldEvent.class);
        assertThat(result).isInstanceOf(WorldEvent.LookedAt.class);
        var la = (WorldEvent.LookedAt) result;
        assertThat(la.actorName()).isEqualTo("Operator");
        assertThat(la.targetName()).isEqualTo("Ember");
        assertThat(la.manner()).isEqualTo("studying her face");
    }

    @Test void world_event_looked_at_null_manner_roundtrip() throws Exception {
        var event = new WorldEvent.LookedAt(
            "study-1", Instant.now(),
            "p1", "Operator", "p2", "Ember", null);
        var result = roundTrip(event, WorldEvent.class);
        var la = (WorldEvent.LookedAt) result;
        assertThat(la.manner()).isNull();
    }

    @Test void world_event_ambient_changed_roundtrip() throws Exception {
        var event = new WorldEvent.AmbientChanged(
            "study-1", Instant.now(),
            "light", "bright", "dim",
            "The hearth has burned low; the room is softer now.");
        var result = roundTrip(event, WorldEvent.class);
        assertThat(result).isInstanceOf(WorldEvent.AmbientChanged.class);
        var ac = (WorldEvent.AmbientChanged) result;
        assertThat(ac.key()).isEqualTo("light");
        assertThat(ac.previous()).isEqualTo("bright");
        assertThat(ac.current()).isEqualTo("dim");
        assertThat(ac.descriptor()).contains("hearth has burned low");
    }
}
