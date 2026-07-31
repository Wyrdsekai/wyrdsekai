package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the server-side {@link VectorClock} to the clients' VectorClock.ts /
 * .kt semantics — if these drift, phone↔server Study sync silently diverges.
 */
class VectorClockTest {

    @Test
    void compare_matches_client_relation_rules() {
        // equal (incl. missing-slot == 0)
        assertEquals(VectorClock.Relation.EQUAL,
            VectorClock.compare(Map.of("a", 1), Map.of("a", 1)));
        assertEquals(VectorClock.Relation.EQUAL,
            VectorClock.compare(Map.of(), Map.of()));
        assertEquals(VectorClock.Relation.EQUAL,
            VectorClock.compare(Map.of("a", 0), Map.of()));

        // dominates: every slot >=, at least one >
        assertEquals(VectorClock.Relation.DOMINATES,
            VectorClock.compare(Map.of("a", 2, "b", 1), Map.of("a", 1, "b", 1)));
        assertEquals(VectorClock.Relation.DOMINATES,
            VectorClock.compare(Map.of("a", 1), Map.of()));

        // dominated (mirror)
        assertEquals(VectorClock.Relation.DOMINATED,
            VectorClock.compare(Map.of("a", 1, "b", 1), Map.of("a", 2, "b", 1)));

        // concurrent: each has a strictly-greater slot
        assertEquals(VectorClock.Relation.CONCURRENT,
            VectorClock.compare(Map.of("a", 2, "b", 1), Map.of("a", 1, "b", 2)));
    }

    @Test
    void merge_takes_per_slot_max() {
        assertEquals(Map.of("a", 2, "b", 3, "c", 5),
            VectorClock.merge(Map.of("a", 2, "b", 1, "c", 5), Map.of("a", 1, "b", 3)));
    }

    @Test
    void tick_increments_only_that_device() {
        assertEquals(Map.of("a", 1), VectorClock.tick(Map.of(), "a"));
        assertEquals(Map.of("a", 2, "b", 1), VectorClock.tick(Map.of("a", 1, "b", 1), "a"));
    }
}
