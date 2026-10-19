package org.wyrdsekai.between.layer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resource heartbeat publishes a snapshot every ten seconds. On a household's Linux box
 * each snapshot spawned a process that could not exist there (pmset, a Mac tool), and the
 * GPU question ran the vendor tools again each time it was asked: a four-line warning per
 * spawn after the host's Java was upgraded under the server, 43,000 journal lines a day for
 * two weeks (2026-10-19). Answers that do not change are kept.
 */
class TheHeartbeatDoesNotSpawnEveryTenSecondsTest {

    @Test
    @DisplayName("the battery is read once a minute, not once a snapshot")
    void batteryOncePerMinute() {
        var caps = new NodeCapabilities("test-node");
        NodeCapabilities.batteryReads.set(0);
        caps.snapshot();
        caps.snapshot();
        caps.snapshot();
        assertThat(NodeCapabilities.batteryReads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("an AMD GPU with memory set aside for it is a GPU; a small one or no card is not")
    void amdGpu(@org.junit.jupiter.api.io.TempDir java.nio.file.Path drm) throws Exception {
        assertThat(NodeCapabilities.amdGpuWithMemory(drm.resolve("missing"))).isFalse();
        var small = drm.resolve("card1/device");
        java.nio.file.Files.createDirectories(small);
        java.nio.file.Files.writeString(small.resolve("vendor"), "0x1002\n");
        java.nio.file.Files.writeString(small.resolve("mem_info_vram_total"), "1073741824\n");
        assertThat(NodeCapabilities.amdGpuWithMemory(drm)).as("1 GiB set aside is not a brain's GPU").isFalse();
        java.nio.file.Files.writeString(small.resolve("mem_info_vram_total"), "34359738368\n");
        assertThat(NodeCapabilities.amdGpuWithMemory(drm)).as("32 GiB set aside is").isTrue();
        var nvidia = drm.resolve("card0/device");
        java.nio.file.Files.createDirectories(nvidia);
        java.nio.file.Files.writeString(nvidia.resolve("vendor"), "0x10de\n");
        assertThat(NodeCapabilities.amdGpuWithMemory(drm)).isTrue();
    }

    @Test
    @DisplayName("the host is asked about its GPU once, then remembered")
    void gpuOnce() {
        NodeCapabilities.hostHasGpu();
        int after = NodeCapabilities.hostGpuProbes.get();
        NodeCapabilities.hostHasGpu();
        NodeCapabilities.hostHasGpu();
        assertThat(NodeCapabilities.hostGpuProbes.get()).isEqualTo(after);
    }
}
