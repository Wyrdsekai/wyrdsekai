package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.interiority.CandidateWant;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The decide step: tired is not the same as not missing anyone; a held want is enacted when she is free. */
class PickCandidateTest {

    private static CandidateWant rest() { return CandidateWant.of("rest", "Energy", 0.3); }

    @Test
    void at_low_energy_a_relational_want_that_weighs_enough_still_goes_out() {
        var reach = CandidateWant.of("check in on someone I care about", "{\"drive\":\"Care\",\"verb\":\"tell_agent\"}", 0.42);
        var explore = CandidateWant.of("explore the library for something new", "{\"drive\":\"Curiosity\",\"verb\":\"library_search\"}", 0.9);
        var pick = CompanionActor.pickCandidate(List.of(explore, reach, rest()), 0.15, new ArrayDeque<>()).orElseThrow();
        assertEquals(reach, pick, "the reach, not the heavier explore, and not rest");
        // a light relational pull still rests
        var faint = CandidateWant.of("be near someone", "Affiliation", 0.1);
        assertTrue(CompanionActor.pickCandidate(List.of(explore, faint, rest()), 0.15, new ArrayDeque<>()).orElseThrow().isRest());
        // nothing relational: rest, as before
        assertTrue(CompanionActor.pickCandidate(List.of(explore, rest()), 0.15, new ArrayDeque<>()).orElseThrow().isRest());
        // no rest on offer at low energy: the heaviest by felt weight
        assertEquals(explore, CompanionActor.pickCandidate(List.of(explore, faint), 0.15, new ArrayDeque<>()).orElseThrow());
    }

    @Test
    void with_energy_the_heaviest_fresh_want_wins_and_a_repeated_verb_is_discounted() {
        var reach = CandidateWant.of("check in on someone I care about", "{\"drive\":\"Care\",\"verb\":\"tell_agent\"}", 0.42);
        var explore = CandidateWant.of("explore the library for something new", "{\"drive\":\"Curiosity\",\"verb\":\"library_search\"}", 0.6);
        assertEquals(explore, CompanionActor.pickCandidate(List.of(explore, reach, rest()), 0.8, new ArrayDeque<>()).orElseThrow());
        var recent = new ArrayDeque<String>(List.of("library_search", "library_search", "library_search", "library_search"));
        assertEquals(reach, CompanionActor.pickCandidate(List.of(explore, reach, rest()), 0.8, recent).orElseThrow(),
            "four library searches in a row: the explore is discounted to 0.12 and the reach wins");
        assertTrue(CompanionActor.pickCandidate(List.of(), 0.8, new ArrayDeque<>()).isEmpty());
    }

    @Test
    void a_held_want_is_enacted_when_she_is_free_and_lapses_after_the_ttl() {
        var t0 = Instant.parse("2026-09-10T03:00:00Z");
        assertEquals("hold", CompanionActor.deferredWantFate(t0, t0.plusSeconds(60), false, false));
        assertEquals("hold", CompanionActor.deferredWantFate(t0, t0.plusSeconds(60), true, true));
        assertEquals("enact", CompanionActor.deferredWantFate(t0, t0.plusSeconds(60), true, false));
        assertEquals("lapsed", CompanionActor.deferredWantFate(t0, t0.plus(CompanionActor.DEFERRED_WANT_TTL).plusSeconds(1), true, false));
        assertEquals("lapsed", CompanionActor.deferredWantFate(null, t0, true, false));
        assertEquals("Care", CompanionActor.driveOf(CandidateWant.of("x", "{\"drive\":\"Care\",\"verb\":\"tell_agent\"}", 0.5)));
        assertEquals("Affiliation", CompanionActor.driveOf(CandidateWant.of("x", "Affiliation", 0.5)), "a bare drive name still reads");
    }
}
