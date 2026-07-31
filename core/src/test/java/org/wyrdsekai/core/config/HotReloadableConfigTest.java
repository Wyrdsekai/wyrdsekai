package org.wyrdsekai.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HotReloadableConfig} — generic hot-reload utility.
 */
class HotReloadableConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void loads_from_file() throws IOException {
        var file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello");

        var config = new HotReloadableConfig<>(file, p -> {
            try { return Files.readString(p); }
            catch (IOException e) { throw new RuntimeException(e); }
        }, "default");

        assertThat(config.get()).isEqualTo("hello");
    }

    @Test
    void returns_default_when_file_missing() {
        var missing = tempDir.resolve("does-not-exist.txt");

        var config = new HotReloadableConfig<>(missing, p -> "loaded", "default-value");

        assertThat(config.get()).isEqualTo("default-value");
    }

    @Test
    void hot_reloads_when_file_changes() throws Exception {
        var file = tempDir.resolve("test.txt");
        Files.writeString(file, "version1");

        var config = new HotReloadableConfig<>(file, p -> {
            try { return Files.readString(p); }
            catch (IOException e) { throw new RuntimeException(e); }
        }, "default");

        assertThat(config.get()).isEqualTo("version1");

        // Ensure file modification time changes (some filesystems have 1s granularity)
        Thread.sleep(50);
        Files.writeString(file, "version2");
        // Touch the file to ensure modification time changes
        file.toFile().setLastModified(System.currentTimeMillis() + 1000);

        assertThat(config.get()).isEqualTo("version2");
    }

    @Test
    void does_not_reload_when_file_unchanged() throws IOException {
        var file = tempDir.resolve("test.txt");
        Files.writeString(file, "stable");

        var loadCount = new AtomicInteger(0);
        var config = new HotReloadableConfig<>(file, p -> {
            loadCount.incrementAndGet();
            try { return Files.readString(p); }
            catch (IOException e) { throw new RuntimeException(e); }
        }, "default");

        // First access triggers load
        config.get();
        assertThat(loadCount.get()).isEqualTo(1);

        // Second access — file unchanged, should NOT reload
        config.get();
        assertThat(loadCount.get()).isEqualTo(1);

        // Third access — still no change
        config.get();
        assertThat(loadCount.get()).isEqualTo(1);
    }

    @Test
    void survives_file_deletion() throws Exception {
        var file = tempDir.resolve("test.txt");
        Files.writeString(file, "ephemeral");

        var config = new HotReloadableConfig<>(file, p -> {
            try { return Files.readString(p); }
            catch (IOException e) { throw new RuntimeException(e); }
        }, "default");

        // Load the value
        assertThat(config.get()).isEqualTo("ephemeral");

        // Delete the file
        Files.delete(file);

        // Should return last cached value (not default)
        assertThat(config.get()).isEqualTo("ephemeral");
    }

    @Test
    void getCached_does_not_check_file() throws Exception {
        var file = tempDir.resolve("test.txt");
        Files.writeString(file, "initial");

        var loadCount = new AtomicInteger(0);
        var config = new HotReloadableConfig<>(file, p -> {
            loadCount.incrementAndGet();
            try { return Files.readString(p); }
            catch (IOException e) { throw new RuntimeException(e); }
        }, "default");

        // Load once via get()
        config.get();
        assertThat(loadCount.get()).isEqualTo(1);

        // Change the file
        Thread.sleep(50);
        Files.writeString(file, "changed");
        file.toFile().setLastModified(System.currentTimeMillis() + 1000);

        // getCached should return old value without triggering reload
        assertThat(config.getCached()).isEqualTo("initial");
        assertThat(loadCount.get()).isEqualTo(1);
    }

    @Test
    void reload_forces_reread() throws Exception {
        var file = tempDir.resolve("test.txt");
        Files.writeString(file, "original");

        var config = new HotReloadableConfig<>(file, p -> {
            try { return Files.readString(p); }
            catch (IOException e) { throw new RuntimeException(e); }
        }, "default");

        assertThat(config.get()).isEqualTo("original");

        // Overwrite with same timestamp (reload wouldn't normally trigger)
        Files.writeString(file, "forced-update");

        // Force reload — resets lastModified to 0
        assertThat(config.reload()).isEqualTo("forced-update");
    }

    @Test
    void null_path_returns_default() {
        var config = new HotReloadableConfig<String>(null, p -> "loaded", "null-path-default");

        assertThat(config.get()).isEqualTo("null-path-default");
        assertThat(config.getCached()).isEqualTo("null-path-default");
        assertThat(config.reload()).isEqualTo("null-path-default");
        assertThat(config.path()).isNull();
    }
}
