package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 9a-Chronicle: verify that ChronicleService.renderSynthesis folds
 * `type: "resilience"` events from the activity log into the synthesis
 * narrative. Uses a real on-disk JSONL fixture instead of mocking
 * TickLogReader (which is a final class with a file constructor).
 */
class ChronicleResilienceFoldingTest {

    @Test
    void synthesis_surfaces_resilience_counts_when_log_has_entries(
            @TempDir Path tmp) throws Exception {
        var logFile = tmp.resolve("activity.jsonl");
        var agent = "did:agent:probe";
        var now = Instant.now();
        var earlier = now.minusSeconds(60);

        var lines = new ArrayList<String>();
        lines.add(tickLine(agent, "probe", earlier, "acted"));
        lines.add(tickLine(agent, "probe", earlier.plusSeconds(1), "acted"));
        lines.add(tickLine(agent, "probe", earlier.plusSeconds(2), "chose_rest"));
        lines.add(resilienceLine(agent, "probe", earlier.plusSeconds(10),
            "HEALTHY_ENDURANCE", 0.9, "calm baseline"));
        lines.add(resilienceLine(agent, "probe", earlier.plusSeconds(20),
            "HEALTHY_ENDURANCE", 0.85, "calm baseline"));
        lines.add(resilienceLine(agent, "probe", earlier.plusSeconds(30),
            "INTEGRATING", 0.72, "affect descending after spike"));
        Files.write(logFile, lines);

        var reader = new TickLogReader(logFile);
        var service = new ChronicleService(reader);
        var chron = service.build(agent, "probe", ChronicleService.Scale.DAY);

        assertThat(chron.synthesis())
            .as("synthesis should fold resilience counts into a 'Substrate trajectory' line")
            .contains("Substrate trajectory:")
            .contains("healthy_endurance(2)")
            .contains("integrating(1)")
            .contains("most recent: integrating");
    }

    @Test
    void synthesis_omits_resilience_line_when_no_entries(@TempDir Path tmp)
            throws Exception {
        var logFile = tmp.resolve("activity.jsonl");
        var agent = "did:agent:quiet";
        var now = Instant.now();
        // Tick activity but no resilience entries.
        Files.write(logFile, List.of(
            tickLine(agent, "quiet", now.minusSeconds(30), "acted"),
            tickLine(agent, "quiet", now.minusSeconds(20), "acted")));

        var reader = new TickLogReader(logFile);
        var service = new ChronicleService(reader);
        var chron = service.build(agent, "quiet", ChronicleService.Scale.DAY);

        assertThat(chron.synthesis())
            .as("no resilience entries → no Substrate trajectory line")
            .doesNotContain("Substrate trajectory")
            .doesNotContain("healthy_endurance");
    }

    @Test
    void synthesis_skips_insufficient_data_as_most_recent(@TempDir Path tmp)
            throws Exception {
        var logFile = tmp.resolve("activity.jsonl");
        var agent = "did:agent:warmup";
        var t = Instant.now().minusSeconds(60);
        Files.write(logFile, List.of(
            tickLine(agent, "warmup", t, "acted"),
            resilienceLine(agent, "warmup", t.plusSeconds(5),
                "HEALTHY_ENDURANCE", 0.7, "ok"),
            // Latest entry is INSUFFICIENT_DATA — should NOT win "most recent"
            resilienceLine(agent, "warmup", t.plusSeconds(20),
                "INSUFFICIENT_DATA", 1.0, "warmup buffer")));

        var reader = new TickLogReader(logFile);
        var service = new ChronicleService(reader);
        var chron = service.build(agent, "warmup", ChronicleService.Scale.DAY);

        assertThat(chron.synthesis())
            .as("Substrate trajectory must skip INSUFFICIENT_DATA for the "
                + "'most recent' callout because it's not a substrate signal")
            .contains("Substrate trajectory:")
            .contains("insufficient_data(1)")
            .contains("most recent: healthy_endurance");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private String tickLine(String agentId, String agentName, Instant ts, String gateOutcome) {
        return "{\"type\":\"tick\","
            + "\"ts\":\"" + ts.toString() + "\","
            + "\"agent\":\"" + agentName + "\","
            + "\"agentId\":\"" + agentId + "\","
            + "\"energy\":0.5,"
            + "\"gateOutcome\":\"" + gateOutcome + "\","
            + "\"nextTickDelaySeconds\":1,"
            + "\"tickDurationMs\":5}";
    }

    private String resilienceLine(String agentId, String agentName, Instant ts,
                                   String classification, double confidence, String reason) {
        return "{\"type\":\"resilience\","
            + "\"ts\":\"" + ts.toString() + "\","
            + "\"agent\":\"" + agentName + "\","
            + "\"agentId\":\"" + agentId + "\","
            + "\"classification\":\"" + classification + "\","
            + "\"confidence\":" + confidence + ","
            + "\"reason\":\"" + reason + "\"}";
    }
}
