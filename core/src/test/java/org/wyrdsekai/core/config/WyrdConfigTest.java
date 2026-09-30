package org.wyrdsekai.core.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WyrdConfigTest {

    @Test
    void parsesSectionsAndKeys() {
        var toml = """
            [node]
            name = "home-server"
            zone = "alpha"

            [relay]
            url = "nats://192.0.2.108:4222"
            user = "hh-c460390412f0"
            """;
        var m = WyrdConfig.parseToml(toml);
        assertEquals("home-server", m.get("node.name"));
        assertEquals("alpha", m.get("node.zone"));
        assertEquals("nats://192.0.2.108:4222", m.get("relay.url"));
        assertEquals("hh-c460390412f0", m.get("relay.user"));
    }

    @Test
    void stripsCommentsAndPreservesQuotedHashes() {
        var toml = """
            # full-line comment
            [node]
            name = "home-server"  # trailing comment
            note = "value with # hash inside string"
            """;
        var m = WyrdConfig.parseToml(toml);
        assertEquals("home-server", m.get("node.name"));
        assertEquals("value with # hash inside string", m.get("node.note"));
    }

    @Test
    void handlesBareValuesAndBooleans() {
        var toml = """
            [peer_training]
            host = true
            iters = 60
            """;
        var m = WyrdConfig.parseToml(toml);
        assertEquals("true", m.get("peer_training.host"));
        assertEquals("60", m.get("peer_training.iters"));
    }

    @Test
    void emptyAndBlankLinesSkipped() {
        var toml = """

            [node]

            name = "x"


            """;
        var m = WyrdConfig.parseToml(toml);
        assertEquals(1, m.size());
        assertEquals("x", m.get("node.name"));
    }

    @Test
    void singleQuotedStringsStripped() {
        var toml = """
            [a]
            k = 'value-with-spaces'
            """;
        assertEquals("value-with-spaces", WyrdConfig.parseToml(toml).get("a.k"));
    }

    // --- library research runs a day ---

    @Test void library_research_runs_default_to_five_a_member_and_three_for_her_own_time() {
        var cfg = WyrdConfig.forProfile(Map.of());
        assertEquals(5, cfg.libraryResearchPerDay());
        assertEquals(3, cfg.libraryOwnTimeResearchPerDay());
    }

    @Test void library_research_runs_are_the_stewards_setting() {
        var cfg = WyrdConfig.forProfile(Map.of(
            "library.research_per_day", "2", "library.own_time_research_per_day", "0"));
        assertEquals(2, cfg.libraryResearchPerDay());
        assertEquals(0, cfg.libraryOwnTimeResearchPerDay());
        assertEquals(0, WyrdConfig.forProfile(Map.of("library.research_per_day", "-4")).libraryResearchPerDay(),
            "a negative number is none, not a crash");
    }

    // --- serving profile ---

    @Test void the_default_serving_profile_is_the_two_model_stack() {
        var cfg = WyrdConfig.forProfile(Map.of());
        assertEquals(WyrdConfig.PROFILE_TWO_MODEL, cfg.servingProfile());
        assertFalse(cfg.singleBrain());
        assertTrue(cfg.voicePolish(), "the voice model finishes full-lane drafts");
    }

    @Test void single_sparse_turns_polish_off_unless_asked_for() {
        var cfg = WyrdConfig.forProfile(Map.of("inference.serving_profile", "single-sparse"));
        assertTrue(cfg.singleBrain());
        assertFalse(cfg.voicePolish());
        var kept = WyrdConfig.forProfile(Map.of("inference.serving_profile", "single-sparse", "voice.polish", "true"));
        assertTrue(kept.voicePolish());
    }

    @Test void single_sparse_turns_the_tell_voice_pass_off_even_with_the_voice_key_left_on() {
        // Household node 2026-09-23: moved to single-sparse with WYRDSEKAI_VOICE_ENABLED=true
        // kept from its two-model install, and the pass turned a companion's question into an answer.
        var moved = WyrdConfig.forProfile(Map.of(
            "inference.serving_profile", "single-sparse", "voice.enabled", "true"));
        assertFalse(moved.voicePass());
        var asked = WyrdConfig.forProfile(Map.of(
            "inference.serving_profile", "single-sparse", "voice.enabled", "true", "voice.pass", "true"));
        assertTrue(asked.voicePass(), "an explicit setting still wins");
    }

    @Test void the_tell_voice_pass_follows_the_voice_key_wherever_a_voice_model_speaks() {
        assertTrue(WyrdConfig.forProfile(Map.of("voice.enabled", "true")).voicePass());
        assertFalse(WyrdConfig.forProfile(Map.of()).voicePass(), "no voice model, no pass");
        assertTrue(WyrdConfig.forProfile(Map.of(
            "inference.serving_profile", "sparse-drive", "voice.enabled", "true")).voicePass(),
            "the voice model still speaks under sparse-drive");
        assertFalse(WyrdConfig.forProfile(Map.of("voice.enabled", "true", "voice.pass", "false")).voicePass());
    }

    @Test void a_conversation_turn_carries_her_drives_line_where_one_model_serves_every_lane() {
        assertTrue(WyrdConfig.forProfile(Map.of("inference.serving_profile", "single-sparse")).conversationFeltLine());
        assertFalse(WyrdConfig.forProfile(Map.of("voice.enabled", "true")).conversationFeltLine(),
            "the two-model stack is unchanged");
        assertFalse(WyrdConfig.forProfile(Map.of("inference.serving_profile", "sparse-drive")).conversationFeltLine(),
            "under sparse-drive the voice model still talks");
        assertTrue(WyrdConfig.forProfile(Map.of("conversation.felt_line", "true")).conversationFeltLine(),
            "an explicit setting wins");
        assertFalse(WyrdConfig.forProfile(Map.of(
            "inference.serving_profile", "single-sparse", "conversation.felt_line", "false")).conversationFeltLine());
    }

    @Test void the_register_dial_is_on_with_a_half_top_until_measured() {
        var cfg = WyrdConfig.forProfile(Map.of("inference.serving_profile", "single-sparse"));
        assertTrue(cfg.registerDial());
        assertEquals(0.5, cfg.registerDialMax(), 1e-9);
        var set = WyrdConfig.forProfile(Map.of("register.dial", "false", "register.dial_max", "0.75"));
        assertFalse(set.registerDial());
        assertEquals(0.75, set.registerDialMax(), 1e-9);
    }

    @Test void an_unknown_serving_profile_is_the_two_model_stack() {
        assertFalse(WyrdConfig.forProfile(Map.of("inference.serving_profile", "whatever")).singleBrain());
    }

    @Test void a_large_drive_with_the_voice_kept_is_still_a_two_lane_node() {
        var cfg = WyrdConfig.forProfile(Map.of("inference.serving_profile", "sparse-drive"));
        assertEquals(WyrdConfig.PROFILE_SPARSE_DRIVE, cfg.servingProfile());
        assertFalse(cfg.singleBrain(), "the voice model still speaks");
        assertTrue(cfg.voicePolish(), "and still finishes full-lane drafts");
    }
}
