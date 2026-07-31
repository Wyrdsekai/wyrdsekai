package org.wyrdsekai.between;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * inbound dedup across legs.
 */
class RelaySeenSetTest {

    @Test
    void firstSightThenDuplicate() {
        var seen = new RelaySeenSet(1024);
        assertTrue(seen.firstSight("sig-A"), "first time → delivered");
        assertFalse(seen.firstSight("sig-A"), "same sig (arrived via 2nd leg) → dropped");
        assertTrue(seen.firstSight("sig-B"), "distinct message → delivered");
    }

    @Test
    void nullOrBlankNeverDeduped() {
        var seen = new RelaySeenSet(1024);
        assertTrue(seen.firstSight(null));
        assertTrue(seen.firstSight(null), "null sig is always first-sight (unsigned/legacy)");
        assertTrue(seen.firstSight(""));
        assertTrue(seen.firstSight("  "));
    }

    @Test
    void evictsOldestPastCapacity() {
        var seen = new RelaySeenSet(64); // floored to 64
        for (int i = 0; i < 64; i++) seen.firstSight("s" + i);
        // Fill beyond capacity; the eldest ("s0") should have been evicted, so it
        // reads as first-sight again, while a recent one is still a duplicate.
        for (int i = 64; i < 200; i++) seen.firstSight("s" + i);
        assertTrue(seen.firstSight("s0"), "evicted eldest → first-sight again");
        assertFalse(seen.firstSight("s199"), "recent sig still remembered");
    }
}
