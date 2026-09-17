package org.wyrdsekai.core.body;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both doors of the map. Interrupts say a part is down now; the clock catches the part that
 * simply stopped talking. A quiet part is numb, dated from the last time it was heard, and it
 * is gone only when someone with authority says so. The map survives a restart.
 */
class ASilentPartGoesNumbByTheClockTest {

    @AfterEach
    void tearDown() {
        BodyMap.resetForTests();
    }

    @Test
    @DisplayName("a part that stops heartbeating goes numb after twice its contract, dated from the last beat")
    void numbByTheClock() {
        var map = BodyMap.inMemory();
        var every = Duration.ofSeconds(30);
        map.attach(new LimbDescriptor("sense:kitchen-camera", BodyKind.SENSE, "kitchen camera", "household",
            "/dev/video0", every, FeltWeight.QUIET, "I am blind in the kitchen", "first"));
        var heard = Instant.now();

        assertTrue(map.tick(heard.plus(Duration.ofSeconds(59))).isEmpty(), "inside the contract");
        var changed = map.tick(heard.plus(Duration.ofSeconds(61)));
        assertEquals(1, changed.size());
        var cam = map.part("sense:kitchen-camera").orElseThrow();
        assertEquals(PartState.NUMB, cam.state());
        assertTrue(Math.abs(Duration.between(heard, cam.numbSince()).toMillis()) < 1000,
            "quiet since the last beat heard, not since we noticed");
        assertEquals("The kitchen camera went quiet — I am blind in the kitchen.",
            map.recentMarks(1).get(0).text());
    }

    @Test
    @DisplayName("gone only by authority; the mark says who")
    void goneByAuthority() {
        var map = BodyMap.inMemory();
        map.attach(new LimbDescriptor("brain:peer", BodyKind.BRAIN, "borrowed brain (peer)", "household",
            "nats://peer", Duration.ofSeconds(30), FeltWeight.QUIET, null, "first"));
        map.heartbeat("brain:peer", false, null);
        map.tick(Instant.now().plus(Duration.ofDays(30)));
        assertEquals(PartState.NUMB, map.part("brain:peer").orElseThrow().state(), "the clock never declares gone");

        map.declareGone("brain:peer", "the steward");
        var p = map.part("brain:peer").orElseThrow();
        assertEquals(PartState.GONE, p.state());
        assertEquals("the steward", p.goneBy());
        assertTrue(map.numb().isEmpty());
        assertTrue(map.recentMarks(1).get(0).text().contains("the steward said so"), map.recentMarks(1).get(0).text());
        assertFalse(map.heartbeat("brain:peer", true, null), "a gone part's heartbeat is ignored");

        map.attach(p.descriptor());
        assertEquals(PartState.ATTACHED, map.part("brain:peer").orElseThrow().state(), "attaching again is coming back");
    }

    @Test
    @DisplayName("a heartbeat without a descriptor is not an attach")
    void heartbeatIsNotAttach() {
        var map = BodyMap.inMemory();
        assertFalse(map.heartbeat("brain:nobody", true, null));
        assertTrue(map.parts().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new LimbDescriptor("x", BodyKind.SENSE, "x", null, null,
            Duration.ZERO, FeltWeight.QUIET, null, null), "a part with no heartbeat contract cannot be attached");
    }

    @Test
    @DisplayName("a restart is not silence: parts loaded from the record get their contract again from now")
    void aRestartIsNotSilence(@TempDir Path dir) throws Exception {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        var map = BodyMap.install(new BodyStore(jdbc));
        map.attach(new LimbDescriptor("door:relay", BodyKind.DOOR, "relay door", "household", null,
            Duration.ofSeconds(30), FeltWeight.PRESENT, "nobody outside can reach me", "first"));
        BodyMap.resetForTests();
        // The next process starts twelve minutes later and pulses before the relay probe is wired.
        var again = BodyMap.install(new BodyStore(jdbc));
        var changed = again.tick(Instant.now().plus(Duration.ofSeconds(45)));
        assertTrue(changed.isEmpty(), "no false 'went quiet' at boot");
        assertEquals(PartState.ATTACHED, again.part("door:relay").orElseThrow().state());
        assertTrue(again.unreadFor("companion-mia").isEmpty(), "and nothing to tell her");
        assertEquals(1, again.tick(Instant.now().plus(Duration.ofSeconds(61))).size(), "true silence still counts, from boot");
    }

    @Test
    @DisplayName("the map and the marks survive a restart")
    void survivesARestart(@TempDir Path dir) {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        var map = BodyMap.install(new BodyStore(jdbc));
        map.attach(new LimbDescriptor("brain:drive", BodyKind.BRAIN, "thinking brain", "household",
            "http://127.0.0.1:8200", Duration.ofSeconds(30), FeltWeight.LOUD, "I think slower", "first"));
        map.heartbeat("brain:drive", false, "refused");
        map.mark("slept", "sleep", "companion-mia", "I slept.", null);
        map.markRead("companion-mia", map.unreadFor("companion-mia").stream().map(BodyMark::id).toList());
        map.used("brain:drive");

        BodyMap.resetForTests();
        var again = BodyMap.install(new BodyStore(jdbc));
        var drive = again.part("brain:drive").orElseThrow();
        assertEquals(PartState.NUMB, drive.state());
        assertEquals("refused", drive.lastDetail());
        assertEquals("thinking brain", drive.name());
        assertTrue(drive.lastUsed() != null, "last worked survives");
        assertTrue(again.unreadFor("companion-mia").isEmpty(), "what she read stays read");
        assertEquals(1, again.unreadFor("companion-ember").size(), "the body-wide numb mark waits for the other reader");
    }
}
