package org.wyrdsekai.core.body;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quiesce: at a junction everyone saves what she carries, the record is checkpointed, and a
 * mark says why, who, and how long. A flusher that never answers is not waited for past the
 * deadline. Resume leaves its own mark with how long the hold lasted.
 */
class HeldStillAtAJunctionTest {

    @AfterEach
    void tearDown() {
        Quiesce.resetForTests();
        BodyMap.resetForTests();
    }

    @Test
    @DisplayName("held still: flushed, checkpointed, marked; then let go")
    void heldStillAndLetGo(@TempDir Path dir) throws Exception {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        var map = BodyMap.install(new BodyStore(jdbc));
        Quiesce.install(jdbc, (reason, deadline) -> CompletableFuture.completedFuture(2));

        var report = Quiesce.quiesce("an update", "the steward", Duration.ofSeconds(5));
        assertEquals(2, report.flushed());
        assertTrue(report.checkpointed(), "the sqlite record is checkpointed");
        assertTrue(report.marked());
        var mark = map.recentMarks(1).get(0);
        assertEquals("paused", mark.kind());
        assertTrue(mark.text().startsWith("I was held still for an update, by the steward: 2 of us saved what we carried and the record was checkpointed"), mark.text());
        assertTrue(Quiesce.recentlyQuiesced(Duration.ofMinutes(1)));

        Thread.sleep(20);
        Quiesce.resumed("the steward");
        var resumed = map.recentMarks(1).get(0);
        assertEquals("resumed", resumed.kind());
        assertTrue(resumed.text().startsWith("I was let go after"), resumed.text());
        assertTrue(resumed.text().contains("by the steward"));
    }

    @Test
    @DisplayName("a flusher that never answers is not waited for past the deadline")
    void theDeadlineHolds() {
        BodyMap.inMemory();
        Quiesce.install(null, (reason, deadline) -> new CompletableFuture<>());
        var t0 = System.nanoTime();
        var report = Quiesce.quiesce("a stop", "the service", Duration.ofMillis(300));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(0, report.flushed());
        assertFalse(report.checkpointed(), "no record, no checkpoint");
        assertTrue(ms < 2000, "did not hang: " + ms + " ms");
        assertTrue(BodyMap.get().recentMarks(1).get(0).text().contains("0 of us saved"));
    }

    @Test
    @DisplayName("a second pass at the same junction can skip the mark")
    void secondPassSkipsTheMark() {
        var map = BodyMap.inMemory();
        Quiesce.install(null, (reason, deadline) -> CompletableFuture.completedFuture(1));
        Quiesce.quiesce("a stop", "the steward", Duration.ofSeconds(1), true);
        Quiesce.quiesce("a stop", "the service", Duration.ofSeconds(1), !Quiesce.recentlyQuiesced(Duration.ofMinutes(3)));
        assertEquals(1, map.recentMarks(10).size(), "told once");
    }

    @Test
    @DisplayName("no map, no flusher: it still returns, honestly")
    void nothingInstalled() {
        var report = Quiesce.quiesce(null, null, null);
        assertEquals("a pause", report.reason());
        assertEquals("the household", report.who());
        assertEquals(0, report.flushed());
        assertFalse(report.marked());
    }
}
