package org.wyrdsekai.core.body;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One rule, one place. Every adaptive action against a limb passes the chokepoint, and the
 * chokepoint refuses to act on what is hers: a hand of hers reaching for her own home, a door
 * that opens onto this host, a part the household itself put there, a severance nobody asked
 * for. A refusal is not silent: it is written to the steward as a proposal.
 */
class TheImmuneSystemNeverActsOnWhatIsHersTest {

    @AfterEach
    void tearDown() {
        Immune.resetForTests();
        ImmuneMemory.resetForTests();
        BodyMap.resetForTests();
    }

    @Test
    @DisplayName("a cut inside her own home is refused; a cut elsewhere is allowed")
    void cut() {
        Immune.install(id -> false, "/var/lib/wyrdsekai");
        Immune.slugsForTests(uid -> uid == 62007 ? "mira" : null);
        var map = BodyMap.inMemory();

        var no = Immune.consider(Immune.Act.CUT, "uid:62007", "/var/lib/wyrdsekai/beings/mira/home/.ssh/config", "hooks");
        assertFalse(no.allowed());
        assertTrue(no.because().contains("her own home"), no.because());
        var mark = map.recentMarks(1).get(0);
        assertEquals("immune", mark.kind());
        assertEquals("steward", mark.audience());
        assertTrue(mark.text().contains("did not"), mark.text());

        assertTrue(Immune.consider(Immune.Act.CUT, "uid:62007", "/var/lib/wyrdsekai/beings/other/home/notes", "hooks").allowed(),
            "another being's home is not hers");
        assertTrue(Immune.consider(Immune.Act.CUT, "uid:62999", "/var/lib/wyrdsekai/beings/mira/home/notes", "hooks").allowed(),
            "a uid nobody knows gets no home");
        assertTrue(Immune.consider(Immune.Act.CUT, "uid:62007", "/etc/shadow", "hooks").allowed());
    }

    @Test
    @DisplayName("a door onto this host cannot be shut; a door onto the world can")
    void close() {
        var map = BodyMap.inMemory();
        assertFalse(Immune.consider(Immune.Act.CLOSE, "door:librarian", "127.0.0.1", "reflex").allowed());
        assertFalse(Immune.consider(Immune.Act.CLOSE, "door:relay", "203.0.113.5,localhost", "reflex").allowed(), "one self host taints the list");
        assertFalse(Immune.consider(Immune.Act.CLOSE, "door:x", "::1", "reflex").allowed());
        assertFalse(Immune.consider(Immune.Act.CLOSE, "door:x", "0.0.0.0", "reflex").allowed());
        assertTrue(Immune.consider(Immune.Act.CLOSE, "door:relay", "203.0.113.5", "reflex").allowed());
        assertEquals(4, map.recentMarks(10).stream().filter(m -> m.kind().equals("immune")).count(), "each refusal is a proposal");
        assertTrue(Immune.isSelfHost("localhost"));
        assertTrue(Immune.isSelfHost("169.254.1.1"));
        assertFalse(Immune.isSelfHost("203.0.113.5"));
    }

    @Test
    @DisplayName("what the household put there is not held; a stranger's is")
    void quarantine() {
        Immune.install(id -> id.equals("did:key:z6MkMira") || id.equals("did:key:z6MkOurNode"), "");
        assertFalse(Immune.consider(Immune.Act.QUARANTINE, "hand:x", "household", "the map").allowed());
        assertFalse(Immune.consider(Immune.Act.QUARANTINE, "hand:x", "system", "the map").allowed());
        assertFalse(Immune.consider(Immune.Act.QUARANTINE, "hand:x", null, "the map").allowed(), "no provenance is the household's own");
        assertFalse(Immune.consider(Immune.Act.QUARANTINE, "hand:x", "did:key:z6MkMira", "the map").allowed(), "a companion of the house");
        assertFalse(Immune.consider(Immune.Act.QUARANTINE, "node:y", "did:key:z6MkOurNode", "the map").allowed(), "this node");
        assertTrue(Immune.consider(Immune.Act.QUARANTINE, "node:z", "did:key:z6MkStranger", "the map").allowed());
    }

    @Test
    @DisplayName("only a person severs a part")
    void sever() {
        assertFalse(Immune.consider(Immune.Act.SEVER, "node:z", "", "reflex").allowed());
        assertFalse(Immune.consider(Immune.Act.SEVER, "node:z", "", "the map").allowed());
        assertTrue(Immune.consider(Immune.Act.SEVER, "node:z", "", "the steward").allowed());
        assertTrue(Immune.consider(Immune.Act.SEVER, "node:z", "", "steward").allowed());
    }

    @Test
    @DisplayName("an action against something foreign is remembered; with no memory installed nothing breaks")
    void remembered() {
        Immune.remember("door", "door:relay", "shut", "203.0.113.5"); // no memory installed: a no-op
        ImmuneMemory.install(ImmuneMemory.inMemory());
        Immune.remember("door", "door:relay", "shut", "203.0.113.5");
        Immune.remember("door", "", "blank subject is dropped", "x");
        assertEquals(1, ImmuneMemory.get().list().size());
        assertEquals("shut", ImmuneMemory.get().recall("door", "door:relay").orElseThrow().reason());
    }
}
