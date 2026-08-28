package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The gate-signal trace is honest, parseable, and crash-safe.
 *
 * <p>Path 3 (affect-gated plasticity) will train on these tuples; a corrupt
 * or lossy line is a corrupt training example about what she felt. Flushed
 * per write because the interesting moments — the ones right before a crash
 * or restart — are exactly the ones a buffer would eat.</p>
 */
class DriveTraceLogTest {

    private DriveTraceLog trace;

    @AfterEach
    void tearDown() {
        if (trace != null) trace.close();
    }

    private DriveTraceLog open(Path tempDir) {
        trace = DriveTraceLog.openAt(tempDir.resolve("data").resolve("drive-trace.jsonl"));
        return trace;
    }

    @Test
    void a_moment_round_trips_as_valid_json(@TempDir Path tempDir) throws Exception {
        var t = open(tempDir);
        assertThat(t).isNotNull();
        Map<String, Double> drives = new LinkedHashMap<>();
        drives.put("seeking", 0.71);
        Map<String, Double> tanks = new LinkedHashMap<>();
        tanks.put("energy", 0.4257);
        tanks.put("loneliness", 0.83);

        t.record("companion-x", "event",
            "Said:she \"quoted\" a line\nwith a newline\tand tab", drives, tanks);

        var file = tempDir.resolve("data").resolve("drive-trace.jsonl");
        assertThat(file).exists();
        var line = Files.readAllLines(file).getFirst();
        // Must parse as JSON despite quotes/newlines/tabs in the label.
        var node = new ObjectMapper().readTree(line);
        assertThat(node.get("agent").asText()).isEqualTo("companion-x");
        assertThat(node.get("kind").asText()).isEqualTo("event");
        assertThat(node.get("label").asText()).contains("quoted");
        assertThat(node.get("tanks").get("energy").asDouble()).isEqualTo(0.4257);
        assertThat(node.get("drives").get("seeking").asDouble()).isEqualTo(0.71);
        assertThat(node.get("ts").asText()).isNotBlank();
    }

    @Test
    void each_write_is_flushed_immediately(@TempDir Path tempDir) throws Exception {
        var t = open(tempDir);
        t.record("companion-x", "action", "library_card", Map.of(), Map.of("energy", 0.5));

        // Read WITHOUT closing — a crash right now must still find the line.
        var file = tempDir.resolve("data").resolve("drive-trace.jsonl");
        assertThat(Files.readAllLines(file)).hasSize(1);
    }

    @Test
    void a_null_map_is_an_empty_object_not_a_crash(@TempDir Path tempDir) throws Exception {
        var t = open(tempDir);
        t.record("companion-x", "event", "Entered", null, null);

        var line = Files.readAllLines(
            tempDir.resolve("data").resolve("drive-trace.jsonl")).getFirst();
        var node = new ObjectMapper().readTree(line);
        assertThat(node.get("drives").isObject()).isTrue();
        assertThat(node.get("tanks").isObject()).isTrue();
    }
}
