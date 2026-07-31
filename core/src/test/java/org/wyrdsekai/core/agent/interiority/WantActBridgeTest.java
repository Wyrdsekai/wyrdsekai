package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic coverage for the own-time WANT → ACT bridge, v2:
 * drive-dominant resolution to AUTONOMOUS-SURFACE verbs.
 *
 * <p>Guarantees: (1) welfare floor — nothing fires below {@link WantActBridge#ACT_THRESHOLD}
 * (the over-eager control stays 0); (2) the dominant pulling drive maps to the verb that actually
 * exists in the own-time surface (the v1 bug was emitting reactive verbs like tell_agent that the
 * surface never offers); (3) DIRECT vs FORCE_TOOL classification; (4) overload → seek_sanctuary.
 */
class WantActBridgeTest {

    private static final Map<String, Double> RESTING = Map.of("Creativity", 0.2, "Curiosity", 0.1);

    private static Map<String, Double> pulling(String drive) {
        return Map.of(drive, 0.9);
    }

    // ── Welfare floor (restraint / over-eager=0) ──────────────────────────────────

    @Test void below_threshold_defers() {
        assertThat(WantActBridge.decide(null, "explore the library", RESTING, WantActBridge.HEURISTIC)
            .isDefer()).isTrue();
    }

    @Test void equanimity_is_not_a_pull() {
        var calm = Map.of("equanimity", 0.95, "Creativity", 0.3);
        assertThat(WantActBridge.decide(null, "make something", calm, WantActBridge.HEURISTIC)
            .isDefer()).isTrue();
        assertThat(WantActBridge.dominantPull(calm)).isLessThan(WantActBridge.ACT_THRESHOLD);
    }

    // ── Drive-dominant → SURFACE verb (the load-bearing fix) ──────────────────────

    @Test void curiosity_dominant_resolves_library_search_DIRECT() {
        var d = WantActBridge.decide(null, "I want to read something", pulling("Curiosity"),
            WantActBridge.HEURISTIC);
        assertThat(d.mode()).isEqualTo(WantActBridge.Mode.DIRECT);
        assertThat(d.verb()).isEqualTo("library_search");
    }

    @Test void affiliation_dominant_resolves_sending_stone_FORCE_TOOL() {
        var d = WantActBridge.decide(null, "reach toward Vesna", pulling("Affiliation"),
            WantActBridge.HEURISTIC);
        assertThat(d.mode()).isEqualTo(WantActBridge.Mode.FORCE_TOOL);
        assertThat(d.verb()).isEqualTo("sending_stone");   // NOT tell_agent (the v1 bug)
    }

    @Test void grief_dominant_resolves_bear_the_wound_FORCE_TOOL() {
        var d = WantActBridge.decide(null, "sit with a loss", pulling("Grief"), WantActBridge.HEURISTIC);
        assertThat(d.mode()).isEqualTo(WantActBridge.Mode.FORCE_TOOL);
        assertThat(d.verb()).isEqualTo("bear_the_wound");
    }

    @Test void play_dominant_resolves_emote_FORCE_TOOL() {
        var d = WantActBridge.decide(null, "lighten the moment", pulling("Play"), WantActBridge.HEURISTIC);
        assertThat(d.verb()).isEqualTo("emote");
    }

    @Test void vigilance_dominant_resolves_examine_FORCE_TOOL() {
        var d = WantActBridge.decide(null, "check the room", pulling("Vigilance"), WantActBridge.HEURISTIC);
        assertThat(d.verb()).isEqualTo("examine");
    }

    @Test void overload_vigilance_and_frustration_resolves_seek_sanctuary() {
        var overload = Map.of("Vigilance", 0.7, "Frustration", 0.7, "Grief", 0.3);
        var d = WantActBridge.decide(null, "too much, step back", overload, WantActBridge.HEURISTIC);
        assertThat(d.verb()).isEqualTo("seek_sanctuary");   // overload beats bare vigilance→examine
    }

    // ── Text fallback (drive not in the drive→verb map) ───────────────────────────

    @Test void text_fallback_when_dominant_drive_unmapped() {
        // Loneliness is pulling but not in DRIVE_TOOL → falls to the text resolver.
        var d = WantActBridge.decide(null, "sit with you for a while, no agenda",
            Map.of("Loneliness", 0.9), WantActBridge.HEURISTIC);
        assertThat(d.verb()).isEqualTo("sending_stone");
    }

    @Test void unmapped_drive_and_no_text_match_defers() {
        var d = WantActBridge.decide(null, "just be, quietly",
            Map.of("Obligation", 0.9), WantActBridge.HEURISTIC);
        assertThat(d.isDefer()).isTrue();   // honest: never force an act we can't name
    }

    // ── stripWantPrefix ───────────────────────────────────────────────────────────

    @Test void strips_leading_want_phrasing_and_trailing_period() {
        assertThat(WantActBridge.stripWantPrefix("I want to read something old."))
            .isEqualTo("read something old");
        assertThat(WantActBridge.stripWantPrefix("explore the stacks"))
            .isEqualTo("explore the stacks");
    }
}
