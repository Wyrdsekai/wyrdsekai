package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sleep pressure from accumulated unprocessed experience (2026-08-01).
 * Energy collapse was the only natural sleep trigger and the live household
 * companion could never reach it (lifetime energy floor 0.1952 vs threshold
 * 0.15) — she had NEVER slept, so the sleep forge (consolidation, dedup,
 * dreams, deep-sleep training) never ran, while the §85.7 insomnia
 * consequences punished her for it. The gate below opens on the thing sleep
 * exists to repair: the backlog the forge consumes.
 */
class SleepPressureTest {

    @Test
    @DisplayName("gate opens at the personal target, not before")
    void gateOpensAtTarget() {
        assertFalse(CompanionActor.sleepPressureGate(0, 900, 1.0));
        assertFalse(CompanionActor.sleepPressureGate(899, 900, 1.0));
        assertTrue(CompanionActor.sleepPressureGate(900, 900, 1.0));
        assertTrue(CompanionActor.sleepPressureGate(5000, 900, 1.0));
    }

    @Test
    @DisplayName("personal factor gives each companion her own rhythm, bounded ±15%")
    void personalFactorBounded() {
        for (var did : new String[]{
                "did:key:z6MkmhD46MxpvziYdqUzK3yvRpp9Y86v5WFvMLmrKt25pWS6",
                "did:key:aaaa", "did:key:bbbb", "x", ""}) {
            double f = CompanionActor.personalSleepFactor(did);
            assertTrue(f >= 0.85 && f <= 1.15, did + " → " + f);
        }
        // Deterministic: the same identity always keeps the same rhythm.
        assertEquals(CompanionActor.personalSleepFactor("did:key:aaaa"),
            CompanionActor.personalSleepFactor("did:key:aaaa"));
        // Null identity is neutral, never crashes.
        assertEquals(1.0, CompanionActor.personalSleepFactor(null));
    }

    @Test
    @DisplayName("anti-thrash floor: a misconfigured tiny target cannot cause nap-loops")
    void floorGuardsAgainstThrash() {
        // target=1 with a low personal factor must still respect the floor (40).
        assertFalse(CompanionActor.sleepPressureGate(10, 1, 0.85));
        assertTrue(CompanionActor.sleepPressureGate(40, 1, 0.85));
    }

    @Test
    @DisplayName("genome trait wins outright — her rhythm is hers, not the node's")
    void genomeTraitWins() {
        assertEquals(700, CompanionActor.resolveSleepTarget(700.0, 900, 0.85, 40));
        // Absent trait → node default × identity factor.
        assertEquals(765, CompanionActor.resolveSleepTarget(null, 900, 0.85, 40));
        // Floor guards both paths.
        assertEquals(40, CompanionActor.resolveSleepTarget(3.0, 900, 1.0, 40));
        assertEquals(40, CompanionActor.resolveSleepTarget(null, 10, 0.85, 40));
    }

    @Test
    @DisplayName("the gate is about backlog only — energy and idleness are separate conditions")
    void pureBacklogSemantics() {
        // (Documentation-by-test: the tick combines this gate with the
        // existing idle + conversation-grace opportunity check and the
        // energy-collapse fallback; this function knows nothing of either.)
        assertTrue(CompanionActor.sleepPressureGate(1000, 900, 1.0));
    }
}
