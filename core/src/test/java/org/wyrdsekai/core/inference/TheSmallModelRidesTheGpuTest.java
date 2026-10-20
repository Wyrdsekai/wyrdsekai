package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A household's 4B voice model ran on the processor for five weeks beside an idle Radeon
 * (2026-10-19): the bundled llama-server was a CPU build and the node asked for no GPU layers.
 * Since 0.5.8 the Vulkan backend ships beside the binary, and the server the node starts puts
 * every layer on the GPU when the host has one; nothing changes where there is no backend or no
 * GPU, and WYRDSEKAI_GPU_LAYERS has the last word.
 */
class TheSmallModelRidesTheGpuTest {

    private static Path drmWith(Path root, String... vendors) throws Exception {
        var drm = root.resolve("drm");
        for (int i = 0; i < vendors.length; i++) {
            Files.createDirectories(drm.resolve("card" + i + "/device"));
            Files.writeString(drm.resolve("card" + i + "/device/vendor"), vendors[i] + "\n");
            Files.createDirectories(drm.resolve("card" + i + "-eDP-1"));   // a connector, not a card
        }
        Files.createDirectories(drm.resolve("renderD128"));
        return drm;
    }

    private static Path serverWith(Path root, boolean vulkan) throws Exception {
        var bin = root.resolve("bin");
        Files.createDirectories(bin);
        var exe = bin.resolve("llama-server");
        Files.writeString(exe, "#!/bin/sh\n");
        exe.toFile().setExecutable(true);
        Files.writeString(bin.resolve("libggml-cpu-x64.so"), "");
        if (vulkan) Files.writeString(bin.resolve("libggml-vulkan.so"), "");
        return exe;
    }

    @Test
    @DisplayName("the household box: the Vulkan backend beside the server and a Radeon in the host — every layer on it")
    void aRadeonAndTheVulkanBackend(@TempDir Path tmp) throws Exception {
        var exe = serverWith(tmp, true);
        var drm = drmWith(tmp, "0x1002");
        assertThat(LlamaServerManager.defaultGpuLayers(exe.toString(), drm, null)).isEqualTo(99);
        assertThat(LlamaServerManager.gpuVendorUnderDrm(drm)).isEqualTo("amd");
    }

    @Test
    @DisplayName("an Intel laptop too; and a box with both names the card")
    void intelAndBoth(@TempDir Path tmp) throws Exception {
        var exe = serverWith(tmp, true);
        assertThat(LlamaServerManager.defaultGpuLayers(exe.toString(), drmWith(tmp.resolve("a"), "0x8086"), null)).isEqualTo(99);
        assertThat(LlamaServerManager.gpuVendorUnderDrm(drmWith(tmp.resolve("b"), "0x8086", "0x10de"))).isEqualTo("nvidia");
    }

    @Test
    @DisplayName("no GPU in the host, or no backend beside the server: the processor, as before")
    void nothingToRide(@TempDir Path tmp) throws Exception {
        var withBackend = serverWith(tmp.resolve("v"), true);
        var noGpu = tmp.resolve("nogpu/drm");
        Files.createDirectories(noGpu);
        assertThat(LlamaServerManager.defaultGpuLayers(withBackend.toString(), noGpu, null)).isEqualTo(0);
        assertThat(LlamaServerManager.defaultGpuLayers(withBackend.toString(), tmp.resolve("missing"), null)).isEqualTo(0);
        var cpuOnly = serverWith(tmp.resolve("c"), false);
        assertThat(LlamaServerManager.defaultGpuLayers(cpuOnly.toString(), drmWith(tmp.resolve("g"), "0x1002"), null)).isEqualTo(0);
        assertThat(LlamaServerManager.defaultGpuLayers(tmp.resolve("nowhere/llama-server").toString(), drmWith(tmp.resolve("h"), "0x1002"), null))
            .as("a binary that is not there").isEqualTo(0);
    }

    @Test
    @DisplayName("WYRDSEKAI_GPU_LAYERS has the last word, 0 included")
    void theStewardsWord(@TempDir Path tmp) throws Exception {
        var exe = serverWith(tmp, true);
        var drm = drmWith(tmp, "0x1002");
        assertThat(LlamaServerManager.defaultGpuLayers(exe.toString(), drm, "0")).isEqualTo(0);
        assertThat(LlamaServerManager.defaultGpuLayers(exe.toString(), drm, "20")).isEqualTo(20);
        assertThat(LlamaServerManager.defaultGpuLayers(exe.toString(), drm, "auto")).as("not a number: the rule decides").isEqualTo(99);
    }

    @Test
    @DisplayName("layers asked for: the flag; none: no GPU at all, not a visible one with nothing on it")
    void theCommandLine() {
        var gpu = LlamaServerManager.command("llama-server", "/m.gguf", 11525, 99, 1, 8192, 1024);
        assertThat(gpu).contains("--n-gpu-layers", "99").doesNotContain("--device");
        // A Vulkan device visible with zero layers ran fifteen times slower than the processor alone
        // (0.7 against 10.5 tokens a second, the build box, 2026-10-20): zero layers means no device.
        var cpu = LlamaServerManager.command("llama-server", "/m.gguf", 11525, 0, 1, 8192, 1024);
        assertThat(cpu).doesNotContain("--n-gpu-layers").containsSequence("--device", "none");
    }
}
