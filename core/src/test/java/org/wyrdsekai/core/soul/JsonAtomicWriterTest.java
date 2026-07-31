package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 9a-PersistRefactor: targeted contract tests for the shared
 * JsonAtomicWriter helper that all four substrate trackers now use.
 */
class JsonAtomicWriterTest {

    private static final ObjectMapper READ_MAPPER;
    static {
        READ_MAPPER = new ObjectMapper();
        READ_MAPPER.registerModule(new JavaTimeModule());
    }

    @Test
    void writes_pretty_printed_json(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("hello.json");
        JsonAtomicWriter.write(file, Map.of("greeting", "hello"));
        var content = Files.readString(file);
        assertThat(content)
            .contains("\"greeting\"")
            .contains("\"hello\"")
            // pretty-printed → multiple lines
            .contains("\n");
    }

    @Test
    void creates_missing_parent_directories(@TempDir Path tmp) throws Exception {
        var nested = tmp.resolve("deep").resolve("nest").resolve("out.json");
        JsonAtomicWriter.write(nested, Map.of("k", "v"));
        assertThat(Files.exists(nested)).isTrue();
    }

    @Test
    void leaves_no_tmp_file_after_successful_write(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("out.json");
        JsonAtomicWriter.write(file, Map.of("k", 1));
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.exists(tmp.resolve("out.json.tmp"))).isFalse();
    }

    @Test
    void serializes_jsr310_instants(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("instant.json");
        var now = Instant.parse("2026-05-15T12:00:00Z");
        JsonAtomicWriter.write(file, Map.of("when", now));
        var content = Files.readString(file);
        // JavaTimeModule registered → Instant serializes as ISO string or epoch seconds
        // (the default for jsr310 is epoch with nano fraction). The point of the
        // test is that the module *is* registered — Instant becomes a JSON number/string,
        // not a "java.time.Instant" placeholder.
        assertThat(content).matches("(?s).*\"when\"\\s*:\\s*(\"2026[^\"]+\"|\\d+\\.?\\d*).*");
    }

    @Test
    void second_write_replaces_first_atomically(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("out.json");
        JsonAtomicWriter.write(file, Map.of("v", 1));
        var size1 = Files.size(file);

        var bigger = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 50; i++) bigger.put("k" + i, "value-with-padding-" + i);
        JsonAtomicWriter.write(file, bigger);
        var size2 = Files.size(file);

        assertThat(size2).isGreaterThan(size1);
        assertThat(Files.exists(tmp.resolve("out.json.tmp"))).isFalse();
    }

    @Test
    void preexisting_tmp_is_overwritten_not_appended(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("out.json");
        var leftover = tmp.resolve("out.json.tmp");
        Files.writeString(leftover, "{garbage from prior crash");

        JsonAtomicWriter.write(file, Map.of("clean", true));

        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.exists(leftover))
            .as("the leftover .tmp must be consumed (overwritten + renamed away)")
            .isFalse();
        // Confirm the result is the new clean JSON, not the leftover garbage.
        MapType mapType = READ_MAPPER.getTypeFactory()
            .constructMapType(LinkedHashMap.class, String.class, Object.class);
        Map<String, Object> read = READ_MAPPER.readValue(file.toFile(), mapType);
        assertThat(read).containsEntry("clean", true);
    }

    @Test
    void null_path_throws_illegalArgument() {
        assertThatThrownBy(() -> JsonAtomicWriter.write(null, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("file required");
    }

    @Test
    void absolute_path_at_filesystem_root_does_not_crash(@TempDir Path tmp) throws Exception {
        // Direct child of an existing dir — parent already exists, so
        // createDirectories is a no-op. Just confirm no NPE on getParent().
        var file = tmp.resolve("at-root.json");
        JsonAtomicWriter.write(file, Map.of("ok", true));
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void write_throws_IOException_when_value_unserializable(@TempDir Path tmp) {
        var file = tmp.resolve("bad.json");
        // A non-static inner class with a back-reference and no Jackson
        // module is hard for Jackson to serialize; use a Path which
        // Jackson normally serializes fine, but a self-referential map
        // is the canonical "loop" trigger.
        var cycle = new LinkedHashMap<String, Object>();
        cycle.put("self", cycle);
        assertThatThrownBy(() -> JsonAtomicWriter.write(file, cycle))
            .isInstanceOf(IOException.class);
        // After a failed serialization, no real file should exist.
        // (A .tmp may or may not be present depending on when the
        // failure fires; the contract is about file, not tmp.)
        assertThat(Files.exists(file)).isFalse();
    }
}
