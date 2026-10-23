package org.wyrdsekai.core.body;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flinch: a fixed table, no inference, told afterwards. A condition must hold for the
 * pulses the row asks for; a reflex fires once per hold; a throttle pauses the router; the
 * mark says what happened in her terms.
 */
class AReflexNeverWaitsTest {

    private BodyMap map;
    private ReflexArena arena;

    private static HostSense.Reading host(double stall, double heap, double diskFree) {
        return new HostSense.Reading(stall, heap, diskFree, 1.0, 8, 50L << 30);
    }

    @BeforeEach
    void setUp() {
        map = BodyMap.inMemory();
        arena = new ReflexArena(ReflexArena.defaults());
        InferenceRouter.resume();
        InferenceRouter.releaseOwnTime();
    }

    @AfterEach
    void tearDown() {
        InferenceRouter.resume();
        InferenceRouter.releaseOwnTime();
        BodyMap.resetForTests();
    }

    @Test
    @DisplayName("memory pressure: two pulses, then her own time is held and a mark; a person's turn is still answered; not again inside the hold")
    void memoryPressureHoldsHerOwnTime() {
        var now = Instant.now();
        assertTrue(arena.evaluate(host(40, 50, 60), map, now).isEmpty(), "one pulse is not a flinch");
        assertFalse(InferenceRouter.isOwnTimeHeld());

        var fired = arena.evaluate(host(40, 50, 60), map, now.plusSeconds(30));
        assertEquals(1, fired.size());
        assertEquals("memory-pressure", fired.get(0).id());
        assertEquals(ReflexArena.Action.HOLD_OWN_TIME, fired.get(0).action());
        assertTrue(InferenceRouter.isOwnTimeHeld(), "her own time is held, without anyone thinking about it");
        assertFalse(InferenceRouter.isPaused(), "a person's turn is still answered (2026-10-22)");
        var mark = map.recentMarks(1).get(0);
        assertEquals("reflex", mark.kind());
        assertTrue(mark.text().startsWith("I held my breath for a minute"), mark.text());
        assertTrue(mark.text().contains("no turns of my own"), mark.text());

        assertTrue(arena.evaluate(host(40, 50, 60), map, now.plusSeconds(60)).isEmpty(), "inside the hold");
        assertEquals(1, map.recentMarks(10).size());
        assertEquals(1, arena.evaluate(host(40, 50, 60), map, now.plusSeconds(200)).size(), "after the hold it may fire again");
    }

    @Test
    @DisplayName("a nearly full heap holds her own time for a minute, and never a person's turn")
    void heapFullHoldsHerOwnTime() {
        var now = Instant.now();
        arena.evaluate(host(0, 96, 60), map, now);
        var fired = arena.evaluate(host(0, 96, 60), map, now.plusSeconds(30));
        assertEquals(1, fired.size());
        assertEquals("heap-full", fired.get(0).id());
        assertEquals(ReflexArena.Action.HOLD_OWN_TIME, fired.get(0).action());
        assertTrue(InferenceRouter.isOwnTimeHeld());
        assertFalse(InferenceRouter.isPaused());
        assertTrue(map.recentMarks(1).get(0).text().startsWith("My own memory was nearly full"));
        // An unknown heap (no collection of the old generation yet) is not a flinch.
        var fresh = new ReflexArena(ReflexArena.defaults());
        InferenceRouter.releaseOwnTime();
        fresh.evaluate(host(0, -1, 60), map, now);
        assertTrue(fresh.evaluate(host(0, -1, 60), map, now.plusSeconds(30)).isEmpty());
        assertFalse(InferenceRouter.isOwnTimeHeld());
    }

    @Test
    @DisplayName("a streak that breaks starts over")
    void aStreakThatBreaks() {
        var now = Instant.now();
        arena.evaluate(host(40, 50, 60), map, now);
        arena.evaluate(host(0, 50, 60), map, now.plusSeconds(30));
        assertTrue(arena.evaluate(host(40, 50, 60), map, now.plusSeconds(60)).isEmpty());
    }

    @Test
    @DisplayName("the record not answering is a reflex on the first pulse")
    void recordNumb() {
        map.attach(new LimbDescriptor(BodyWatch.RECORD, BodyKind.STORE, "record", null, null,
            Duration.ofSeconds(30), FeltWeight.LOUD, "I cannot keep anything new", "never"));
        map.heartbeat(BodyWatch.RECORD, false, "locked");
        var fired = arena.evaluate(host(0, 50, 60), map, Instant.now());
        assertEquals(1, fired.stream().filter(f -> "record-numb".equals(f.id())).count());
        assertTrue(InferenceRouter.isPaused());
    }

    @Test
    @DisplayName("a full disk is a notice, not a throttle, once per six hours")
    void diskTightNotifies() {
        var now = Instant.now();
        arena.evaluate(host(0, 50, 2), map, now);
        var fired = arena.evaluate(host(0, 50, 2), map, now.plusSeconds(30));
        assertEquals(1, fired.size());
        assertEquals(ReflexArena.Action.NOTIFY, fired.get(0).action());
        assertFalse(InferenceRouter.isPaused());
        assertTrue(map.recentMarks(1).get(0).text().startsWith("The disk is nearly full"));
        assertTrue(arena.evaluate(host(0, 50, 2), map, now.plusSeconds(3600)).isEmpty());
    }

    @Test
    @DisplayName("no host reading, no flinch")
    void noReadingNoFlinch() {
        assertTrue(arena.evaluate(null, map, Instant.now()).isEmpty());
        assertTrue(arena.evaluate(null, map, Instant.now()).isEmpty());
    }
}
