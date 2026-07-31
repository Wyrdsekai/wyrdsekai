package org.wyrdsekai.scripting.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptLoaderTest {

    @TempDir Path baseDir;
    @TempDir Path userDir;

    @Test void load_existing_script() throws IOException {
        Files.writeString(baseDir.resolve("nexus.js"), "function onEnter() {}");
        var loader = new ScriptLoader(baseDir);
        assertThat(loader.load("nexus")).isEqualTo("function onEnter() {}");
    }

    @Test void load_missing_script_returns_null() {
        var loader = new ScriptLoader(baseDir);
        assertThat(loader.load("nonexistent")).isNull();
    }

    @Test void load_caches_script() throws IOException {
        Files.writeString(baseDir.resolve("test.js"), "original");
        var loader = new ScriptLoader(baseDir);

        var first = loader.load("test");
        var second = loader.load("test");
        assertThat(first).isEqualTo("original");
        assertThat(second).isEqualTo("original");
    }

    @Test void invalidate_clears_cache() throws IOException {
        var scriptFile = baseDir.resolve("test.js");
        Files.writeString(scriptFile, "version1");
        var loader = new ScriptLoader(baseDir);

        assertThat(loader.load("test")).isEqualTo("version1");

        // Update file and invalidate
        Files.writeString(scriptFile, "version2");
        loader.invalidate("test");
        assertThat(loader.load("test")).isEqualTo("version2");
    }

    @Test void user_scripts_override_base() throws IOException {
        Files.writeString(baseDir.resolve("nexus.js"), "base version");
        Files.writeString(userDir.resolve("nexus.js"), "user version");
        var loader = new ScriptLoader(baseDir, userDir);

        assertThat(loader.load("nexus")).isEqualTo("user version");
    }

    @Test void user_scripts_null_falls_back_to_base() throws IOException {
        Files.writeString(baseDir.resolve("nexus.js"), "base version");
        var loader = new ScriptLoader(baseDir, null);

        assertThat(loader.load("nexus")).isEqualTo("base version");
    }

    @Test void user_script_missing_falls_back_to_base() throws IOException {
        Files.writeString(baseDir.resolve("nexus.js"), "base version");
        // userDir exists but has no nexus.js
        var loader = new ScriptLoader(baseDir, userDir);

        assertThat(loader.load("nexus")).isEqualTo("base version");
    }
}
