package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TellScopeGateTest {

    private EntityRegistry reg;

    @BeforeEach
    void setUp() {
        EntityRegistry.init();
        reg = EntityRegistry.get();
    }

    // Helper to register an entity + room in one call.
    private void place(String id, String name, String room) {
        reg.enter(id, name, "player", room);
    }

    // ── same-room path ────────────────────────────────────────────────

    @Test void sameRoom_allowsRegardlessOfZone() {
        place("alice", "alice", "kitchen");
        place("bob", "bob", "kitchen");
        var d = TellScopeGate.check("alice", "zoneA", "bob", "zoneB", reg, TellScopeGate.DENY_ALL);
        assertInstanceOf(TellScopeGate.Decision.AllowSameRoom.class, d);
    }

    @Test void differentRoom_sameZone_allowedByIntraZoneRule() {
        // Within a single household zone, tells are unrestricted — the
        // household is a single trust boundary (§6.3).
        place("alice", "alice", "kitchen");
        place("bob", "bob", "garage");
        var d = TellScopeGate.check("alice", "zoneA", "bob", "zoneA", reg, TellScopeGate.DENY_ALL);
        assertInstanceOf(TellScopeGate.Decision.AllowContract.class, d);
    }

    @Test void differentRoom_nullTargetZone_treatedAsIntraZone() {
        // When the caller doesn't know the target's zone, we assume same-zone
        // as sender — matches today's local-only tells that don't carry
        // zone info in the call. Avoids false rejections during migration.
        place("alice", "alice", "kitchen");
        place("bob", "bob", "garage");
        var d = TellScopeGate.check("alice", "zoneA", "bob", null, reg, TellScopeGate.DENY_ALL);
        assertInstanceOf(TellScopeGate.Decision.AllowContract.class, d);
    }

    @Test void differentRoom_blankTargetZone_treatedAsIntraZone() {
        place("alice", "alice", "kitchen");
        place("bob", "bob", "garage");
        var d = TellScopeGate.check("alice", "zoneA", "bob", "   ", reg, TellScopeGate.DENY_ALL);
        assertInstanceOf(TellScopeGate.Decision.AllowContract.class, d);
    }

    // ── cross-zone — contract path ────────────────────────────────────

    @Test void crossZone_withContract_allowsContract() {
        place("alice", "alice", "parlor");
        place("bob", "bob", "garage");
        TellScopeGate.ContractLookup contracts =
            (sz, tz, te) -> "zoneA".equals(sz) && "zoneB".equals(tz);
        var d = TellScopeGate.check("alice", "zoneA", "bob", "zoneB", reg, contracts);
        assertInstanceOf(TellScopeGate.Decision.AllowContract.class, d);
        assertEquals("zoneB", ((TellScopeGate.Decision.AllowContract) d).targetZoneId());
    }

    @Test void crossZone_withoutContract_denies() {
        place("alice", "alice", "parlor");
        place("bob", "bob", "garage");
        var d = TellScopeGate.check("alice", "zoneA", "bob", "zoneB", reg, TellScopeGate.DENY_ALL);
        assertInstanceOf(TellScopeGate.Decision.Deny.class, d);
        // Message must explain the rule so user understands how to fix it.
        var reason = ((TellScopeGate.Decision.Deny) d).reason();
        assertTrue(reason.contains("same-room") || reason.contains("contract"),
            "deny reason must explain the rule: " + reason);
        assertTrue(reason.contains("bob") || reason.contains("zoneB"),
            "deny reason should name the target or its zone: " + reason);
    }

    @Test void crossZone_contractLookupCalledWithCorrectArgs() {
        place("alice", "alice", "parlor");
        place("bob", "bob", "garage");
        final String[] captured = new String[3];
        TellScopeGate.ContractLookup spy = (sz, tz, te) -> {
            captured[0] = sz;
            captured[1] = tz;
            captured[2] = te;
            return false;
        };
        TellScopeGate.check("alice", "zoneA", "bob", "zoneB", reg, spy);
        assertEquals("zoneA", captured[0]);
        assertEquals("zoneB", captured[1]);
        assertEquals("bob", captured[2]);
    }

    // ── parlor-stranger scenario (the motivating case for this spec) ──

    @Test void parlorStranger_cannotTellHomeResidentInAnotherRoom() {
        // Classic spec §6.9 scenario: stranger in Parlor, resident Alice in
        // Kitchen (both in same zone but different rooms). Intra-zone rule
        // still allows — BUT the cross-household version blocks.
        //
        // Here we model a cross-household stranger: they're in OUR parlor,
        // but their zone is different.
        place("stranger", "stranger", "parlor-public");
        place("alice", "alice", "kitchen");

        // Stranger's zone != our zone. No contract.
        var d = TellScopeGate.check("stranger", "visitor-zone", "alice", "my-zone",
            reg, TellScopeGate.DENY_ALL);
        assertInstanceOf(TellScopeGate.Decision.Deny.class, d);
    }

    @Test void parlorStranger_canTellHostCompanionInSameRoom() {
        place("stranger", "stranger", "parlor-public");
        place("host", "host", "parlor-public");
        var d = TellScopeGate.check("stranger", "visitor-zone", "host", "my-zone",
            reg, TellScopeGate.DENY_ALL);
        assertInstanceOf(TellScopeGate.Decision.AllowSameRoom.class, d);
    }

    // ── self-tell ─────────────────────────────────────────────────────

    @Test void selfTell_alwaysAllowed() {
        place("alice", "alice", "kitchen");
        var d = TellScopeGate.check("alice", "zoneA", "alice", "zoneA", reg, TellScopeGate.DENY_ALL);
        assertInstanceOf(TellScopeGate.Decision.AllowSameRoom.class, d);
    }

    @Test void selfTell_allowedEvenIfNotTracked() {
        // Self-tell should short-circuit before registry lookup — it's a
        // note-to-self flow that must work under any state.
        var d = TellScopeGate.check("alice", "zoneA", "alice", null, reg, TellScopeGate.DENY_ALL);
        assertInstanceOf(TellScopeGate.Decision.AllowSameRoom.class, d);
    }

    // ── offline / unregistered entities ───────────────────────────────

    @Test void offlineSender_crossZoneWithContract_stillAllowed() {
        // Sender not in EntityRegistry (e.g. spoken via API without enter) —
        // same-room can't be established. Contract path still works.
        place("bob", "bob", "garage");
        TellScopeGate.ContractLookup yes = (sz, tz, te) -> true;
        var d = TellScopeGate.check("unregistered", "zoneA", "bob", "zoneB", reg, yes);
        assertInstanceOf(TellScopeGate.Decision.AllowContract.class, d);
    }

    @Test void offlineTarget_crossZoneWithContract_stillAllowed() {
        // Target offline (no room) — same-room false. Contract still allows.
        place("alice", "alice", "kitchen");
        TellScopeGate.ContractLookup yes = (sz, tz, te) -> true;
        var d = TellScopeGate.check("alice", "zoneA", "bob", "zoneB", reg, yes);
        assertInstanceOf(TellScopeGate.Decision.AllowContract.class, d);
    }

    @Test void bothOffline_crossZoneNoContract_denies() {
        var d = TellScopeGate.check("ghost-a", "zoneA", "ghost-b", "zoneB",
            reg, TellScopeGate.DENY_ALL);
        assertInstanceOf(TellScopeGate.Decision.Deny.class, d);
    }

    // ── allows() predicate ────────────────────────────────────────────

    @Test void allows_trueForSameRoom() {
        place("alice", "alice", "kitchen");
        place("bob", "bob", "kitchen");
        assertTrue(TellScopeGate.allows("alice", "z", "bob", "z", reg, TellScopeGate.DENY_ALL));
    }

    @Test void allows_trueForContract() {
        place("alice", "alice", "a");
        place("bob", "bob", "b");
        assertTrue(TellScopeGate.allows("alice", "zoneA", "bob", "zoneB",
            reg, (sz, tz, te) -> true));
    }

    @Test void allows_falseForDeny() {
        place("alice", "alice", "a");
        place("bob", "bob", "b");
        assertFalse(TellScopeGate.allows("alice", "zoneA", "bob", "zoneB",
            reg, TellScopeGate.DENY_ALL));
    }

    // ── null-safety ───────────────────────────────────────────────────

    @Test void nullSenderId_throws() {
        assertThrows(NullPointerException.class,
            () -> TellScopeGate.check(null, "z", "bob", "z", reg, TellScopeGate.DENY_ALL));
    }

    @Test void nullTargetId_throws() {
        assertThrows(NullPointerException.class,
            () -> TellScopeGate.check("alice", "z", null, "z", reg, TellScopeGate.DENY_ALL));
    }

    @Test void nullRegistry_throws() {
        assertThrows(NullPointerException.class,
            () -> TellScopeGate.check("a", "z", "b", "z", null, TellScopeGate.DENY_ALL));
    }

    @Test void nullContractLookup_throws() {
        assertThrows(NullPointerException.class,
            () -> TellScopeGate.check("a", "z", "b", "z", reg, null));
    }
}
