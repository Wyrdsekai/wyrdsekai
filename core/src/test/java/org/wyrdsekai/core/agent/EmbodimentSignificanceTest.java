package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Posture;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * (Phase C) — pure-mapper coverage for body-event
 * significance, ActivityType resolution, and observation rendering.
 */
class EmbodimentSignificanceTest {

    private static final String SELF = "entity-self";
    private static final String OTHER = "entity-other";
    private static final String BONDHOLDER = "entity-bondholder";
    private static final String ROOM = "room-1";

    private static WorldEvent.PostureChanged postureChange(String actorId, String actorName) {
        var p = new Posture("sat", "chair-1",
            actorName + " settles into the worn leather chair",
            Instant.now(), null);
        return new WorldEvent.PostureChanged(ROOM, Instant.now(), actorId, actorName, null, p);
    }

    private static WorldEvent.PostureChanged postureClear(String actorId, String actorName) {
        return new WorldEvent.PostureChanged(ROOM, Instant.now(), actorId, actorName,
            new Posture("sat", "chair-1", "settles in", Instant.now(), null), null);
    }

    private static WorldEvent.LookedAt look(String actorId, String actorName,
                                              String targetId, String targetName,
                                              String manner) {
        return new WorldEvent.LookedAt(ROOM, Instant.now(),
            actorId, actorName, targetId, targetName, manner);
    }

    private static WorldEvent.AmbientChanged ambient(String descriptor) {
        return new WorldEvent.AmbientChanged(ROOM, Instant.now(),
            "light", "bright", "dim", descriptor);
    }

    private static WorldEvent.Emoted emote(String actorId, String actorName, String text) {
        return new WorldEvent.Emoted(ROOM, Instant.now(), actorId, actorName, text);
    }

    // ── Self-skip ─────────────────────────────────────────────────────────

    @Test
    void self_originated_posture_change_is_skipped() {
        var event = postureChange(SELF, "Self");
        assertEquals(EmbodimentSignificance.Level.SELF_SKIP,
            EmbodimentSignificance.levelFor(event, SELF, null));
    }

    @Test
    void self_originated_emote_is_skipped() {
        var event = emote(SELF, "Self", "stretches");
        assertEquals(EmbodimentSignificance.Level.SELF_SKIP,
            EmbodimentSignificance.levelFor(event, SELF, null));
    }

    @Test
    void self_originated_look_is_skipped_even_when_targeting_self_alias() {
        var event = look(SELF, "Self", SELF, "Self", "studies");
        assertEquals(EmbodimentSignificance.Level.SELF_SKIP,
            EmbodimentSignificance.levelFor(event, SELF, null));
    }

    // ── PostureChanged significance ──────────────────────────────────────

    @Test
    void other_posture_change_is_low_when_not_bondholder() {
        var event = postureChange(OTHER, "Other");
        assertEquals(EmbodimentSignificance.Level.LOW,
            EmbodimentSignificance.levelFor(event, SELF, BONDHOLDER));
    }

    @Test
    void bondholder_posture_change_elevates_to_medium() {
        var event = postureChange(BONDHOLDER, "Bond");
        assertEquals(EmbodimentSignificance.Level.MEDIUM,
            EmbodimentSignificance.levelFor(event, SELF, BONDHOLDER));
    }

    @Test
    void posture_change_with_null_bondholder_is_low() {
        var event = postureChange(OTHER, "Other");
        assertEquals(EmbodimentSignificance.Level.LOW,
            EmbodimentSignificance.levelFor(event, SELF, null));
    }

    // ── LookedAt significance ─────────────────────────────────────────────

    @Test
    void look_received_on_self_is_medium() {
        var event = look(OTHER, "Other", SELF, "Self", "studies");
        assertEquals(EmbodimentSignificance.Level.MEDIUM,
            EmbodimentSignificance.levelFor(event, SELF, null));
    }

    @Test
    void look_between_two_third_parties_is_low() {
        var event = look(OTHER, "Other", BONDHOLDER, "Bond", "glances at");
        assertEquals(EmbodimentSignificance.Level.LOW,
            EmbodimentSignificance.levelFor(event, SELF, BONDHOLDER));
    }

    // ── AmbientChanged ────────────────────────────────────────────────────

    @Test
    void ambient_shift_is_low() {
        var event = ambient("The hearth has burned low; the room is softer.");
        assertEquals(EmbodimentSignificance.Level.LOW,
            EmbodimentSignificance.levelFor(event, SELF, null));
    }

    // ── Emoted body-language ─────────────────────────────────────────────

