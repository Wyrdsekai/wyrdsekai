package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Entity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who the voice stage is told it is speaking TO.
 *
 * <p>The polish prompt opened every line with "a draft that will be spoken to a user",
 * unconditionally — so a companion alone in her own room was rewritten toward an
 * audience that did not exist, and said things like "That feels right, doesn't it?
 * Glad you're ready too" to nobody (live 2026-08-17). The phantom listener was not a
 * habit of the weights; we asserted one into the prompt on every turn. Anchoring what
 * she talks about could not fix it, because this is added downstream of the draft.
 *
 * <p>The failure is deliberately asymmetric: telling the voice stage an audience
 * exists when it doesn't invents a listener, while the reverse would suppress address
 * while a real person is standing there — which reads as ignoring them, and is the
 * worse failure. So an unknown room counts as occupied.
 */
class EmptyRoomVoiceTest {

    private static final String SELF = "companion-alder";

    private static Entity entity(String id, String type) {
        return new Entity(id, id, type, "");
    }

    @Test
    void an_empty_room_has_no_listener() {
        assertThat(CompanionActor.noOneIsListening(List.of(), SELF)).isTrue();
    }

    @Test
    void a_room_holding_only_herself_has_no_listener() {
        assertThat(CompanionActor.noOneIsListening(List.of(entity(SELF, "agent")), SELF)).isTrue();
    }

    @Test
    void a_person_in_the_room_is_a_listener() {
        assertThat(CompanionActor.noOneIsListening(
            List.of(entity(SELF, "agent"), entity("bramble", "player")), SELF)).isFalse();
    }

    @Test
    void a_peer_companion_is_a_listener() {
        assertThat(CompanionActor.noOneIsListening(
            List.of(entity(SELF, "agent"), entity("companion-thorne", "agent")), SELF)).isFalse();
    }

    @Test
    void scenery_and_items_are_not_listeners() {
        // A room full of objects is still an empty room to speak into.
        assertThat(CompanionActor.noOneIsListening(
            List.of(entity(SELF, "agent"), entity("crystal", "object"),
                    entity("hearth", "scenery")), SELF)).isTrue();
    }

    @Test
    void an_unknown_room_is_treated_as_occupied() {
        // The safe direction: never suppress address on a guess.
        assertThat(CompanionActor.noOneIsListening(null, SELF)).isFalse();
    }

    @Test
    void malformed_entities_do_not_conjure_or_erase_a_listener() {
        var entities = new ArrayList<Entity>();
        entities.add(null);
        entities.add(new Entity(null, "nameless", "player", ""));
        assertThat(CompanionActor.noOneIsListening(entities, SELF)).isTrue();

        entities.add(entity("bramble", "player"));
        assertThat(CompanionActor.noOneIsListening(entities, SELF)).isFalse();
    }
}
