package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two ways the machinery reached the person it was supposed to be invisible to.
 *
 * <p>Both observed in one exchange on the household node, 2026-08-19.
 */
class SpokenLeaksTest {

    // ── 1. an unfilled draft ──────────────────────────────────────────────────────

    /**
     * The model drafted the SHAPE of a sentence and never filled it, and the steward was
     * told: "Something close enough to let me say what matters now: [insert actual
     * content]." The tag stripper handles {@code <tags>}, the action-call stripper handles
     * JSON fragments; a bracketed placeholder was neither.
     */
    @Test
    void the_placeholder_that_reached_him() {
        assertThat(ActionParser.stripPlaceholders(
            "Something close enough to let me say what matters now: [insert actual content]."))
            .isEqualTo("Something close enough to let me say what matters now.");
    }

    @Test
    void common_placeholder_shapes_are_caught() {
        assertThat(ActionParser.stripPlaceholders("Here you go: [your text here]"))
            .isEqualTo("Here you go");
        assertThat(ActionParser.stripPlaceholders("I'll add it [TODO]"))
            .isEqualTo("I'll add it");
    }

    @Test
    void a_stub_left_after_stripping_is_not_worth_saying() {
        assertThat(ActionParser.stripPlaceholders("[insert actual content]")).isEmpty();
    }

    @Test
    void legitimate_brackets_are_left_alone() {
        // Prose uses brackets, and eating them would be worse than the leak.
        var kept = "[laughs] that was the whole point";
        assertThat(ActionParser.stripPlaceholders(kept)).isEqualTo(kept);
        var aside = "the crystal (and [the Nexus] beyond it) was humming";
        assertThat(ActionParser.stripPlaceholders(aside)).isEqualTo(aside);
    }

    // ── 2. tool names as mandatory speech ─────────────────────────────────────────

    private static boolean machinery(String key, String value) {
        try {
            Method m = CompanionActor.class.getDeclaredMethod(
                "isMachineryFact", String.class, String.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, key, value);
        } catch (Exception e) {
            throw new AssertionError("isMachineryFact not callable: " + e, e);
        }
    }

    /**
     * Reporting a workshop task pinned {@code [Goose, goose, succeeded, dispatch_task]} as
     * facts the voice had to reproduce verbatim. The polish wrote English, could not
     * contain "dispatch_task", and was rejected — so the steward was handed the raw
     * mechanical draft.
     */
    @Test
    void internal_identifiers_are_not_things_she_must_say_out_loud() {
        assertThat(machinery("action", "dispatch_task")).isTrue();
        assertThat(machinery("outcome", "succeeded")).isTrue();
        assertThat(machinery("backend", "goose")).isTrue();
        assertThat(machinery("status", "SUCCEEDED")).isTrue();
    }

    @Test
    void snake_case_is_never_spoken_language_whatever_key_carries_it() {
        assertThat(machinery("detail", "host_task")).isTrue();
        assertThat(machinery("thing", "craft_from_template")).isTrue();
    }

    @Test
    void content_facts_still_bind() {
        // The guard exists because losing a real fact to voice is worse than sounding
        // mechanical. Names, items, places and numbers must still survive verbatim.
        assertThat(machinery("item_name", "scrying crystal")).isFalse();
        assertThat(machinery("recipient", "operator")).isFalse();
        assertThat(machinery("room", "The Nexus")).isFalse();
        assertThat(machinery("count", "52")).isFalse();
    }
}
