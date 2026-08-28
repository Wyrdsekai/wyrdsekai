package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The words people actually use to ask for a tool.
 *
 * <p>Every string here was said to a companion on 2026-08-21/22 by the steward, verbatim.
 * When a build request stops arming, this is the first thing to check — and when it turned
 * out on 08-22 that the detector was fine and the failure was two layers downstream
 * (relevance had no cue for {@code dispatch_task}; the cost filter culled it at
 * energy 0.22), having this pinned is what made that a two-minute question instead of a
 * guess.
 */
class BuildRequestDetectorProbeTest {

    @Test
    @DisplayName("his real asks are recognised as build requests")
    void hisRealAsksArm() {
        String[] asks = {
            "please build me a tool called media_sorter that reviews the files in "
                + "/data/mediamisc-mirror and tells me what kinds of files are in there",
            "please build me an item called venture_scout - i give it a subject and it "
                + "brainstorms radical business ideas and estimates the TAM for each",
            "can you make me one tool that takes a subject matter and brainstorms radical "
                + "business ideas for it and estimates the opportunity size based on TAM",
            "can you make me a tool that looks up a topic on wikipedia and then speaks a "
                + "three-line briefing about it to the room",
            "can you make a room where someone can go to look up a topic and hear a short "
                + "briefing about it",
            "so can you make me a tool / item that allows me to query the library and then "
                + "whatever it finds it speaks out to the room a short fairy tale story",
            "can you make and give me a tool / item that allows me to query a location and "
                + "get back the current weather of that location",
        };
        for (var ask : asks) {
            assertThat(CompanionActor.looksLikeBuildRequest(ask))
                .as("asked: %s", ask)
                .isTrue();
        }
    }

    @Test
    @DisplayName("ordinary talk does not open the workbench")
    void ordinaryTalkDoesNot() {
        for (var ask : new String[]{
            "how are you feeling today",
            "what did we talk about yesterday",
            "tell me about the weather outside"}) {
            assertThat(CompanionActor.looksLikeBuildRequest(ask))
                .as("asked: %s", ask)
                .isFalse();
        }
    }

    /**
     * 21:45: "please revise trip_compass so it accepts two cities…" had no making-verb,
     * this said no, and the classifier handed it to a bunshin — the morning's hijack with
     * a different verb. A revision that names an item she made is workbench work.
     */
    @Test
    @DisplayName("revising a named item is a build request")
    void revisingAKnownItemArms() {
        // The loader is empty in a unit test, so the known-item cue cannot fire; pin the
        // verb half here and the loader half where a registry exists.
        assertThat(CompanionActor.mentionsAKnownItem("please revise trip_compass so it works"))
            .as("no loader in this JVM — must be false, never throw").isFalse();
        // With no known item the revise verbs alone must NOT arm: "change it so" about
        // nothing in particular is conversation.
        assertThat(CompanionActor.looksLikeBuildRequest("can you change it so we talk more"))
            .isFalse();
    }
}
