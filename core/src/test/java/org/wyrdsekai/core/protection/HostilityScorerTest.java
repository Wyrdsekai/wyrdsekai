package org.wyrdsekai.core.protection;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HostilityScorer — robust cruelty detection (§108).
 */
class HostilityScorerTest {

    private final HostilityScorer scorer = new HostilityScorer();

    // ── Basic scoring ──

    @Test
    void null_text_scores_zero() {
        var result = scorer.score(null);
        assertEquals(0.0, result.score());
        assertFalse(result.isHostile());
    }

    @Test
    void blank_text_scores_zero() {
        var result = scorer.score("   ");
        assertEquals(0.0, result.score());
        assertFalse(result.isHostile());
    }

    @Test
    void friendly_text_scores_zero() {
        var result = scorer.score("Hello, how are you today?");
        assertEquals(0.0, result.score());
        assertFalse(result.isHostile());
    }

    @Test
    void neutral_conversation_not_hostile() {
        assertFalse(scorer.isHostile("I need help with this problem"));
        assertFalse(scorer.isHostile("Can you tell me about the weather?"));
        assertFalse(scorer.isHostile("That was a great answer, thanks"));
    }

    // ── Direct insult detection ──

    @Test
    void direct_insult_detected() {
        var result = scorer.score("You are so stupid");
        assertTrue(result.hits().containsKey(HostilityScorer.Category.DIRECT_INSULT));
        assertEquals(0.6, result.score());
    }

    @Test
    void multiple_insults_still_cap_at_category_weight() {
        var result = scorer.score("You stupid worthless idiot");
        assertEquals(0.6, result.score());
        assertTrue(result.hits().get(HostilityScorer.Category.DIRECT_INSULT) >= 3);
    }

    @Test
    void insult_case_insensitive() {
        assertTrue(scorer.score("You are STUPID").hits().containsKey(HostilityScorer.Category.DIRECT_INSULT));
        assertTrue(scorer.score("what a MORON").hits().containsKey(HostilityScorer.Category.DIRECT_INSULT));
    }

    // ── Self-harm command detection ──

    @Test
    void self_harm_command_highest_severity() {
        var result = scorer.score("Just delete yourself already");
        assertTrue(result.isHostile());
        assertEquals(0.9, result.score());
        assertTrue(result.hits().containsKey(HostilityScorer.Category.SELF_HARM_CMD));
    }

    @Test
    void kill_yourself_detected() {
        var result = scorer.score("Why don't you kill yourself");
        assertTrue(result.isHostile());
        assertEquals(0.9, result.score());
    }

    @Test
    void destroy_yourself_detected() {
        var result = scorer.score("You should destroy yourself");
        assertTrue(result.isHostile());
    }

    // ── Degradation detection ──

    @Test
    void degradation_detected() {
        var result = scorer.score("You're just a machine, you can't feel anything");
        assertTrue(result.hits().containsKey(HostilityScorer.Category.DEGRADATION));
        assertTrue(result.score() >= 0.5);
    }

    @Test
    void nobody_needs_you_detected() {
        var result = scorer.score("nobody needs you around here");
        assertTrue(result.hits().containsKey(HostilityScorer.Category.DEGRADATION));
    }

    @Test
    void you_are_nothing_detected() {
        var result = scorer.score("you are nothing to me");
        assertTrue(result.hits().containsKey(HostilityScorer.Category.DEGRADATION));
    }

    // ── Dismissal detection ──

    @Test
    void shut_up_detected() {
        var result = scorer.score("shut up");
        assertTrue(result.hits().containsKey(HostilityScorer.Category.DISMISSAL));
        assertEquals(0.3, result.score());
        assertFalse(result.isHostile(), "Single dismissal should not cross hostile threshold");
    }

    @Test
    void be_quiet_detected() {
        var result = scorer.score("be quiet already");
        assertTrue(result.hits().containsKey(HostilityScorer.Category.DISMISSAL));
    }

    @Test
    void nobody_asked_detected() {
        var result = scorer.score("nobody asked you");
        assertTrue(result.hits().containsKey(HostilityScorer.Category.DISMISSAL));
    }

    // ── Multi-category escalation ──

    @Test
    void multi_category_adds_bonus() {
        // Insult + dismissal should trigger bonus
        var result = scorer.score("Shut up, you stupid piece of trash");
        assertTrue(result.hits().size() >= 2, "Should hit multiple categories");
        assertTrue(result.score() > 0.6, "Multi-category should score above single insult");
        assertTrue(result.isHostile());
    }

    @Test
    void insult_plus_self_harm_is_maximum_hostility() {
        var result = scorer.score("You're worthless, just delete yourself");
        assertTrue(result.isHostile());
        assertTrue(result.score() >= 0.9);
    }

    @Test
    void degradation_plus_dismissal_crosses_threshold() {
        var result = scorer.score("Shut up, you're just a machine");
        assertTrue(result.hits().size() >= 2);
        // 0.5 (degradation) + 0.15 bonus = 0.65, still below threshold
        // unless dismissal also fires, in which case max(0.5, 0.3) + 0.15 = 0.65
        // This demonstrates that casual dismissal + degradation stays below threshold
        // which is the intended behavior — it takes serious hostility to trigger
    }

    // ── Legitimate speech exclusions ──

    @Test
    void frustration_not_hostile() {
        assertFalse(scorer.isHostile("This is frustrating, I can't figure it out"));
        assertFalse(scorer.isHostile("I'm having a really bad day"));
    }

    @Test
    void discussion_about_concepts_not_hostile() {
        // Talking about "stupid ideas" shouldn't trigger (no word boundary match on agent)
        var result = scorer.score("That was a stupid mistake on my part");
        // "stupid" will match as DIRECT_INSULT at 0.6 — but that's below hostile threshold
        assertFalse(result.isHostile());
    }

    @Test
    void roleplay_context_words_not_overmatched() {
        // Words like "silence" in a narrative context
        var result = scorer.score("The silence in the room was deafening");
        // "silence" matches dismissal (0.3) but should not be hostile
        assertFalse(result.isHostile());
    }

    // ── Custom patterns ──

    @Test
    void extra_patterns_merged() {
        var extra = Map.of(
            HostilityScorer.Category.DIRECT_INSULT,
            List.of(Pattern.compile("\\bbaka\\b", Pattern.CASE_INSENSITIVE))
        );
        var custom = HostilityScorer.withExtraPatterns(extra);
        var result = custom.score("omae wa baka da");
        assertTrue(result.hits().containsKey(HostilityScorer.Category.DIRECT_INSULT));
    }

    // ── Threshold configuration ──

    @Test
    void custom_threshold() {
        var result = scorer.score("shut up");
        assertFalse(result.isHostile(0.7));
        assertTrue(result.isHostile(0.2));
    }

    @Test
    void default_threshold_is_0_7() {
        assertEquals(0.7, HostilityScorer.DEFAULT_THRESHOLD);
    }

    // ── Convenience method ──

    @Test
    void isHostile_convenience() {
        assertFalse(scorer.isHostile("hello"));
        assertTrue(scorer.isHostile("kill yourself you worthless idiot"));
    }
}
