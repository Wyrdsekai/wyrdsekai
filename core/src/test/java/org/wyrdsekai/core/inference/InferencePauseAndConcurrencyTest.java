package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two things the steward asked for after a first real deployment (2026-09-13): a
 * concurrency default that fits the hardware, and a way to take the card for a while
 * without every companion timing out into "tangled threads".
 */
class InferencePauseAndConcurrencyTest {

    @AfterEach
    void tearDown() {
        InferenceRouter.resume();
    }

    @Test
    @DisplayName("one slot per backend by default; the env var still wins")
    void concurrencyFollowsTheBackends() {
        var a = new InferenceBackend.Cloud("a", new InferenceClient("http://x"), 5, List.of());
        var b = new InferenceBackend.Cloud("b", new InferenceClient("http://y"), 15, List.of());
        assertEquals(2, InferenceRouter.concurrencyFor(List.of(a, b)));
        assertEquals(1, InferenceRouter.concurrencyFor(List.of(a)));
        assertEquals(1, InferenceRouter.concurrencyFor(List.of()));
        assertEquals(1, InferenceRouter.concurrencyFor(null));
    }

    @Test
    @DisplayName("a pause is visible, has a reason, and ends — early on resume, or by itself")
    void pauseIsHonestAndEnds() throws Exception {
        assertFalse(InferenceRouter.isPaused());
        InferenceRouter.pause(Duration.ofHours(2), "the card is mine until dinner");
        assertTrue(InferenceRouter.isPaused());
        var snap = InferenceRouter.snapshot().asMap();
        assertEquals(true, snap.get("paused"));
        assertEquals("the card is mine until dinner", snap.get("pauseReason"));
        InferenceRouter.resume();
        assertFalse(InferenceRouter.isPaused());
        assertEquals(false, InferenceRouter.snapshot().asMap().get("paused"));

        InferenceRouter.pause(Duration.ofMillis(50), null);
        assertTrue(InferenceRouter.isPaused());
        Thread.sleep(80);
        assertFalse(InferenceRouter.isPaused(), "a pause expires on its own");
        assertTrue(String.valueOf(InferenceRouter.snapshot().asMap().get("pauseReason")).contains("household"),
            "a pause with no reason still has one");
    }

    @Test
    @DisplayName("the snapshot carries what the doctor prints")
    void snapshotShape() {
        var m = InferenceRouter.snapshot().asMap();
        for (var k : List.of("inFlight", "queued", "maxConcurrency", "paused", "p95LatencyMs", "avgLatencyMs", "latencySamples")) {
            assertTrue(m.containsKey(k), k);
        }
    }
}
