package org.wyrdsekai.common.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoomObjectCloneableTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void default_is_cloneable() {
        var obj = new RoomObject("sword-1", "sword", "A sharp sword", true);
        assertThat(obj.cloneable()).isTrue();
    }

    @Test
    void visible_constructor_defaults_cloneable_true() {
        var obj = new RoomObject("sword-1", "sword", "A sharp sword", true, true);
        assertThat(obj.cloneable()).isTrue();
    }

    @Test
    void unique_factory_creates_non_cloneable() {
        var obj = RoomObject.unique("journal-1", "journal", "Personal journal", true);
        assertThat(obj.cloneable()).isFalse();
        assertThat(obj.takeable()).isTrue();
        assertThat(obj.visible()).isTrue();
    }

    @Test
    void full_constructor_allows_non_cloneable() {
        var obj = new RoomObject("key-1", "master key", "Opens all doors", true, true, false);
        assertThat(obj.cloneable()).isFalse();
    }

    @Test
    void json_without_cloneable_defaults_true() throws Exception {
        var json = """
            {"id":"test","name":"test item","description":"desc","takeable":true,"visible":true}
            """;
        var obj = MAPPER.readValue(json, RoomObject.class);
        assertThat(obj.cloneable()).isTrue();
    }

    @Test
    void json_with_cloneable_false() throws Exception {
        var json = """
            {"id":"test","name":"test item","description":"desc","takeable":true,"visible":true,"cloneable":false}
            """;
        var obj = MAPPER.readValue(json, RoomObject.class);
        assertThat(obj.cloneable()).isFalse();
    }

    @Test
    void json_roundtrip_preserves_cloneable() throws Exception {
        var original = RoomObject.unique("artifact-1", "ancient artifact", "One of a kind", true);
        var json = MAPPER.writeValueAsString(original);
        var deserialized = MAPPER.readValue(json, RoomObject.class);

        assertThat(deserialized.cloneable()).isFalse();
        assertThat(deserialized.id()).isEqualTo("artifact-1");
        assertThat(deserialized.name()).isEqualTo("ancient artifact");
    }

    @Test
    void existing_foundation_room_objects_are_cloneable() throws Exception {
        // Foundation room JSON doesn't have "cloneable" — should default to true
        var json = """
            {"id":"nexus-crystal","name":"crystal","description":"A pulsing crystal","takeable":false}
            """;
        var obj = MAPPER.readValue(json, RoomObject.class);
        assertThat(obj.cloneable()).isTrue();
        assertThat(obj.visible()).isTrue(); // also defaulted
    }
}