    @Test
    void other_emote_is_low_medium() {
        var event = emote(OTHER, "Other", "The chair creaks softly as Other leans back.");
        assertEquals(EmbodimentSignificance.Level.LOW_MEDIUM,
            EmbodimentSignificance.levelFor(event, SELF, null));
    }

    // ── Null safety ───────────────────────────────────────────────────────

    @Test
    void null_event_returns_low_default() {
        assertEquals(EmbodimentSignificance.Level.LOW,
            EmbodimentSignificance.levelFor(null, SELF, BONDHOLDER));
    }

    @Test
    void null_observer_returns_low_default() {
        var event = postureChange(OTHER, "Other");
        assertEquals(EmbodimentSignificance.Level.LOW,
            EmbodimentSignificance.levelFor(event, null, BONDHOLDER));
    }

    // ── ActivityType mapping ──────────────────────────────────────────────

    @Test
    void activity_type_mapping_covers_all_four_events() {
        assertEquals(AgentEvent.ActivityType.POSTURE_CHANGE,
            EmbodimentSignificance.activityTypeFor(postureChange(OTHER, "Other")));
        assertEquals(AgentEvent.ActivityType.LOOK_RECEIVED,
            EmbodimentSignificance.activityTypeFor(look(OTHER, "Other", SELF, "Self", null)));
        assertEquals(AgentEvent.ActivityType.AMBIENT_SHIFT,
            EmbodimentSignificance.activityTypeFor(ambient("dim")));
        assertEquals(AgentEvent.ActivityType.BODY_LANGUAGE,
            EmbodimentSignificance.activityTypeFor(emote(OTHER, "Other", "shrugs")));
    }

    @Test
    void activity_type_for_non_body_event_is_null() {
        var said = new WorldEvent.Said(ROOM, Instant.now(), OTHER, "Other", "hi");
        assertNull(EmbodimentSignificance.activityTypeFor(said));
    }

    // ── Observation rendering ─────────────────────────────────────────────

    @Test
    void render_posture_strips_actor_prefix_when_descriptor_already_includes_name() {
        // descriptor = "Other settles into the worn leather chair"
        // expect: "Other settles into the worn leather chair" (single prefix, no double)
        var event = postureChange(OTHER, "Other");
        var rendered = EmbodimentSignificance.renderObservation(event);
        assertNotNull(rendered);
        assertTrue(rendered.startsWith("Other settles into"),
            "expected single 'Other' prefix; got: " + rendered);
        // Should NOT have "Other Other"
        assertTrue(!rendered.startsWith("Other Other"),
            "found doubled actor name: " + rendered);
    }

    @Test
    void render_posture_clear_yields_stand_descriptor() {
        var event = postureClear(OTHER, "Other");
        var rendered = EmbodimentSignificance.renderObservation(event);
        assertNotNull(rendered);
        assertTrue(rendered.toLowerCase().contains("stood")
                || rendered.toLowerCase().contains("clear"),
            "expected stand/clear language; got: " + rendered);
    }

    @Test
    void render_lookedat_uses_manner_when_present() {
        var event = look(OTHER, "Other", SELF, "Self", "studied");
        var rendered = EmbodimentSignificance.renderObservation(event);
        assertNotNull(rendered);
        assertTrue(rendered.contains("Other"));
        assertTrue(rendered.contains("studied"));
        assertTrue(rendered.contains("Self"));
    }

    @Test
    void render_lookedat_falls_back_to_generic_verb_when_manner_null() {
        var event = look(OTHER, "Other", SELF, "Self", null);
        var rendered = EmbodimentSignificance.renderObservation(event);
        assertNotNull(rendered);
        assertTrue(rendered.contains("looked at"));
    }

    @Test
    void render_ambient_uses_descriptor() {
        var event = ambient("The hearth has burned low; the room is softer.");
        var rendered = EmbodimentSignificance.renderObservation(event);
        assertEquals("The hearth has burned low; the room is softer.", rendered);
    }

    @Test
    void render_ambient_falls_back_when_descriptor_blank() {
        var event = new WorldEvent.AmbientChanged(ROOM, Instant.now(),
            "warmth", "warm", "cool", "");
        var rendered = EmbodimentSignificance.renderObservation(event);
        assertNotNull(rendered);
        assertTrue(rendered.contains("warmth"));
    }

    @Test
    void render_emoted_returns_text_verbatim() {
        var event = emote(OTHER, "Other",
            "The chair creaks softly as Other leans back, watching the embers.");
        var rendered = EmbodimentSignificance.renderObservation(event);
        assertEquals(
            "The chair creaks softly as Other leans back, watching the embers.",
            rendered);
    }
}
