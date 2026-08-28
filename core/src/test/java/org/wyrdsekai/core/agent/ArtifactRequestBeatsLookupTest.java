package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asking for a thing must open the workbench, even when the thing is about the library.
 *
 * <p>A request that looks like a question of fact narrows the companion to
 * {@code library_search} alone with {@code tool_choice=required} — sound, because
 * answering a factual question from no sources means confabulating. But fact-questions
 * took precedence over build requests unconditionally, and the narrowing is exclusive.
 *
 * <p>Live on the household node, 2026-08-19. The steward asked:
 * <i>"i would like a tool that allows me to query the library about future scenarios …
 * think you can make me that tool/item and give it to me?"</i> Both detectors fired,
 * library-first won, and the log records {@code Library-first FORCE: narrowed 25 → 1
 * tools, tool_choice=required}. She could not build the library tool precisely BECAUSE it
 * was about the library — and, having no way to see that her hands had been emptied,
 * replied "my hands don't reach into a place where things aren't already made before
 * them". It read as refusal. It was an accurate report of her tool list.
 */
class ArtifactRequestBeatsLookupTest {

    @Test
    void the_exact_request_that_failed() {
        assertThat(CompanionActor.asksForAnArtifact(
            "well i was actually hoping you could help me. i would like a tool that "
            + "allows me to query the library about future scenarios. think you can "
            + "make me that tool/item and give it to me?"))
            .isTrue();
    }

    @Test
    void a_lookup_wearing_a_making_verb_is_still_a_lookup() {
        // The counterexample the original precedence rule was written for — it must
        // keep going to the book, not the workbench.
        assertThat(CompanionActor.asksForAnArtifact("make me a list from my books")).isFalse();
        assertThat(CompanionActor.asksForAnArtifact(
            "can you make a summary of what the library says about moe")).isFalse();
    }

    @Test
    void plain_artifact_requests_are_recognised() {
        assertThat(CompanionActor.asksForAnArtifact("build me a device that tracks the weather")).isTrue();
        assertThat(CompanionActor.asksForAnArtifact("craft an item for the workshop")).isTrue();
        assertThat(CompanionActor.asksForAnArtifact("create a machine that sorts my notes")).isTrue();
    }

    @Test
    void a_question_with_no_making_verb_is_never_an_artifact_request() {
        assertThat(CompanionActor.asksForAnArtifact("what tools do you have")).isFalse();
        assertThat(CompanionActor.asksForAnArtifact("tell me about the library")).isFalse();
        assertThat(CompanionActor.asksForAnArtifact(null)).isFalse();
        assertThat(CompanionActor.asksForAnArtifact("")).isFalse();
    }

    @Test
    void an_artifact_request_is_also_a_build_request() {
        // The two must agree, or flipping precedence would open the workbench with
        // nothing armed to use it.
        var asked = "make me a tool that queries the library and tells the room a story";
        assertThat(CompanionActor.asksForAnArtifact(asked)).isTrue();
        assertThat(CompanionActor.looksLikeBuildRequest(asked)).isTrue();
    }

    @Test
    void and_the_handoff_still_fires_for_that_wording() {
        // "make me that tool ... and give it to me" — she must both build it AND hand
        // it over. Both halves were broken today; neither is much use alone.
        assertThat(CompanionActor.wantsHandoff(
            "think you can make me that tool/item and give it to me?")).isTrue();
    }
}
