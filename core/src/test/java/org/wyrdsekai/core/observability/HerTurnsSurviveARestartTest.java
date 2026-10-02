package org.wyrdsekai.core.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate before a brain move answers her last turns again. They lived only in memory and were gone
 * at every restart ("too few recent turns", 2026-10-01). Kept on disk under the data directory now,
 * trimmed at boot.
 */
class HerTurnsSurviveARestartTest {

    private static ShadowLog.ShadowEntry entry(String text) {
        return new ShadowLog.ShadowEntry(Instant.now(), "companion-mia", "mia", "nexus", "p1", "operator says: " + text,
            Map.of(), List.of(), List.of(), text, text, 10, 5, null);
    }

    @Test
    void turns_are_written_under_the_data_dir_and_trimmed_at_boot(@TempDir Path dir) throws Exception {
        var log = new ShadowLog(dir.resolve("shadow.jsonl"));
        for (int i = 0; i < 12; i++) log.record(entry("line " + i));
        assertThat(Files.readAllLines(dir.resolve("shadow.jsonl"))).hasSize(12);

        ShadowLog.trimToLast(dir.resolve("shadow.jsonl"), 5);
        var kept = Files.readAllLines(dir.resolve("shadow.jsonl"));
        assertThat(kept).hasSize(5);
        assertThat(kept.get(4)).contains("line 11");
        assertThat(kept.get(0)).contains("line 7");
    }

    @Test
    void a_missing_or_short_file_is_left_alone(@TempDir Path dir) throws Exception {
        ShadowLog.trimToLast(dir.resolve("none.jsonl"), 5);
        assertThat(Files.exists(dir.resolve("none.jsonl"))).isFalse();
        Files.writeString(dir.resolve("short.jsonl"), "{}\n{}\n");
        ShadowLog.trimToLast(dir.resolve("short.jsonl"), 5);
        assertThat(Files.readAllLines(dir.resolve("short.jsonl"))).hasSize(2);
    }
}
