package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers — concurrent bunshin ceilings
 * elastic allocation, single-primary invariant, priority broadcasts.
 */
class BunshinSchedulerTest {

    private static final String DID = "did:wyrd:zA:wyrd";

    // ── single-primary invariant (§6.5) ─────────────────────────────────────

    @Test
    void registering_same_primary_twice_throws() {
        var s = new BunshinScheduler();
        s.registerPrimary(DID);
        assertThrows(IllegalStateException.class, () -> s.registerPrimary(DID));
    }

    @Test
    void unregister_then_reregister_allowed() {
        var s = new BunshinScheduler();
        s.registerPrimary(DID);
        s.unregisterPrimary(DID);
        s.registerPrimary(DID);  // no throw
    }

    @Test
    void acquire_without_registration_refused() {
        var s = new BunshinScheduler();
        var slot = s.acquireSlot("did:wyrd:unreg", BunshinScheduler.ElasticProbe.ALWAYS);
        assertTrue(slot instanceof BunshinScheduler.Slot.Refused r
            && r.reason().contains("no primary"));
    }

    // ── basic allocation ────────────────────────────────────────────────────

    @Test
    void grants_up_to_maxConcurrent() {
        var s = new BunshinScheduler(2, 3, 5);
        s.registerPrimary(DID);
        var a = s.acquireSlot(DID, BunshinScheduler.ElasticProbe.NEVER);
        var b = s.acquireSlot(DID, BunshinScheduler.ElasticProbe.NEVER);
        assertTrue(a instanceof BunshinScheduler.Slot.Granted g && !g.elastic());
        assertTrue(b instanceof BunshinScheduler.Slot.Granted g && !g.elastic());
        assertEquals(2, s.activeCount(DID));
    }

    @Test
    void refuses_beyond_maxConcurrent_when_elastic_check_fails() {
        var s = new BunshinScheduler(2, 3, 5);
        s.registerPrimary(DID);
        s.acquireSlot(DID, BunshinScheduler.ElasticProbe.NEVER);
        s.acquireSlot(DID, BunshinScheduler.ElasticProbe.NEVER);
        var third = s.acquireSlot(DID, BunshinScheduler.ElasticProbe.NEVER);
        assertTrue(third instanceof BunshinScheduler.Slot.Refused r
            && r.reason().contains("elastic check failed"));
    }

    // ── elastic allocation (§5.1) ───────────────────────────────────────────

    @Test
    void grants_elastic_when_probe_allows_and_under_ceiling() {
        var s = new BunshinScheduler(2, 3, 5);
        s.registerPrimary(DID);
        s.acquireSlot(DID, BunshinScheduler.ElasticProbe.NEVER);
        s.acquireSlot(DID, BunshinScheduler.ElasticProbe.NEVER);
        var elastic = s.acquireSlot(DID, BunshinScheduler.ElasticProbe.ALWAYS);
        assertTrue(elastic instanceof BunshinScheduler.Slot.Granted g && g.elastic());
        assertEquals(3, s.activeCount(DID));
        assertEquals(1, s.elasticCount(DID));
    }

    @Test
    void refuses_beyond_elastic_ceiling_even_if_probe_allows() {
        var s = new BunshinScheduler(2, 3, 5);
        s.registerPrimary(DID);
        s.acquireSlot(DID, BunshinScheduler.ElasticProbe.ALWAYS);
        s.acquireSlot(DID, BunshinScheduler.ElasticProbe.ALWAYS);
        s.acquireSlot(DID, BunshinScheduler.ElasticProbe.ALWAYS);
        var fourth = s.acquireSlot(DID, BunshinScheduler.ElasticProbe.ALWAYS);
        assertTrue(fourth instanceof BunshinScheduler.Slot.Refused r
            && r.reason().contains("user approval required"));
    }

    @Test
    void refuses_beyond_absolute_ceiling_unconditionally() {
        // Force a situation at absolute ceiling by constructing a scheduler
        // where elastic == absolute, then filling it.
        var s = new BunshinScheduler(2, 3, 3);
        s.registerPrimary(DID);
        for (int i = 0; i < 3; i++) {
            s.acquireSlot(DID, BunshinScheduler.ElasticProbe.ALWAYS);
        }
        var refused = s.acquireSlot(DID, BunshinScheduler.ElasticProbe.ALWAYS);
        assertTrue(refused instanceof BunshinScheduler.Slot.Refused r
            && r.reason().contains("absolute ceiling"));
    }

    // ── release ─────────────────────────────────────────────────────────────

    @Test
    void release_frees_slot_and_allows_reacquisition() {
        var s = new BunshinScheduler(2, 3, 5);
        s.registerPrimary(DID);
        var first = (BunshinScheduler.Slot.Granted) s.acquireSlot(DID,
            BunshinScheduler.ElasticProbe.NEVER);
        s.acquireSlot(DID, BunshinScheduler.ElasticProbe.NEVER);
        assertEquals(2, s.activeCount(DID));

        assertTrue(s.releaseSlot(DID, first.slotId()));
        assertEquals(1, s.activeCount(DID));

        var another = s.acquireSlot(DID, BunshinScheduler.ElasticProbe.NEVER);
        assertTrue(another instanceof BunshinScheduler.Slot.Granted);
    }

    @Test
    void release_of_unknown_slot_returns_false() {
        var s = new BunshinScheduler();
        s.registerPrimary(DID);
        assertFalse(s.releaseSlot(DID, "not-a-real-slot"));
    }

    // ── priority broadcasts (§6.3) ──────────────────────────────────────────

    @Test
    void bunshin_yields_when_primary_active() {
        var s = new BunshinScheduler();
        s.registerPrimary(DID);
        assertFalse(s.shouldBunshinYield(DID));
        s.primaryActive(DID);
        assertTrue(s.shouldBunshinYield(DID));
        s.primaryQuiescent(DID);
        assertFalse(s.shouldBunshinYield(DID));
    }

    // ── ceilings normalization ──────────────────────────────────────────────

    @Test
    void ceilings_normalize_to_monotone_order() {
        // elastic < max → elastic should be bumped to max
        var s = new BunshinScheduler(5, 2, 3);
        assertEquals(5, s.maxConcurrent());
        assertEquals(5, s.elasticCeiling());
        assertEquals(5, s.absoluteCeiling());
    }
}
