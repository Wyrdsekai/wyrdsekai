package org.wyrdsekai.core.body;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Nearly full" is what survived a collection of the old generation. The old generation's
 * collection usage under the default collector is its fill line between mixed collections,
 * garbage included: the household node read 92% with 180 MB alive and the heap reflex paused her
 * turns four times in a row while the steward was in the room (2026-10-22).
 */
class TheHeapIsWhatSurvivedACollectionTest {

    private static final long TWO_GB = 2L << 30;

    @Test
    @DisplayName("only a collector of the old generation says how much is alive; the young ones say nothing")
    void onlyTheOldCollectorCounts() {
        var young = new LinkedHashMap<String, Long>();
        young.put("G1 Young Generation", 1_900L << 20);
        assertThat(HostSense.liveAfterOldCollectionPct(TWO_GB, young)).isEqualTo(-1);

        var both = new LinkedHashMap<String, Long>();
        both.put("G1 Young Generation", 1_900L << 20);
        both.put("G1 Old Generation", 180L << 20);
        assertThat(HostSense.liveAfterOldCollectionPct(TWO_GB, both)).isCloseTo(8.8, org.assertj.core.data.Offset.offset(0.1));

        var full = Map.of("G1 Old Generation", 1_950L << 20);
        assertThat(HostSense.liveAfterOldCollectionPct(TWO_GB, full)).isCloseTo(95.2, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    @DisplayName("the old generation's collectors by the names the runtimes give them")
    void theCollectorsNames() {
        assertThat(HostSense.collectsTheOldGeneration("G1 Old Generation")).isTrue();
        assertThat(HostSense.collectsTheOldGeneration("PS MarkSweep")).isTrue();
        assertThat(HostSense.collectsTheOldGeneration("MarkSweepCompact")).isTrue();
        assertThat(HostSense.collectsTheOldGeneration("ZGC Major Cycles")).isTrue();
        assertThat(HostSense.collectsTheOldGeneration("G1 Young Generation")).isFalse();
        assertThat(HostSense.collectsTheOldGeneration("G1 Concurrent GC")).isFalse();
        assertThat(HostSense.collectsTheOldGeneration("PS Scavenge")).isFalse();
        assertThat(HostSense.collectsTheOldGeneration("ZGC Minor Cycles")).isFalse();
    }

    @Test
    @DisplayName("a collection older than five minutes says nothing about now")
    void anOldCollectionIsForgotten() {
        long uptime = 90 * 60 * 1000L;
        assertThat(HostSense.recentEnough(uptime, uptime - 4 * 60 * 1000L, HostSense.LIVE_SET_STALE_AFTER_MS)).isTrue();
        assertThat(HostSense.recentEnough(uptime, uptime - 6 * 60 * 1000L, HostSense.LIVE_SET_STALE_AFTER_MS)).isFalse();
        assertThat(HostSense.recentEnough(uptime, -1, HostSense.LIVE_SET_STALE_AFTER_MS)).isFalse();
        assertThat(HostSense.LIVE_SET_STALE_AFTER_MS).isEqualTo(300_000L);
    }

    @Test
    @DisplayName("nothing known: -1, and the gauges say so instead of a number")
    void nothingKnown() throws Exception {
        assertThat(HostSense.liveAfterOldCollectionPct(TWO_GB, Map.of())).isEqualTo(-1);
        assertThat(HostSense.liveAfterOldCollectionPct(0, Map.of("G1 Old Generation", 1L))).isEqualTo(-1);
        assertThat(HostSense.liveAfterOldCollectionPct(TWO_GB, null)).isEqualTo(-1);
        var unknown = new HostSense.Reading(-1, -1, 60.0, 1.0, 8, 50L << 30, -1);
        assertThat(unknown.gauges()).contains("heap: no recent full collection").doesNotContain("heap -1");
        assertThat(unknown.memoryPressure()).isFalse();
    }

    @Test
    @DisplayName("her own time and the background work honour the hold; a person's turn does not read it")
    void whoHonoursTheHold() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var actor = Files.readString(Files.exists(from) ? from : Path.of(rel));
        assertThat(actor).contains("if (InferenceRouter.isPaused() || InferenceRouter.isOwnTimeHeld()) {");
        assertThat(actor).contains("if (InferenceRouter.isOwnTimeHeld()) return;   // background work waits with her own time");
        assertThat(actor).contains("if (inferenceRouter == null || InferenceRouter.isPaused() || InferenceRouter.isOwnTimeHeld()) return false;");
        var router = "core/src/main/java/org/wyrdsekai/core/inference/InferenceRouter.java";
        var r = Files.readString(Files.exists(Path.of("..", router)) ? Path.of("..", router) : Path.of(router));
        assertThat(r).as("the hold is not the pause: a request on the wire is never refused for it")
            .doesNotContain("isOwnTimeHeld() ? pausedMessage()");
    }
}
