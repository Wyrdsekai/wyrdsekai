package org.wyrdsekai.core.parlor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ParlorManagerTest {

    private static final Instant T0 = Instant.parse("2026-04-19T12:00:00Z");

    private static class MutableClock implements Supplier<Instant> {
        Instant now = T0;
        public Instant get() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    // ── entry / occupancy ─────────────────────────────────────────────

    @Test void firstEntry_admitsAtFullMode() {
        var pm = new ParlorManager(n -> {}, () -> T0);
        var d = pm.entered("parlor-public", "alice");
        assertInstanceOf(ParlorManager.EntryDecision.Admitted.class, d);
        assertEquals(ParlorPresenceMode.FULL,
            ((ParlorManager.EntryDecision.Admitted) d).mode());
        assertEquals(1, pm.snapshot("parlor-public").orElseThrow().occupancy());
    }

    @Test void duplicateEntry_ignored() {
        // Re-entering the same parlor doesn't double-count — matches
        // room-actor idempotent enter semantics.
        var pm = new ParlorManager(n -> {}, () -> T0);
        pm.entered("parlor-public", "alice");
        pm.entered("parlor-public", "alice");
        assertEquals(1, pm.snapshot("parlor-public").orElseThrow().occupancy());
    }

    @Test void left_decrementsOccupancy() {
        var pm = new ParlorManager(n -> {}, () -> T0);
        pm.entered("parlor-public", "alice");
        pm.entered("parlor-public", "bob");
        pm.left("parlor-public", "alice");
        assertEquals(1, pm.snapshot("parlor-public").orElseThrow().occupancy());
    }

    @Test void left_unknownIsNoOp() {
        var pm = new ParlorManager(n -> {}, () -> T0);
        pm.entered("parlor-public", "alice");
        pm.left("parlor-public", "nobody");
        assertEquals(1, pm.snapshot("parlor-public").orElseThrow().occupancy());
    }

    // ── mode transitions ──────────────────────────────────────────────

    @Test void transitionFullToSampled_firesOnEntry() {
        // 11 occupants pushes into SAMPLED mode — immediate UP transition.
        var narrations = new ArrayList<ParlorManager.Narration>();
        var pm = new ParlorManager(narrations::add, () -> T0);
        for (int i = 0; i < 11; i++) {
            pm.entered("parlor-public", "user" + i);
        }
        assertEquals(ParlorPresenceMode.SAMPLED,
            pm.snapshot("parlor-public").orElseThrow().mode());
        assertFalse(narrations.isEmpty());
        var last = narrations.get(narrations.size() - 1);
        assertEquals(ParlorPresenceMode.FULL, last.from());
        assertEquals(ParlorPresenceMode.SAMPLED, last.to());
        assertTrue(last.text().toLowerCase().contains("busier")
            || last.text().toLowerCase().contains("voices"),
            "diegetic narration expected: " + last.text());
    }

    @Test void transitionDownRequiresDwell() {
        // Fill to SAMPLED, drop to 5 — immediately → should stay SAMPLED
        // because dwell (60s) hasn't elapsed.
        var clock = new MutableClock();
        var pm = new ParlorManager(n -> {}, clock);
        for (int i = 0; i < 11; i++) pm.entered("p", "u" + i);
        assertEquals(ParlorPresenceMode.SAMPLED, pm.snapshot("p").orElseThrow().mode());

        // Drop to 5 immediately.
        for (int i = 0; i < 6; i++) pm.left("p", "u" + i);

        // Dwell not yet elapsed.
        assertEquals(ParlorPresenceMode.SAMPLED, pm.snapshot("p").orElseThrow().mode(),
            "down transition must wait for dwell window");

        // Advance past dwell and trigger one more activity to re-evaluate.
        clock.advance(Duration.ofSeconds(61));
        pm.entered("p", "tick");
        pm.left("p", "tick");
        assertEquals(ParlorPresenceMode.FULL, pm.snapshot("p").orElseThrow().mode());
    }

    // ── DoS cap ───────────────────────────────────────────────────────

    @Test void atCapacity_newArrivalQueued() {
        var pm = new ParlorManager(n -> {}, () -> T0);
        // Fill to MAX_OCCUPANTS.
        for (int i = 0; i < ParlorPresenceMode.MAX_OCCUPANTS; i++) {
            pm.entered("p", "u" + i);
        }
        // Next arrival triggers QueuedAtCap.
        var d = pm.entered("p", "overflow");
        assertInstanceOf(ParlorManager.EntryDecision.QueuedAtCap.class, d);
        // Occupancy didn't tick past the cap.
        assertEquals(ParlorPresenceMode.MAX_OCCUPANTS,
            pm.snapshot("p").orElseThrow().occupancy());
    }

    @Test void cappedArrivalDoesNotLeakInOccupantSet() {
        var pm = new ParlorManager(n -> {}, () -> T0);
        for (int i = 0; i < ParlorPresenceMode.MAX_OCCUPANTS; i++) {
            pm.entered("p", "u" + i);
        }
        pm.entered("p", "overflow-user");
        var snap = pm.snapshot("p").orElseThrow();
        assertFalse(snap.occupants().contains("overflow-user"),
            "queued-at-cap arrivals must NOT be added to occupant set");
    }

    @Test void existingOccupantCanReEnterAtCap() {
        // Someone already in the parlor should be able to "re-enter" (e.g.
        // reconnect) even at cap — they're not a new overflow arrival.
        var pm = new ParlorManager(n -> {}, () -> T0);
        for (int i = 0; i < ParlorPresenceMode.MAX_OCCUPANTS; i++) {
            pm.entered("p", "u" + i);
        }
        var d = pm.entered("p", "u0");  // already in occupant set
        assertInstanceOf(ParlorManager.EntryDecision.Admitted.class, d);
    }

    // ── multi-parlor isolation ────────────────────────────────────────

    @Test void perParlorStateIsIsolated() {
        var pm = new ParlorManager(n -> {}, () -> T0);
        for (int i = 0; i < 15; i++) pm.entered("public", "u" + i);
        for (int i = 0; i < 3; i++) pm.entered("family", "f" + i);
        assertEquals(ParlorPresenceMode.SAMPLED, pm.snapshot("public").orElseThrow().mode());
        assertEquals(ParlorPresenceMode.FULL, pm.snapshot("family").orElseThrow().mode());
    }

    @Test void trackedParlors_countsUnique() {
        var pm = new ParlorManager(n -> {}, () -> T0);
        pm.entered("a", "x");
        pm.entered("b", "x");
        pm.entered("a", "y");
        assertEquals(2, pm.trackedParlors());
    }

    // ── narration sink robustness ─────────────────────────────────────

    @Test void sinkThrowing_doesNotBreakTransitionCommit() {
        // A misbehaving narration sink must not prevent state mutation —
        // otherwise a downstream bug in RoomActor.emote would wedge the
        // Parlor's presence mode.
        var pm = new ParlorManager(n -> { throw new RuntimeException("boom"); }, () -> T0);
        for (int i = 0; i < 11; i++) pm.entered("p", "u" + i);
        assertEquals(ParlorPresenceMode.SAMPLED, pm.snapshot("p").orElseThrow().mode());
    }

    // ── snapshot semantics ────────────────────────────────────────────

    @Test void snapshot_returnsEmptyForUnknownRoom() {
        var pm = new ParlorManager(n -> {}, () -> T0);
        assertTrue(pm.snapshot("nonexistent").isEmpty());
    }

    @Test void snapshot_occupantsAreImmutable() {
        var pm = new ParlorManager(n -> {}, () -> T0);
        pm.entered("p", "alice");
        var snap = pm.snapshot("p").orElseThrow();
        assertThrows(UnsupportedOperationException.class,
            () -> snap.occupants().add("injected"));
    }

    // ── singleton ─────────────────────────────────────────────────────

    @Test void singleton_idempotentInit() {
        ParlorManager.resetForTests();
        try {
            var a = ParlorManager.getOrInit(n -> {});
            var b = ParlorManager.getOrInit(n -> {});
            assertSame(a, b);
        } finally {
            ParlorManager.resetForTests();
        }
    }

    @Test void singleton_getBeforeInitReturnsNull() {
        ParlorManager.resetForTests();
        assertNull(ParlorManager.get());
    }
}
