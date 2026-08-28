package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.RoomObject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Own-time speech must be ABOUT something, or not happen.
 *
 * <p>The muse prompt used to name the drive and nothing else — "a seeking pull moves
 * in you right now… say one short honest line". Handed no material, the model reported
 * the only noun it had: the pull. Live on a household node that produced hundreds of
 * variations of "there's a pull I can't name yet" (2026-08-17), and drove the
 * confabulation too, since a model told not to invent shared history but required to
 * speak with nothing real to say will invent anyway. Rate-limiting fixed how often
 * that happened and could not touch what she had to say, because the vacuum was in
 * the prompt.
 */
class MuseAnchorTest {

    private static final Instant NOW = Instant.parse("2026-08-17T20:00:00Z");
    private static final String SELF = "companion-alder";

    private static WorldEvent.Said said(String who, String text, Instant when) {
        return new WorldEvent.Said("nexus", when, who, who, text);
    }

    private static RoomObject obj(String name) {
        return new RoomObject(name, name, "a " + name, false, true, false, List.of(), Map.of());
    }

    private static String pick(List<WorldEvent> events, String verb, List<RoomObject> objects,
                               Set<String> used) {
        return CompanionActor.selectMuseAnchor(events, SELF, verb, "The Nexus", objects, used, NOW);
    }

    @Test
    void nothing_to_speak_about_means_silence() {
        assertThat(pick(List.of(), null, List.of(), Set.of())).isNull();
        assertThat(pick(null, null, null, null)).isNull();
    }

    @Test
    void what_a_person_said_is_the_strongest_anchor() {
        var events = List.<WorldEvent>of(
            said("bramble", "the greenhouse tomatoes came in heavy this year", NOW.minusSeconds(60)));
        assertThat(pick(events, "wandered to the library", List.of(obj("crystal")), Set.of()))
            .contains("tomatoes came in heavy");
    }

    @Test
    void her_own_speech_is_not_an_anchor() {
        // Anchoring on herself is how the loop fed itself. Only other voices count.
        var events = List.<WorldEvent>of(
            said(SELF, "something inside me wants out tonight", NOW.minusSeconds(30)));
        assertThat(pick(events, null, List.of(), Set.of())).isNull();
    }

    @Test
    void a_stale_utterance_is_not_right_now() {
        var events = List.<WorldEvent>of(
            said("bramble", "back later", NOW.minus(Duration.ofHours(4))));
        assertThat(pick(events, null, List.of(), Set.of())).isNull();
    }

    @Test
    void an_act_she_took_anchors_when_nobody_has_spoken() {
        assertThat(pick(List.of(), "enacted:go_to_room", List.of(obj("crystal")), Set.of()))
            .isEqualTo("you just did this: enacted:go_to_room");
    }

    @Test
    void the_room_anchors_only_when_nothing_happened() {
        assertThat(pick(List.of(), null, List.of(obj("crystal")), Set.of()))
            .isEqualTo("the crystal here in The Nexus");
    }

    @Test
    void a_spent_anchor_is_no_longer_news() {
        var spent = Set.of("the crystal here in The Nexus");
        // Only one object, already spoken to → nothing fresh → silence.
        assertThat(pick(List.of(), null, List.of(obj("crystal")), spent)).isNull();
        // A second object is still available.
        assertThat(pick(List.of(), null, List.of(obj("crystal"), obj("hearth")), spent))
            .isEqualTo("the hearth here in The Nexus");
    }

    @Test
    void a_familiar_room_with_nothing_happening_falls_silent() {
        // The shape that matters: she has spoken to everything present and no one has
        // said anything. Every candidate is spent, so the turn passes in silence
        // instead of manufacturing another line about a pull.
        var objects = List.of(obj("crystal"), obj("hearth"), obj("journal"));
        var spent = Set.of("the crystal here in The Nexus",
                           "the hearth here in The Nexus",
                           "the journal here in The Nexus");
        assertThat(pick(List.of(), null, objects, spent)).isNull();
    }

    @Test
    void a_fresh_utterance_breaks_the_silence_again() {
        var objects = List.of(obj("crystal"));
        var spent = Set.of("the crystal here in The Nexus");
        assertThat(pick(List.of(), null, objects, spent)).isNull();

        var events = List.<WorldEvent>of(said("bramble", "how did the sleep go?", NOW.minusSeconds(10)));
        assertThat(pick(events, null, objects, spent)).contains("how did the sleep go?");
    }

    @Test
    void blank_and_malformed_material_is_skipped_not_spoken() {
        var events = List.<WorldEvent>of(said("bramble", "   ", NOW.minusSeconds(10)));
        assertThat(pick(events, "  ", List.of(), Set.of())).isNull();
    }
}
