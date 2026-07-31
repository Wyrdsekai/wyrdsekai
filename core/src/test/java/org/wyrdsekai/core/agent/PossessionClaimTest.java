package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rita re-verify 2026-07-11 (#29): goal_done "script now in hand" after
 * take_item came back not_found. take_item counts as a productive tool even
 * when it FAILED, so the 2026-07-08 anti-false-completion gate passed; the
 * possession gate keys on the room's rejection plus these claim shapes.
 */
class PossessionClaimTest {

    @Test
    void detects_the_verbatim_secondNode_claim() {
        assertThat(CompanionActor.claimsPossession(
            "The behavior script is now in hand — installing it next.")).isTrue();
    }

    @Test
    void detects_common_possession_shapes() {
        assertThat(CompanionActor.claimsPossession("I have the script.")).isTrue();
        assertThat(CompanionActor.claimsPossession("I took the greeter script from the shelf.")).isTrue();
        assertThat(CompanionActor.claimsPossession("Picked it up, all set.")).isTrue();
        assertThat(CompanionActor.claimsPossession("picked up the script")).isTrue();
        assertThat(CompanionActor.claimsPossession("I grabbed it on my way through.")).isTrue();
        assertThat(CompanionActor.claimsPossession("It's in my inventory now.")).isTrue();
        assertThat(CompanionActor.claimsPossession("I am holding the lantern.")).isTrue();
        assertThat(CompanionActor.claimsPossession("We now have the key.")).isTrue();
    }

    @Test
    void ignores_non_possession_speech() {
        assertThat(CompanionActor.claimsPossession(null)).isFalse();
        assertThat(CompanionActor.claimsPossession("")).isFalse();
        assertThat(CompanionActor.claimsPossession(
            "The room didn't have the script — I couldn't find it.")).isFalse();
        assertThat(CompanionActor.claimsPossession(
            "Let me search the library for that.")).isFalse();
        // "took" in the temporal sense must not fire the item gate by itself.
        assertThat(CompanionActor.claimsPossession(
            "The search took quite a while.")).isFalse();
    }
}
