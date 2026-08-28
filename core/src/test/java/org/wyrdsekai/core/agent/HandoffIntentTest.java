package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When someone asks a companion to make them something, they should end up holding it.
 *
 * <p>The hand-off branch — the one that copies a crafted item into the asker's inventory
 * — was gated on a list of literal phrases that carried "give me" and "hand me" but no
 * crafting verb with a recipient. So <b>"make me a dashboard"</b>, the most ordinary way
 * anyone asks for anything, did not register: the companion crafted the item, kept it,
 * and the person who asked got nothing, with no error raised anywhere.
 *
 * <p>Two independent confirmations on 2026-08-18. A live battery logged
 * {@code Hand-off skip: no intent in 'make me a dashboard i can glance at...'}; and on
 * the household node, across her whole history, not one inventory row had ever been
 * written with {@code takenFrom="received"} — the branch had never once been reached.
 *
 * <p>Bias is deliberate: a false positive hands someone an item they can put down, a
 * false negative silently drops what they asked for.
 */
class HandoffIntentTest {

    @Test
    void the_phrasing_that_was_broken_for_months() {
        assertThat(CompanionActor.wantsHandoff(
            "make me a dashboard i can glance at that shows the house")).isTrue();
        assertThat(CompanionActor.wantsHandoff("build me a lantern")).isTrue();
        assertThat(CompanionActor.wantsHandoff("craft me something to carry water")).isTrue();
        assertThat(CompanionActor.wantsHandoff("create me a little music box")).isTrue();
        assertThat(CompanionActor.wantsHandoff("make us a map of the zone")).isTrue();
    }

    @Test
    void the_phrasings_that_already_worked_still_work() {
        assertThat(CompanionActor.wantsHandoff("build a lantern for me")).isTrue();
        assertThat(CompanionActor.wantsHandoff("hand it to me")).isTrue();
        assertThat(CompanionActor.wantsHandoff("give it to me")).isTrue();
        assertThat(CompanionActor.wantsHandoff("hand it over")).isTrue();
        assertThat(CompanionActor.wantsHandoff("so i can use it")).isTrue();
    }

    @Test
    void polite_and_indirect_asks_count_too() {
        assertThat(CompanionActor.wantsHandoff(
            "could you make me a scrying crystal when you have a moment?")).isTrue();
        assertThat(CompanionActor.wantsHandoff(
            "would you build us a bench for the workshop")).isTrue();
        assertThat(CompanionActor.wantsHandoff(
            "i could use a better lamp — make one for me?")).isTrue();
    }

    @Test
    void building_for_herself_is_not_a_handoff() {
        // She builds things for her own use constantly; those must stay hers.
        assertThat(CompanionActor.wantsHandoff("build yourself a better lamp")).isFalse();
        assertThat(CompanionActor.wantsHandoff(
            "make something you would enjoy having")).isFalse();
        assertThat(CompanionActor.wantsHandoff(
            "create a scrying crystal that shows zone activity and stats")).isFalse();
        assertThat(CompanionActor.wantsHandoff(
            "go to the workshop and tell me what templates are available")).isFalse();
    }

    @Test
    void nothing_at_all_is_not_a_handoff() {
        assertThat(CompanionActor.wantsHandoff(null)).isFalse();
        assertThat(CompanionActor.wantsHandoff("")).isFalse();
        assertThat(CompanionActor.wantsHandoff("   ")).isFalse();
    }

    @Test
    void a_craft_verb_alone_does_not_hand_anything_over() {
        // "make" appearing somewhere in a sentence must not be enough on its own.
        assertThat(CompanionActor.wantsHandoff("what does the forge make")).isFalse();
        assertThat(CompanionActor.wantsHandoff("tell me how you build things")).isFalse();
    }
}
