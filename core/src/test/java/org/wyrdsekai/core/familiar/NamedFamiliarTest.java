package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers — named-familiar persistence, bond charge
 * eligibility signals, and the FamilyLocker integration.
 */
class NamedFamiliarTest {

    private static final String PARENT = "did:wyrd:zA:wyrd";
    private static final String INTRUDER = "did:wyrd:zA:other";

    private FamilyLocker locker;

    @BeforeEach
    void setUp() {
        var bud = SoulBud.original(PARENT, "pk", "family-1",
            "locker://alpha", "home-server", "qwen2.5:7b");
        locker = FamilyLocker.create("family-1", "locker://alpha", bud);
    }

    // ── record invariants ───────────────────────────────────────────────────

    @Test
    void named_requires_non_blank_name() {
        assertThrows(IllegalArgumentException.class,
            () -> NamedFamiliar.named("", PARENT, "form-1", ""));
        assertThrows(IllegalArgumentException.class,
            () -> NamedFamiliar.named("!bad-start", PARENT, "form-1", ""));
    }

    @Test
    void initial_bond_charge_is_light() {
        var nf = NamedFamiliar.named("researcher", PARENT, "form-1", "Knows how to cite.");
        assertEquals(NamedFamiliar.INITIAL_BOND_CHARGE, nf.bondCharge(), 1e-9);
        assertEquals(0, nf.summonCount());
        assertEquals(0, nf.distinctTasks());
    }

    // ── summon + outcome updates ────────────────────────────────────────────

    @Test
    void with_summoned_increments_counters_and_tracks_distinct_tasks() {
        var nf = NamedFamiliar.named("gardener", PARENT, "form-1", "");
        var a = nf.withSummoned("water-tomatoes");
        var b = a.withSummoned("water-tomatoes");    // same task
        var c = b.withSummoned("prune-roses");        // new task

        assertEquals(3, c.summonCount());
        assertEquals(2, c.distinctTasks(), "duplicate task shouldn't bump distinct count");
        assertTrue(c.lastSummonedAt().isPresent());
    }

    @Test
    void with_outcome_done_bumps_bond_and_success() {
        var nf = NamedFamiliar.named("coder", PARENT, "form-1", "")
            .withOutcome(Familiar.Status.DONE, 5, "Fixed the crash.");
        assertEquals(1, nf.successCount());
        assertEquals(0, nf.failureCount());
        assertEquals(5, nf.totalTurns());
        assertTrue(nf.bondCharge() > NamedFamiliar.INITIAL_BOND_CHARGE);
        assertTrue(nf.selfContext().contains("Fixed the crash"));
    }

    @Test
    void with_outcome_stuck_drops_bond_and_logs_failure() {
        var before = NamedFamiliar.named("flaky", PARENT, "form-1", "")
            .nudgeBond(0.3f);  // start at 0.45
        var after = before.withOutcome(Familiar.Status.STUCK, 3, "Couldn't converge.");
        assertEquals(1, after.failureCount());
        assertTrue(after.bondCharge() < before.bondCharge());
    }

    @Test
    void bond_charge_is_clamped_to_unit_interval() {
        var nf = NamedFamiliar.named("clamp", PARENT, "form-1", "")
            .nudgeBond(5.0f);           // try to overshoot
        assertEquals(1.0f, nf.bondCharge(), 1e-9);
        var grim = nf.nudgeBond(-10.0f);
        assertEquals(0.0f, grim.bondCharge(), 1e-9);
    }

    // ── eligibility (§17.1) ─────────────────────────────────────────────────

    @Test
    void not_eligible_below_thresholds() {
        var nf = NamedFamiliar.named("fresh", PARENT, "form-1", "");
        assertFalse(nf.meetsPromotionEligibility());
    }

    @Test
    void eligible_once_all_thresholds_met() {
        var nf = NamedFamiliar.named("seasoned", PARENT, "form-1", "");
        for (int i = 0; i < 50; i++) nf = nf.withSummoned("task-" + i);
        // bump bond to 0.6+
        for (int i = 0; i < 10; i++) nf = nf.withOutcome(Familiar.Status.DONE, 1, null);
        assertTrue(nf.meetsPromotionEligibility(),
            "50 summons + 20 distinct tasks + bond ≥ 0.6 should qualify");
    }

    // ── self-context elision ────────────────────────────────────────────────

    @Test
    void self_context_is_bounded() {
        var nf = NamedFamiliar.named("chatter", PARENT, "form-1", "");
        for (int i = 0; i < 200; i++) {
            nf = nf.withOutcome(Familiar.Status.DONE, 1,
                "turn-" + i + " narrative note that runs on and on with filler text");
        }
        assertTrue(nf.selfContext().length() <= NamedFamiliar.MAX_SELF_CONTEXT_CHARS);
    }

    // ── FamilyLocker integration ────────────────────────────────────────────

    @Test
    void locker_stores_and_retrieves_named_familiars() {
        var nf = locker.nameFamiliar("researcher", PARENT, "form-1",
            "cites carefully", PARENT);
        assertEquals("researcher", nf.name());
        assertEquals(1, locker.namedFamiliarCount());

        var fetched = locker.namedFamiliar("researcher", PARENT).orElseThrow();
        assertEquals(nf, fetched);
    }

    @Test
    void name_collision_rejected() {
        locker.nameFamiliar("dup", PARENT, "form-1", "", PARENT);
        assertThrows(IllegalStateException.class,
            () -> locker.nameFamiliar("dup", PARENT, "form-2", "", PARENT));
    }

    @Test
    void parent_must_match_requester() {
        // Another bud authorized in the locker but isn't the parentDid
        var child = SoulBud.sprout(INTRUDER, PARENT, "pk2",
            "family-1", "locker://alpha", "home-server", "qwen2.5:7b");
        locker.authorize(child);
        assertThrows(SecurityException.class,
            () -> locker.nameFamiliar("usurp", PARENT, "form-1", "", INTRUDER));
    }

    @Test
    void record_summon_and_outcome_through_locker() {
        locker.nameFamiliar("helper", PARENT, "form-1", "", PARENT);
        locker.recordNamedSummon("helper", "research-topic", PARENT);
        var updated = locker.recordNamedOutcome("helper",
            Familiar.Status.DONE, 3, "Found three sources.", PARENT);

        assertEquals(1, updated.summonCount());
        assertEquals(1, updated.successCount());
        assertTrue(updated.bondCharge() > NamedFamiliar.INITIAL_BOND_CHARGE);
    }

    @Test
    void release_removes_named_familiar() {
        locker.nameFamiliar("tempname", PARENT, "form-1", "", PARENT);
        assertTrue(locker.releaseNamedFamiliar("tempname", PARENT));
        assertFalse(locker.releaseNamedFamiliar("tempname", PARENT));
        assertTrue(locker.namedFamiliar("tempname", PARENT).isEmpty());
    }

    @Test
    void lookup_rejects_unauthorized_did() {
        locker.nameFamiliar("guarded", PARENT, "form-1", "", PARENT);
        assertThrows(SecurityException.class,
            () -> locker.namedFamiliar("guarded", "did:wyrd:unauth"));
    }

    @Test
    void list_returns_sorted_by_name() {
        locker.nameFamiliar("zebra", PARENT, "f", "", PARENT);
        locker.nameFamiliar("alpha", PARENT, "f", "", PARENT);
        locker.nameFamiliar("mid", PARENT, "f", "", PARENT);
        var names = locker.listNamedFamiliars(PARENT).stream().map(NamedFamiliar::name).toList();
        assertEquals(List.of("alpha", "mid", "zebra"), names);
    }
}
