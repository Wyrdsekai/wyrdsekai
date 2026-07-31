package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #32 item 3 (closing-verify 8d3a172b): goal_done at step 1, ZERO tools,
 * outcome "math task delegated to tool backend — I'll report back". The
 * COMPLETION_CLAIM gate keys on past-tense completion verbs, so an in-flight
 * deferral claim sailed through — but nothing was dispatched, so nothing will
 * ever report back. DEFERRAL_CLAIM closes that shape.
 */
class DeferralClaimTest {

    @Test
    void detects_the_verbatim_closing_verify_claim() {
        assertThat(CompanionActor.claimsDeferral(
            "math task delegated to tool backend — I'll report back")).isTrue();
    }

    @Test
    void detects_common_deferral_shapes() {
        assertThat(CompanionActor.claimsDeferral(
            "I've handed it off to the workshop.")).isTrue();
        assertThat(CompanionActor.claimsDeferral(
            "Delegating this to the coding backend now.")).isTrue();
        assertThat(CompanionActor.claimsDeferral(
            "I will report back as soon as it's ready.")).isTrue();
        assertThat(CompanionActor.claimsDeferral(
            "Once the result comes in, you'll hear from me.")).isTrue();
        assertThat(CompanionActor.claimsDeferral(
            "The job is queued and running in the background.")).isTrue();
        assertThat(CompanionActor.claimsDeferral(
            "Kicked off the build — will get back to you.")).isTrue();
    }

    @Test
    void ignores_direct_answers_and_ordinary_speech() {
        assertThat(CompanionActor.claimsDeferral(null)).isFalse();
        assertThat(CompanionActor.claimsDeferral("")).isFalse();
        assertThat(CompanionActor.claimsDeferral("Two plus two is four.")).isFalse();
        assertThat(CompanionActor.claimsDeferral(
            "I looked it up — tomorrow will be 78F and overcast.")).isFalse();
        // Past-tense report of an already-relayed message is the relay gate's
        // territory, not a deferral.
        assertThat(CompanionActor.claimsDeferral(
            "I reported the outage to operator this morning.")).isFalse();
        // "delegation" as a topic is not a claim of having delegated.
        assertThat(CompanionActor.claimsDeferral(
            "Delegation is hard to get right.")).isFalse();
    }
}
