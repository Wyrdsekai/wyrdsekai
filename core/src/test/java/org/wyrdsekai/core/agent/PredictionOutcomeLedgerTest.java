package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PredictionOutcomeLedgerTest {

    @Test
    void appends_jsonl_with_all_fields(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var firedAt = Instant.parse("2026-05-08T10:00:00Z");
        var observedAt = Instant.parse("2026-05-08T10:02:30Z");
        ledger.append(new PredictionOutcomeLedger.Entry(
            "p1", "agent-x", "temporal",
            PredictionOutcomeLedger.Kind.FOLLOWED_UP,
            firedAt, observedAt, "token=garden"));

        var lines = Files.readAllLines(tmp.resolve("m4").resolve("outcomes.jsonl"));
        assertThat(lines).hasSize(1);
        var line = lines.get(0);
        assertThat(line).contains("\"predictionId\":\"p1\"");
        assertThat(line).contains("\"agentId\":\"agent-x\"");
        assertThat(line).contains("\"category\":\"temporal\"");
        assertThat(line).contains("\"kind\":\"FOLLOWED_UP\"");
        assertThat(line).contains("\"firedAt\":\"2026-05-08T10:00:00Z\"");
        assertThat(line).contains("\"observedAt\":\"2026-05-08T10:02:30Z\"");
        assertThat(line).contains("\"signal\":\"token=garden\"");
    }

    @Test
    void appends_multiple_records_in_order(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        ledger.append(new PredictionOutcomeLedger.Entry(
            "a", "ag", "topic", PredictionOutcomeLedger.Kind.IGNORED, t, t, "expired"));
        ledger.append(new PredictionOutcomeLedger.Entry(
            "b", "ag", "topic", PredictionOutcomeLedger.Kind.DISMISSED, t, t, "phrase=later"));
        ledger.append(new PredictionOutcomeLedger.Entry(
            "c", "ag", "topic", PredictionOutcomeLedger.Kind.FOLLOWED_UP, t, t, "token=foo"));

        var lines = Files.readAllLines(tmp.resolve("m4").resolve("outcomes.jsonl"));
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains("\"predictionId\":\"a\"").contains("IGNORED");
        assertThat(lines.get(1)).contains("\"predictionId\":\"b\"").contains("DISMISSED");
        assertThat(lines.get(2)).contains("\"predictionId\":\"c\"").contains("FOLLOWED_UP");
    }

    @Test
    void escapes_quotes_and_backslashes(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        ledger.append(new PredictionOutcomeLedger.Entry(
            "p", "ag", "topic", PredictionOutcomeLedger.Kind.FOLLOWED_UP, t, t,
            "token=\"weird\\value\""));

        var lines = Files.readAllLines(tmp.resolve("m4").resolve("outcomes.jsonl"));
        assertThat(lines.get(0)).contains("\\\"weird\\\\value\\\"");
    }

    @Test
    void null_data_dir_silently_drops_writes() {
        var ledger = new PredictionOutcomeLedger(null);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        // Must not throw — production hot path tolerates ledger-disabled config.
        ledger.append(new PredictionOutcomeLedger.Entry(
            "p", "ag", "topic", PredictionOutcomeLedger.Kind.IGNORED, t, t, "x"));
        assertThat(ledger.ledgerFile()).isNull();
    }
}
