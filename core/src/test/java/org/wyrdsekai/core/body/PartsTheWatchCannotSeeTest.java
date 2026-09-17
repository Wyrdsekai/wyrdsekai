package org.wyrdsekai.core.body;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sources: the mesh's peer nodes, the relay door, the coding hand, the librarian's door are
 * parts the watch cannot see itself. A source names them each pulse; a part named for the
 * first time is attached, a part that stops being named ages out by the clock, and a source
 * that throws does not stop the pulse.
 */
class PartsTheWatchCannotSeeTest {

    private static final Duration EVERY = Duration.ofSeconds(30);

    @AfterEach
    void tearDown() {
        BodyMap.resetForTests();
    }

    private static LimbDescriptor node(String id) {
        return new LimbDescriptor("node:" + id, BodyKind.NODE, "household node " + id, "household", "mesh",
            Duration.ofSeconds(120), FeltWeight.PRESENT, "the " + id + " node is out of reach", "first");
    }

    @Test
    @DisplayName("a source attaches what it names and heartbeats it; a named-dead part goes numb at once")
    void aSourceSpeaksForItsParts() {
        var map = BodyMap.inMemory();
        var watch = new BodyWatch(map, null, null, EVERY);
        var peerUp = new AtomicBoolean(true);
        watch.source(() -> List.of(new BodyWatch.Beat(node("gpu-peer"), peerUp.get(), "gpu A6000")));

        watch.pulse();
        var part = map.part("node:gpu-peer").orElseThrow();
        assertEquals(PartState.ATTACHED, part.state());
        assertEquals("gpu A6000", part.lastDetail());

        peerUp.set(false);
        watch.pulse();
        assertEquals(PartState.NUMB, map.part("node:gpu-peer").orElseThrow().state());
        assertTrue(map.recentMarks(1).get(0).text().contains("household node gpu-peer went quiet"));
    }

    @Test
    @DisplayName("a part the source stops naming ages out by the clock, not by the source")
    void unnamedPartsAgeOut() {
        var map = BodyMap.inMemory();
        var watch = new BodyWatch(map, null, null, EVERY);
        var named = new AtomicReference<>(List.of(new BodyWatch.Beat(node("pi"), true, null)));
        watch.source(named::get);
        watch.pulse();
        named.set(List.of());
        watch.pulse();
        assertEquals(PartState.ATTACHED, map.part("node:pi").orElseThrow().state(), "silence is not death");
        map.tick(Instant.now().plus(Duration.ofMinutes(5)));
        assertEquals(PartState.NUMB, map.part("node:pi").orElseThrow().state());
    }

    @Test
    @DisplayName("a probe runs on its own cadence and a broken source does not stop the pulse")
    void cadenceAndFaults() {
        var map = BodyMap.inMemory();
        var watch = new BodyWatch(map, null, null, EVERY);
        var probes = new java.util.concurrent.atomic.AtomicInteger();
        watch.source(() -> { throw new IllegalStateException("registry not ready"); });
        watch.probe(new LimbDescriptor("hand:codezaiku", BodyKind.HAND, "coding hand", "household", "codezaiku",
            EVERY.multipliedBy(4), FeltWeight.PRESENT, "I cannot build", "first"),
            () -> { probes.incrementAndGet(); return true; }, null, 4);
        for (int i = 0; i < 8; i++) watch.pulse();
        assertEquals(2, probes.get(), "every fourth pulse");
        assertEquals(PartState.ATTACHED, map.part("hand:codezaiku").orElseThrow().state());
    }
}
