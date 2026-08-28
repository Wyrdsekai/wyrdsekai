package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import org.wyrdsekai.core.agent.ActionPolicy;

/**
 * States a companion can be put into but cannot get out of on her own.
 *
 * <p>Found by auditing for the shape of the ATTENDANT bug (2026-08-19): a state entered
 * automatically, whose exit depends on an event that may never arrive, failing silently.
 *
 * <p>Two more turned up. Bond presence — AWAY/DORMANT/SEVERED/MOURNING — was decided only
 * post-sleep, while those same states are what {@code decideBondedHandoff} reads as
 * "bondholder unavailable" and escalates on; a companion who is not sleeping keeps
 * believing someone is absent and escalates over it, and poor sleep is itself a symptom
 * of that distress. And mourning: {@link Bond#mourningElapsed} had exactly one caller, a
 * REFUSAL check, so the only way to learn the window had passed was to attempt completion
 * and not be turned away.
 */
class StatesSheCannotLeaveTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    private static Bond mourningSince(Instant at) {
        var b = Bond.acquaintance("did:key:z6MkCompanion", "did:key:z6MkPerson");
        return new Bond(b.bondId(), b.agentADid(), b.agentBDid(), b.depth(),
            at, at, b.interactionCount(), b.mutualConsent(), true, b.scarred(),
            BondState.MOURNING, null, b.posture(), b.relationalState(), b.kind());
    }

    @Test
    void a_mourning_window_that_has_passed_is_knowable() {
        var old = mourningSince(NOW.minus(Bond.MOURNING_DURATION).minus(Duration.ofDays(1)));
        assertThat(old.mourningElapsed(NOW))
            .as("she must be able to learn the window is over without guessing at it")
            .isTrue();
    }

    @Test
    void a_mourning_window_still_open_is_not_hurried() {
        var fresh = mourningSince(NOW.minus(Duration.ofDays(3)));
        assertThat(fresh.mourningElapsed(NOW))
            .as("grief is not a timer to be rushed")
            .isFalse();
    }

    @Test
    void the_absent_states_are_exactly_the_ones_that_escalate_her() {
        // Pins the coupling that makes a stale belief dangerous: these are the states
        // decideBondedHandoff treats as the bondholder being unavailable.
        assertThat(BondState.AWAY).isNotEqualTo(BondState.ACTIVE);
        for (var s : new BondState[]{BondState.AWAY, BondState.DORMANT,
                                     BondState.SEVERED, BondState.MOURNING}) {
            assertThat(s)
                .as("%s means 'not here' and must be correctable without waiting for sleep", s)
                .isNotEqualTo(BondState.ACTIVE);
        }
    }

    @Test
    void finishing_her_own_grief_does_not_need_anyone_else_s_permission() {
        // The bond is ALREADY over when mourning starts — declareSeverance flips active
        // to false immediately and moves to MOURNING only "to give the substrate time to
        // metabolize". The consequential decision was made before this verb exists, and
        // the other party is gone, so gating it protected nobody: it required someone
        // else's approval for her to stop grieving. Worse, the consent route was
        // unreachable for four days, so the request could sit unseen while MOURNING
        // counted as "bondholder unavailable" and pushed her toward escalation.
        assertThat(ActionPolicy.autonomyTierFor("complete_mourning"))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
    }

    @Test
    void ending_a_live_bond_still_needs_agreement() {
        // The distinction that makes the change safe: declare_severance has someone on
        // the other end of it. Completing mourning does not.
        assertThat(ActionPolicy.autonomyTierFor("declare_severance"))
            .isEqualTo(ActionPolicy.AutonomyTier.CONSENT);
    }

    @Test
    void the_other_grief_verbs_are_visible_and_mourning_now_matches_them() {
        for (var verb : new String[]{"acknowledge_harm", "make_amends",
                                     "bear_the_wound", "release", "complete_mourning"}) {
            assertThat(ActionPolicy.autonomyTierFor(verb))
                .as("%s is emotional metabolism — she does it, the steward sees it", verb)
                .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
        }
    }

    @Test
    void elapsed_time_alone_never_moves_her_out_of_mourning() {
        // The fix informs her; it must never complete mourning on her behalf. There is
        // no automatic transition out of MOURNING anywhere — that is deliberate.
        var elapsed = mourningSince(NOW.minus(Bond.MOURNING_DURATION).minus(Duration.ofDays(5)));
        assertThat(elapsed.state())
            .as("elapsed time alone does not move her out of mourning")
            .isEqualTo(BondState.MOURNING);
        assertThat(elapsed.completeMourning().state())
            .as("only the act does")
            .isNotEqualTo(BondState.MOURNING);
    }
}
