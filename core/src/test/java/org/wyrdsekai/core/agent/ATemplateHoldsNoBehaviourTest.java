package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request that describes DOING cannot be answered by a template alone.
 *
 * <p>Live on the household node 2026-08-20. Asked for "a tool that allows me to query the
 * library and then whatever it finds it speaks out loud to the room a story based on what
 * it found", build-first routing worked correctly — the workbench opened, not the coding
 * backend. She then called {@code craft_from_template} with {@code template='mailbox'},
 * {@code name='query-messenger'} and <b>no script</b>. What got built was
 * {@code inherit("std/container")} plus a label and a capacity: a box, handed over as
 * "equipped and ready to use".
 *
 * <p>Nothing objected. A template name is always valid, and there was no config to report
 * as dropped — the previous guard only catches settings a template cannot HOLD, and here
 * she supplied none. The requirement lived entirely in the item's name.
 *
 * <p>The cause is a lesson already recorded here: an optional parameter is one a small
 * model will not fill. Naming a template is the cheapest path through the tool contract.
 * The template itself came from fuzzy word-matching her own item name —
 * "query-<b>messenger</b>" → mailbox — not from what the thing had to do.
 */
class ATemplateHoldsNoBehaviourTest {

    /** The request that produced the box, verbatim. */
    private static final String LIVE_REQUEST =
        "so can you make me a tool / item that allows me to query the library and then "
        + "whatever it finds it speaks out lout to the room a story based on what it "
        + "found.  the story can not exceed 2 paragrahs of text.  can you make it and "
        + "then give me the tool";

    @Test
    void the_request_that_produced_a_box_is_recognised_as_behaviour() {
        assertThat(CompanionActor.describesBehaviour(LIVE_REQUEST)).isTrue();
    }

    @Test
    void it_is_still_recognised_as_a_build_request() {
        // Both must hold: build-first opens the workbench, and the behaviour check then
        // insists the workbench produce something that acts.
        assertThat(CompanionActor.looksLikeBuildRequest(LIVE_REQUEST)).isTrue();
        assertThat(CompanionActor.asksForAnArtifact(LIVE_REQUEST)).isTrue();
    }

    @Test
    void a_verb_the_item_must_perform_counts() {
        assertThat(CompanionActor.describesBehaviour(
            "make me something that searches my books")).isTrue();
        assertThat(CompanionActor.describesBehaviour(
            "a thing that tells me the weather")).isTrue();
        assertThat(CompanionActor.describesBehaviour(
            "an item that converts celsius to fahrenheit")).isTrue();
    }

    @Test
    void a_rule_it_must_follow_is_behaviour_even_with_no_verb() {
        assertThat(CompanionActor.describesBehaviour(
            "a lantern that whenever someone enters, it brightens")).isTrue();
        assertThat(CompanionActor.describesBehaviour(
            "a box, but it cannot exceed ten items")).isTrue();
    }

    @Test
    void asking_for_a_plain_thing_is_not_behaviour() {
        // The guard must not fire on requests a template genuinely answers, or every
        // simple craft turns into a scripting exercise.
        assertThat(CompanionActor.describesBehaviour("make me a book")).isFalse();
        assertThat(CompanionActor.describesBehaviour("can you craft a lantern")).isFalse();
        assertThat(CompanionActor.describesBehaviour("build us a garden bench")).isFalse();
        assertThat(CompanionActor.describesBehaviour(null)).isFalse();
        assertThat(CompanionActor.describesBehaviour("   ")).isFalse();
    }
}
