package org.wyrdsekai.common.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredTest {

    @Test void five_param_constructor_sets_defaults() {
        var s = new Structured("Hall", "A great hall",
            List.of(new Exit("north", "garden", "To the garden")),
            List.of(new Entity("p1", "Alice", "player", "")),
            List.of(new RoomObject("sword", "Sword", "Rusty", true)));

        assertThat(s.name()).isEqualTo("Hall");
        assertThat(s.exits()).hasSize(1);
        assertThat(s.entities()).hasSize(1);
        assertThat(s.objects()).hasSize(1);
        assertThat(s.hints()).isEmpty();
        assertThat(s.properties()).isEmpty();
        assertThat(s.zone()).isEmpty();
    }

    @Test void full_constructor() {
        var s = new Structured("Hall", "A great hall",
            List.of(), List.of(), List.of(),
            List.of(new Hint("Look around", "look", "look")),
            Map.of("light", "bright"),
            "home");

        assertThat(s.hints()).hasSize(1);
        assertThat(s.properties()).containsEntry("light", "bright");
        assertThat(s.zone()).isEqualTo("home");
    }

    @Test void fromSnapshot_preserves_all_fields() {
        var snapshot = new RoomSnapshot(
            "hall", "Hall", "A great hall", "home",
            List.of(new Exit("north", "garden", "To the garden")),
            List.of(new Entity("p1", "Alice", "player", "")),
            List.of(new RoomObject("sword", "Sword", "Rusty", true)),
            List.of(new Hint("Look", "look", "look"))
        );

        var s = Structured.fromSnapshot(snapshot);
        assertThat(s.name()).isEqualTo("Hall");
        assertThat(s.description()).isEqualTo("A great hall");
        assertThat(s.zone()).isEqualTo("home");
        assertThat(s.exits()).hasSize(1);
        assertThat(s.entities()).hasSize(1);
        assertThat(s.objects()).hasSize(1);
        assertThat(s.hints()).hasSize(1);
    }

    @Test void empty_structured() {
        var s = new Structured("", "", List.of(), List.of(), List.of());
        assertThat(s.name()).isEmpty();
        assertThat(s.exits()).isEmpty();
        assertThat(s.entities()).isEmpty();
        assertThat(s.objects()).isEmpty();
    }
}
