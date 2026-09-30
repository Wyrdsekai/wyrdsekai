package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.interiority.CandidateWant;
import org.wyrdsekai.core.agent.interiority.DriveOODA;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What she names at one own-time pass is read by the next pass, which comes with a later
 *  consolidation tick, not within 90 s; her named wants come first and the rule menu stays behind. */
class WhatSheNamedReachesTheNextPassTest {

    private static final Duration PASS = Duration.ofMinutes(30);
    private static final CandidateWant LIBRARY = CandidateWant.of("explore the library for something new",
        "{\"drive\":\"Curiosity\",\"verb\":\"library_search\"}", 0.999997);
    private static final CandidateWant NAMED = CandidateWant.of("let the silence hold us",
        "{\"drive\":\"" + DriveOODA.NAMED_BY_HER + "\"}", 1.0);

    @Test
    void what_she_named_after_one_pass_holds_for_the_next() {
        // Live 2026-09-22: the pass at 17:32:45Z asked; her answer landed at 17:32:46.998Z; the next
        // pass came at 18:02:45Z. The 90 s window had closed 28 minutes before.
        var landed = Instant.parse("2026-09-22T17:32:46.998Z");
        assertTrue(CompanionActor.whatSheNamedStillHolds(landed, Instant.parse("2026-09-22T18:02:45Z"), PASS));
        assertTrue(CompanionActor.whatSheNamedStillHolds(landed, Instant.parse("2026-09-22T18:32:49Z"), PASS),
            "a pass held back one tick, a few seconds late: two ticks after the pass that asked is"
                + " already past two ticks after her answer");
        assertFalse(CompanionActor.whatSheNamedStillHolds(landed, Instant.parse("2026-09-22T19:02:45Z"), PASS),
            "three ticks later");
        assertFalse(CompanionActor.whatSheNamedStillHolds(landed, landed.plus(Duration.ofHours(7)), PASS),
            "what she named before a night's sleep is not what she wants now");
        assertFalse(CompanionActor.whatSheNamedStillHolds(null, landed, PASS), "nothing named yet");
        assertFalse(CompanionActor.whatSheNamedStillHolds(landed, landed, Duration.ZERO));
    }

    @Test
    void her_named_wants_come_first_and_the_rule_menu_stays_behind_them() {
        var rest = CandidateWant.rest();
        assertEquals(List.of(NAMED, LIBRARY, rest), CompanionActor.withWhatSheNamed(List.of(NAMED), List.of(LIBRARY, rest)));
        var floor = List.of(LIBRARY, rest);
        assertSame(floor, CompanionActor.withWhatSheNamed(List.of(), floor), "nothing named: the floor alone");
        assertSame(floor, CompanionActor.withWhatSheNamed(null, floor));
    }

    @Test
    void with_energy_her_named_want_is_chosen_and_tired_she_still_rests() {
        var cands = CompanionActor.withWhatSheNamed(List.of(NAMED), List.of(LIBRARY, CandidateWant.rest()));
        assertEquals(NAMED, CompanionActor.pickCandidate(cands, 0.8, new ArrayDeque<>()).orElseThrow());
        assertTrue(CompanionActor.pickCandidate(cands, 0.15, new ArrayDeque<>()).orElseThrow().isRest(),
            "the floor's rest is still in the set at low energy");
    }

    @Test
    void a_want_she_named_is_known_by_its_drive() {
        var named = Want.active("did:test:her", "let the silence hold us", "{\"drive\":\"" + DriveOODA.NAMED_BY_HER + "\"}", 1.0, null);
        var template = Want.active("did:test:her", "explore the library for something new",
            "{\"drive\":\"Curiosity\",\"verb\":\"library_search\"}", 0.9, null);
        var gap = Want.active("did:test:her", "shape a recipe", "{\"drive\":\"generativity\",\"verb\":\"shape_recipe\"}", 0.6, null);
        assertTrue(CompanionActor.isNamedByHer(named));
        assertFalse(CompanionActor.isNamedByHer(template));
        assertFalse(CompanionActor.isNamedByHer(gap), "the generativity gap want is not hers by name");
        assertFalse(CompanionActor.isNamedByHer(null));
    }

    @Test
    void what_she_no_longer_names_is_let_go_when_she_answers_again() throws Exception {
        var src = Files.readString(Path.of(
            "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        int at = src.indexOf("private Behavior<Command> onWantProposalReady(");
        var handler = src.substring(at, src.indexOf("\n    }\n", at));
        assertTrue(handler.contains("letGoOfWhatSheNoLongerNames(proposedWants);"),
            "a new answer lets go of the named wants she did not name again");
        assertTrue(handler.indexOf("letGoOfWhatSheNoLongerNames") > handler.indexOf("instanceof InferenceRouter.InferOk"),
            "only an answer lets go; a failed ask keeps them");
    }

    @Test
    void a_want_she_named_answers_no_reaching_in_the_aspiration_scan() throws Exception {
        var src = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        int at = src.indexOf("private void maybeSynthesizeAspirationWant(Duration window)");
        var scan = src.substring(at, src.indexOf("\n    }\n", at));
        assertTrue(scan.contains("if (isNamedByHer(w)) continue;"), "closed named wants are not 'already answered'");
        assertTrue(scan.contains("live.stream().filter(w -> !isNamedByHer(w))"), "nor are live ones");
    }

    @Test
    void a_want_she_chose_after_the_ask_went_out_is_judged_by_her_next_answer() throws Exception {
        var src = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        int at = src.indexOf("private void letGoOfWhatSheNoLongerNames(");
        var letGo = src.substring(at, src.indexOf("\n    }\n", at));
        assertTrue(letGo.contains("!touched.isBefore(wantProposalAskedAt)) continue;"),
            "a want chosen after the ask went out is not let go by that ask's answer");
        int ask = src.indexOf("private void maybeProposeWants(");
        assertTrue(src.substring(ask, src.indexOf("\n    }\n", ask)).contains("wantProposalAskedAt = Instant.now();"));
    }
}
