package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Release-accurate model attribution via models-manifest.jsonl (data-durability, 2026-07-09). */
class ModelAttributionTest {

    @Test
    void resolves_version_from_manifest_last_entry_wins(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("models-manifest.jsonl"),
            "{\"v\":1,\"file\":\"drive.gguf\",\"id\":\"drive-9b\",\"version\":\"v6\",\"sha256\":\"a\"}\n"
            + "{\"v\":1,\"file\":\"drive.gguf\",\"id\":\"drive-9b\",\"version\":\"v7\",\"sha256\":\"b\"}\n");
        assertThat(ModelAttribution.withVersion(tmp, "drive.gguf")).isEqualTo("drive.gguf@v7");
    }

    @Test
    void degrades_to_bare_filename_without_manifest(@TempDir Path tmp) {
        assertThat(ModelAttribution.withVersion(tmp, "drive.gguf")).isEqualTo("drive.gguf");
    }

    @Test
    void unknown_version_stays_bare(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("models-manifest.jsonl"),
            "{\"v\":1,\"file\":\"drive.gguf\",\"id\":\"drive-9b\",\"version\":\"unknown\",\"sha256\":\"x\"}\n");
        assertThat(ModelAttribution.withVersion(tmp, "drive.gguf")).isEqualTo("drive.gguf");
    }

    @Test
    void null_and_missing_are_safe(@TempDir Path tmp) {
        assertThat(ModelAttribution.withVersion(tmp, null)).isEqualTo("unknown");
        assertThat(ModelAttribution.withVersion(tmp.resolve("nope"), "x.gguf")).isEqualTo("x.gguf");
    }
}
