package org.wyrdsekai.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.inference.CapabilityRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CapabilityRegistry hot-reload integration with HotReloadableConfig.
 */
class CapabilityRegistryHotReloadTest {

    @TempDir
    Path tempDir;

    @Test
    void loads_capabilities_from_file() throws IOException {
        var file = tempDir.resolve("capabilities.properties");
        Files.writeString(file, """
            reasoning.gpu-host-sglang=qwen2.5:72b,household,1
            coding.gpu-host-sglang=qwen3-coder:30b,household,2
            quick.local-ollama=qwen2.5:7b,local,1
            """);

        var registry = new CapabilityRegistry();
        registry.enableHotReload(file);
        registry.applyOverrides();

        assertThat(registry.availableCapabilities()).contains("reasoning", "coding", "quick");

        var reasoning = registry.resolve("reasoning");
        assertThat(reasoning).isPresent();
        assertThat(reasoning.get().model()).isEqualTo("qwen2.5:72b");
        assertThat(reasoning.get().backendName()).isEqualTo("gpu-host-sglang");
        assertThat(reasoning.get().tier()).isEqualTo("household");
    }

    @Test
    void hot_reloads_when_file_changes() throws Exception {
        var file = tempDir.resolve("capabilities.properties");
        Files.writeString(file, "reasoning.backend1=model-a,household,1\n");

        var registry = new CapabilityRegistry();
        registry.enableHotReload(file);
        registry.applyOverrides();

        assertThat(registry.resolve("reasoning").get().model()).isEqualTo("model-a");

        // Modify the file
        Thread.sleep(50);
        Files.writeString(file, "reasoning.backend1=model-b,cloud,1\n");
        file.toFile().setLastModified(System.currentTimeMillis() + 1000);

        registry.applyOverrides();

        assertThat(registry.resolve("reasoning").get().model()).isEqualTo("model-b");
        assertThat(registry.resolve("reasoning").get().tier()).isEqualTo("cloud");
    }

    @Test
    void overrides_merge_with_existing_capabilities() throws IOException {
        var file = tempDir.resolve("capabilities.properties");
        Files.writeString(file, "reasoning.new-backend=big-model,cloud,1\n");

        var registry = new CapabilityRegistry();
        // Register existing capability
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "existing-backend", "old-model", "local", 5));

        registry.enableHotReload(file);
        registry.applyOverrides();

        // Both entries should exist
        var entries = registry.entries("reasoning");
        assertThat(entries).hasSize(2);
        // Cloud backend has priority 1, should be first
        assertThat(entries.getFirst().model()).isEqualTo("big-model");
        assertThat(entries.get(1).model()).isEqualTo("old-model");
    }

    @Test
    void skips_malformed_entries() throws IOException {
        var file = tempDir.resolve("capabilities.properties");
        Files.writeString(file, """
            # Valid
            reasoning.backend=model,household,1
            # Missing dot separator
            badkey=model,local,1
            # Missing tier
            coding.backend=model
            # Valid
            quick.backend=small,local,2
            """);

        var registry = new CapabilityRegistry();
        registry.enableHotReload(file);
        registry.applyOverrides();

        // Only well-formed entries loaded
        assertThat(registry.resolve("reasoning")).isPresent();
        assertThat(registry.resolve("quick")).isPresent();
        // Malformed entries skipped
        assertThat(registry.resolve("badkey")).isEmpty();
    }

    @Test
    void no_override_config_means_applyOverrides_is_noop() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "default", "backend", "model", "local", 1));

        // No enableHotReload called — applyOverrides should be safe noop
        registry.applyOverrides();

        assertThat(registry.resolve("default")).isPresent();
    }

    @Test
    void tier_constraint_works_with_overrides() throws IOException {
        var file = tempDir.resolve("capabilities.properties");
        Files.writeString(file, """
            reasoning.cloud-backend=big-model,cloud,1
            reasoning.local-backend=small-model,local,5
            """);

        var registry = new CapabilityRegistry();
        registry.enableHotReload(file);
        registry.applyOverrides();

        // With maxTier "local", only local backend should be returned
        var local = registry.resolve("reasoning", "local");
        assertThat(local).isPresent();
        assertThat(local.get().model()).isEqualTo("small-model");

        // Without tier constraint, cloud backend (priority 1) wins
        var any = registry.resolve("reasoning");
        assertThat(any).isPresent();
        assertThat(any.get().model()).isEqualTo("big-model");
    }
}
