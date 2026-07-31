package org.wyrdsekai.common.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.util.Json;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire protocol roundtrip tests for MUD communication messages.
 * Tests JSON serialization/deserialization of Emoted events and Prose style field.
 */
class CommunicationWireTest {

    private static final ObjectMapper mapper = Json.mapper();

    private <T> T roundTrip(Object value, Class<T> type) throws JsonProcessingException {
        var json = mapper.writeValueAsString(value);
        return mapper.readValue(json, type);
    }

    // ── WorldEvent.Emoted serialization ──

    @Test
    void emoted_event_serializes_roundtrip() throws Exception {
        var now = Instant.now();
        var event = new WorldEvent.Emoted("nexus", now, "player-1", "Alice", "smiles warmly");

        var json = mapper.writeValueAsString(event);
        var result = mapper.readValue(json, WorldEvent.class);

        assertThat(result).isInstanceOf(WorldEvent.Emoted.class);
        var emoted = (WorldEvent.Emoted) result;
        assertThat(emoted.roomId()).isEqualTo("nexus");
        assertThat(emoted.entityId()).isEqualTo("player-1");
        assertThat(emoted.entityName()).isEqualTo("Alice");
        assertThat(emoted.text()).isEqualTo("smiles warmly");
        assertThat(emoted.timestamp()).isEqualTo(now);
    }

    @Test
    void emoted_event_type_discriminator() throws Exception {
        var event = new WorldEvent.Emoted("nexus", Instant.now(), "player-1", "Alice", "waves");

        var json = mapper.writeValueAsString(event);
        var tree = mapper.readTree(json);

        assertThat(tree.has("type")).isTrue();
        assertThat(tree.get("type").asText()).isEqualTo("emoted");
    }

    // ── S2CMessage.Prose style field ──

    @Test
    void prose_with_null_style_backward_compatible() throws Exception {
        var msg = new S2CMessage.Prose(1, "narrator", "Welcome!",
            List.of(), null, "normal");

        var result = roundTrip(msg, S2CMessage.class);
        assertThat(result).isInstanceOf(S2CMessage.Prose.class);
        var prose = (S2CMessage.Prose) result;
        assertThat(prose.style()).isNull();
        assertThat(prose.speaker()).isEqualTo("narrator");
        assertThat(prose.text()).isEqualTo("Welcome!");
    }

    @Test
    void prose_with_emote_style() throws Exception {
        var msg = new S2CMessage.Prose(2, "Alice", "Alice smiles warmly",
            List.of(), null, "normal", "en", "emote");

        var json = mapper.writeValueAsString(msg);
        var tree = mapper.readTree(json);
        assertThat(tree.get("style").asText()).isEqualTo("emote");

        var result = roundTrip(msg, S2CMessage.class);
        var prose = (S2CMessage.Prose) result;
        assertThat(prose.style()).isEqualTo("emote");
        assertThat(prose.speaker()).isEqualTo("Alice");
    }

    @Test
    void prose_with_whisper_style() throws Exception {
        var msg = new S2CMessage.Prose(3, "Alice", "Alice whispers: secret",
            List.of(), null, "normal", "en", "whisper");

        var json = mapper.writeValueAsString(msg);
        var tree = mapper.readTree(json);
        assertThat(tree.get("style").asText()).isEqualTo("whisper");

        var result = roundTrip(msg, S2CMessage.class);
        var prose = (S2CMessage.Prose) result;
        assertThat(prose.style()).isEqualTo("whisper");
    }

    @Test
    void prose_with_tell_style() throws Exception {
        var msg = new S2CMessage.Prose(4, "Alice", "Alice tells you: hello",
            List.of(), null, "critical", "en", "tell");

        var json = mapper.writeValueAsString(msg);
        var tree = mapper.readTree(json);
        assertThat(tree.get("style").asText()).isEqualTo("tell");

        var result = roundTrip(msg, S2CMessage.class);
        var prose = (S2CMessage.Prose) result;
        assertThat(prose.style()).isEqualTo("tell");
        assertThat(prose.priority()).isEqualTo("critical");
    }

    @Test
    void prose_with_say_style() throws Exception {
        var msg = new S2CMessage.Prose(5, "Alice", "Alice says: hello",
            List.of(), null, "normal", "en", "say");

        var result = roundTrip(msg, S2CMessage.class);
        var prose = (S2CMessage.Prose) result;
        assertThat(prose.style()).isEqualTo("say");
    }

    @Test
    void prose_style_absent_from_old_constructor() throws Exception {
        // The 6-arg constructor (pre-style) should produce null style
        var msg = new S2CMessage.Prose(6, "system", "Help text",
            List.of(), null, "normal");

        var json = mapper.writeValueAsString(msg);
        var result = mapper.readValue(json, S2CMessage.class);
        var prose = (S2CMessage.Prose) result;
        assertThat(prose.style()).isNull();
    }

    // ── WorldEvent.Whispered serialization ──

    @Test
    void whispered_event_roundtrip() throws Exception {
        var event = new WorldEvent.Whispered("nexus", Instant.now(),
            "player-1", "Alice", "player-2", "psst, secret!");

        var result = roundTrip(event, WorldEvent.class);
        assertThat(result).isInstanceOf(WorldEvent.Whispered.class);
        var whispered = (WorldEvent.Whispered) result;
        assertThat(whispered.entityName()).isEqualTo("Alice");
        assertThat(whispered.targetEntityId()).isEqualTo("player-2");
        assertThat(whispered.text()).isEqualTo("psst, secret!");
    }

    @Test
    void whispered_event_type_discriminator() throws Exception {
        var event = new WorldEvent.Whispered("nexus", Instant.now(),
            "player-1", "Alice", "player-2", "secret");

        var json = mapper.writeValueAsString(event);
        var tree = mapper.readTree(json);
        assertThat(tree.get("type").asText()).isEqualTo("whispered");
    }

    // ── WorldEvent.Told serialization ──

    @Test
    void told_event_roundtrip() throws Exception {
        var event = new WorldEvent.Told("nexus", Instant.now(),
            "player-1", "Alice", "player-2", "hello there");

        var result = roundTrip(event, WorldEvent.class);
        assertThat(result).isInstanceOf(WorldEvent.Told.class);
        var told = (WorldEvent.Told) result;
        assertThat(told.fromEntityName()).isEqualTo("Alice");
        assertThat(told.toEntityId()).isEqualTo("player-2");
        assertThat(told.text()).isEqualTo("hello there");
    }

    @Test
    void told_event_type_discriminator() throws Exception {
        var event = new WorldEvent.Told("nexus", Instant.now(),
            "player-1", "Alice", "player-2", "hi");

        var json = mapper.writeValueAsString(event);
        var tree = mapper.readTree(json);
        assertThat(tree.get("type").asText()).isEqualTo("told");
    }
}
