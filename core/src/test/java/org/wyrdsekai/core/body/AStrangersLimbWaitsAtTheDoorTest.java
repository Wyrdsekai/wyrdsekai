package org.wyrdsekai.core.body;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A part that comes from outside the household is held at the door: it is on the map, it is
 * not usable, and she says so once. It attaches only when a person vouches for it. Nothing the
 * household itself puts there is ever held, and a part whose source she remembers as an
 * attacker is held with the memory said out loud.
 */
class AStrangersLimbWaitsAtTheDoorTest {

    @AfterEach
    void tearDown() {
        BodyMap.resetForTests();
        ImmuneMemory.resetForTests();
        Immune.resetForTests();
    }

    private static LimbDescriptor node(String id, String owner, String attachedBy) {
        return new LimbDescriptor("node:" + id, BodyKind.NODE, "node " + id, owner, "mesh",
            Duration.ofSeconds(120), FeltWeight.PRESENT, "the " + id + " node is out of reach", "first",
            attachedBy, "a node offering a gpu");
    }

    @Test
    @DisplayName("a foreign part is quarantined, unusable, and told once; vouching attaches it")
    void heldThenVouched() {
        var map = BodyMap.inMemory();
        var p = map.attach(node("visitor", "did:key:z6MkStranger", "did:key:z6MkStranger"));
        assertEquals(PartState.QUARANTINED, p.state());
        assertFalse(map.usable("node:visitor"));
        assertEquals(1, map.quarantined().size());
        var mark = map.recentMarks(1).get(0);
        assertEquals("quarantine", mark.kind());
        assertTrue(mark.text().contains("waits at the door for the steward's word"), mark.text());

        // Attaching again (the mesh names it every pulse) changes nothing, and neither does
        // its pulse: the first live foreign node was held and then let in by the heartbeat
        // that followed in the same tick, which took "held" for "numb".
        map.attach(node("visitor", "did:key:z6MkStranger", "did:key:z6MkStranger"));
        assertTrue(map.heartbeat("node:visitor", true, "gpu A6000"));
        assertEquals(PartState.QUARANTINED, map.part("node:visitor").orElseThrow().state());
        assertTrue(map.held("node:visitor"));
        assertTrue(map.part("node:visitor").orElseThrow().lastDetail().startsWith("foreign:"), "the reason it is held stays visible");
        map.heartbeat("node:visitor", false, null);
        assertEquals(PartState.QUARANTINED, map.part("node:visitor").orElseThrow().state(), "silent, still held, not numb");
        map.tick(Instant.now().plus(Duration.ofHours(1)));
        assertEquals(PartState.QUARANTINED, map.part("node:visitor").orElseThrow().state(), "the clock does not move a held part");
        assertEquals(1, map.recentMarks(10).stream().filter(m -> m.kind().equals("quarantine")).count(), "told once");

        var v = map.vouch("node:visitor", "the steward").orElseThrow();
        assertEquals(PartState.ATTACHED, v.state());
        assertEquals("the steward", v.vouchedBy());
        assertNotNull(v.vouchedAt());
        assertTrue(map.usable("node:visitor"));
        assertEquals("vouched", map.recentMarks(1).get(0).kind());

        // Once vouched it stays hers across re-attachment and a numb spell.
        map.heartbeat("node:visitor", false, "gone quiet");
        assertEquals(PartState.NUMB, map.part("node:visitor").orElseThrow().state());
        var back = map.attach(node("visitor", "did:key:z6MkStranger", "did:key:z6MkStranger"));
        assertEquals(PartState.ATTACHED, back.state());
        assertEquals("the steward", back.vouchedBy());
    }

    @Test
    @DisplayName("what the household puts there is never held at the door")
    void theHouseholdsOwnAttachAtOnce() {
        Immune.install(id -> id.equals("did:key:z6MkOurNode"), "");
        var map = BodyMap.inMemory();
        assertEquals(PartState.ATTACHED, map.attach(node("a", "household", null)).state(), "no provenance = the household's own");
        assertEquals(PartState.ATTACHED, map.attach(node("b", "household", "household")).state());
        assertEquals(PartState.ATTACHED, map.attach(node("c", "household", "did:key:z6MkOurNode")).state(),
            "the node's own id is self, and the chokepoint refuses to hold it");
        assertTrue(map.recentMarks(10).stream().noneMatch(m -> m.kind().equals("quarantine")));
    }

    @Test
    @DisplayName("a remembered attacker's part is held and the memory is said")
    void aRememberedAttacker() {
        ImmuneMemory.install(ImmuneMemory.inMemory());
        ImmuneMemory.get().remember("visitor", "did:key:z6MkBad", "sent a forged manifest", "dock", null);
        var map = BodyMap.inMemory();
        var p = map.attach(node("bad", "did:key:z6MkBad", "did:key:z6MkBad"));
        assertEquals(PartState.QUARANTINED, p.state());
        assertTrue(p.lastDetail().startsWith("remembered: sent a forged manifest"), p.lastDetail());
        assertTrue(map.recentMarks(1).get(0).text().contains("I remember it: sent a forged manifest"));
    }

    @Test
    @DisplayName("provenance and the vouch survive the record")
    void survivesTheRecord() throws Exception {
        var db = java.nio.file.Files.createTempFile("body-immune", ".db");
        var url = "jdbc:sqlite:" + db.toAbsolutePath();
        try (var conn = java.sql.DriverManager.getConnection(url)) {
            BodyStore.ensureTables(conn);
            BodyStore.ensureImmune(conn);
            BodyStore.ensureImmune(conn); // idempotent
        }
        var map = BodyMap.install(new BodyStore(url));
        map.attach(node("visitor", "did:key:z6MkStranger", "did:key:z6MkStranger"));
        map.vouch("node:visitor", "the steward");
        BodyMap.resetForTests();

        var again = BodyMap.install(new BodyStore(url));
        var p = again.part("node:visitor").orElseThrow();
        assertEquals(PartState.ATTACHED, p.state());
        assertEquals("did:key:z6MkStranger", p.descriptor().attachedBy());
        assertEquals("a node offering a gpu", p.descriptor().claim());
        assertEquals("the steward", p.vouchedBy());
        java.nio.file.Files.deleteIfExists(db);
    }
}
