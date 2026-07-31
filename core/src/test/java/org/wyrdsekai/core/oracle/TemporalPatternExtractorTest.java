package org.wyrdsekai.core.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TemporalPatternExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AGENT_ID = "companion-wyrd";

    @TempDir
    Path tempDir;

    // ─── Frequency Detection ────────────────────────────────────

    @Test
    void detectsFrequency_whenActionRepeats3PlusTimes() throws IOException {
        var log = writeEvents(tempDir,
            action("library_search", -5), action("library_search", -4),
            action("library_search", -3), action("library_search", -1));

        var extractor = new TemporalPatternExtractor();
        var predictions = extractor.extract(AGENT_ID, log, 14);

        assertTrue(predictions.stream()
            .anyMatch(p -> p.id().contains("freq") && p.text().contains("library search")),
            "Should detect repeated library_search: " + predictions);
    }

    @Test
    void noFrequency_whenActionAppearsTwice() throws IOException {
        var log = writeEvents(tempDir,
            action("library_search", -3), action("library_search", -1));

        var extractor = new TemporalPatternExtractor();
        var predictions = extractor.extract(AGENT_ID, log, 14);

        assertTrue(predictions.stream().noneMatch(p -> p.id().contains("freq-library")),
            "Should not detect frequency with only 2 events");
    }

    // ─── Time-of-Day Detection ──────────────────────────────────

    @Test
    void detectsTimeOfDay_whenWakeEventsCluster() throws IOException {
        // 3 wake events all around the same hour
        var now = Instant.now();
        var events = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            events.add(event("wake", AGENT_ID, now.minus(i * 24 + 1, ChronoUnit.HOURS)));
        }
        var log = writeRawEvents(tempDir, events);

        var extractor = new TemporalPatternExtractor();
        var predictions = extractor.extract(AGENT_ID, log, 14);

        assertTrue(predictions.stream().anyMatch(p -> p.id().contains("tod")),
            "Should detect time-of-day cluster: " + predictions);
    }

    // ─── Sequence Detection ─────────────────────────────────────

    @Test
    void detectsSequence_whenAFollowedByBRepeatedly() throws IOException {
        var log = writeEvents(tempDir,
            action("library_search", -6, 0), action("web_search", -6, 10),
            action("library_search", -4, 0), action("web_search", -4, 10),
            action("library_search", -2, 0), action("web_search", -2, 10));

        var extractor = new TemporalPatternExtractor();
        var predictions = extractor.extract(AGENT_ID, log, 14);

        assertTrue(predictions.stream()
            .anyMatch(p -> p.id().contains("seq") && p.text().contains("library search")
                && p.text().contains("web search")),
            "Should detect library_search→web_search sequence: " + predictions);
    }

    // ─── Rhythm Detection ───────────────────────────────────────

    @Test
    void detectsRhythm_whenSessionGapsAreRegular() throws IOException {
        // 6 wake events at ~24h intervals (low variance)
        var now = Instant.now();
        var events = new ArrayList<String>();
        for (int i = 0; i < 6; i++) {
            events.add(event("wake", AGENT_ID, now.minus(i * 24, ChronoUnit.HOURS)));
        }
        var log = writeRawEvents(tempDir, events);

        var extractor = new TemporalPatternExtractor();
        var predictions = extractor.extract(AGENT_ID, log, 14);

        assertTrue(predictions.stream().anyMatch(p -> p.id().contains("rhythm")),
            "Should detect ~24h rhythm: " + predictions);
    }

    @Test
    void noRhythm_whenSessionGapsAreIrregular() throws IOException {
        var now = Instant.now();
        var events = new ArrayList<String>();
        events.add(event("wake", AGENT_ID, now.minus(100, ChronoUnit.HOURS)));
        events.add(event("wake", AGENT_ID, now.minus(50, ChronoUnit.HOURS)));
        events.add(event("wake", AGENT_ID, now.minus(48, ChronoUnit.HOURS)));
        events.add(event("wake", AGENT_ID, now.minus(10, ChronoUnit.HOURS)));
        events.add(event("wake", AGENT_ID, now.minus(9, ChronoUnit.HOURS)));
        events.add(event("wake", AGENT_ID, now.minus(1, ChronoUnit.HOURS)));
        var log = writeRawEvents(tempDir, events);

        var extractor = new TemporalPatternExtractor();
        var predictions = extractor.extract(AGENT_ID, log, 14);

        assertTrue(predictions.stream().noneMatch(p -> p.id().contains("rhythm")),
            "Should not detect rhythm with irregular gaps");
    }

    // ─── Absence Detection ──────────────────────────────────────

    @Test
    void detectsAbsence_whenGapExceedsTwiceMean() throws IOException {
        // Regular 12h sessions, then 3 days of silence
        var now = Instant.now();
        var events = new ArrayList<String>();
        for (int i = 6; i >= 1; i--) {
            events.add(event("wake", AGENT_ID, now.minus(3 * 24 + i * 12, ChronoUnit.HOURS)));
        }
        // Last activity was 3 days ago
        var log = writeRawEvents(tempDir, events);

        var extractor = new TemporalPatternExtractor();
        var predictions = extractor.extract(AGENT_ID, log, 14);

        assertTrue(predictions.stream().anyMatch(p -> p.id().contains("absence")),
            "Should detect unusual absence: " + predictions);
    }

    // ─── Topic Drift Detection ──────────────────────────────────

    @Test
    void detectsTopicDrift_whenNewTopicEmerges() throws IOException {
        var now = Instant.now();
        var events = new ArrayList<String>();
        // Older events: mythology (8 events to ensure topic registers)
        for (int i = 0; i < 8; i++) {
            events.add(speak("mythology books ancient greek stories", now.minus(12 - i, ChronoUnit.DAYS)));
        }
        // Recent events: quantum computing (new topic, 6 events)
        for (int i = 0; i < 6; i++) {
            events.add(speak("quantum computing research entanglement qubits", now.minus(4 - i, ChronoUnit.DAYS)));
        }
        var log = writeRawEvents(tempDir, events);

        var extractor = new TemporalPatternExtractor();
        var predictions = extractor.extract(AGENT_ID, log, 14);

        assertTrue(predictions.stream().anyMatch(p -> p.id().contains("drift")),
            "Should detect topic drift: " + predictions);
    }

    // ─── Deduplication ──────────────────────────────────────────

    @Test
    void deduplicates_samePatternsOnSecondRun() throws IOException {
        var log = writeEvents(tempDir,
            action("library_search", -5), action("library_search", -4),
            action("library_search", -3));

        var extractor = new TemporalPatternExtractor();
        var first = extractor.extract(AGENT_ID, log, 14);
        var second = extractor.extract(AGENT_ID, log, 14);

        assertFalse(first.isEmpty(), "First run should find patterns");
        assertTrue(second.isEmpty(), "Second run should deduplicate all");
    }

    @Test
    void resetSurfaced_allowsRepeatDetection() throws IOException {
        var log = writeEvents(tempDir,
            action("library_search", -5), action("library_search", -4),
            action("library_search", -3));

        var extractor = new TemporalPatternExtractor();
        extractor.extract(AGENT_ID, log, 14);
        extractor.resetSurfaced();
        var after = extractor.extract(AGENT_ID, log, 14);

        assertFalse(after.isEmpty(), "After reset, should find patterns again");
    }

    // ─── Edge Cases ─────────────────────────────────────────────

    @Test
    void emptyLog_returnsEmpty() throws IOException {
        var log = tempDir.resolve("empty.jsonl");
        Files.writeString(log, "");

        var extractor = new TemporalPatternExtractor();
        var predictions = extractor.extract(AGENT_ID, log, 14);

        assertTrue(predictions.isEmpty());
    }

    @Test
    void missingLog_returnsEmpty() {
        var extractor = new TemporalPatternExtractor();
        var predictions = extractor.extract(AGENT_ID, tempDir.resolve("nonexistent.jsonl"), 14);

        assertTrue(predictions.isEmpty());
    }

    @Test
    void nullLog_returnsEmpty() {
        var extractor = new TemporalPatternExtractor();
        var predictions = extractor.extract(AGENT_ID, null, 14);

        assertTrue(predictions.isEmpty());
    }

    @Test
    void allPredictions_haveCategoryTemporal() throws IOException {
        var log = writeEvents(tempDir,
            action("library_search", -5), action("library_search", -4),
            action("library_search", -3), action("library_search", -1));

        var extractor = new TemporalPatternExtractor();
        var predictions = extractor.extract(AGENT_ID, log, 14);

        for (var p : predictions) {
            assertEquals("temporal", p.category(), "All predictions should have category=temporal");
            assertNotNull(p.id(), "Prediction should have an ID");
            assertNotNull(p.text(), "Prediction should have text");
            assertTrue(p.confidence() >= 0.0 && p.confidence() <= 1.0,
                "Confidence should be in [0,1]");
        }
    }

    @Test
    void filtersOtherAgents_onlyIncludesTargetAgent() throws IOException {
        var now = Instant.now();
        var events = new ArrayList<String>();
        // Events from a different agent
        for (int i = 0; i < 5; i++) {
            var node = MAPPER.createObjectNode()
                .put("ts", now.minus(i, ChronoUnit.DAYS).toString())
                .put("type", "action")
                .put("agent", "Ember")
                .put("agentId", "companion-ember")
                .put("actionType", "library_search")
                .put("room", "library");
            events.add(MAPPER.writeValueAsString(node));
        }
        var log = writeRawEvents(tempDir, events);

        var extractor = new TemporalPatternExtractor();
        var predictions = extractor.extract(AGENT_ID, log, 14);

        assertTrue(predictions.isEmpty(), "Should not find patterns from other agents");
    }

    // ─── Helpers ────────────────────────────────────────────────

    private String action(String actionType, int daysAgo) {
        return action(actionType, daysAgo, 0);
    }

    private String action(String actionType, int daysAgo, int minutesOffset) {
        var ts = Instant.now().minus(-daysAgo, ChronoUnit.DAYS)
            .plus(minutesOffset, ChronoUnit.MINUTES);
        var node = MAPPER.createObjectNode()
            .put("ts", ts.toString())
            .put("type", "action")
            .put("agent", "Wyrd")
            .put("agentId", AGENT_ID)
            .put("actionType", actionType)
            .put("room", "nexus")
            .put("detail", "test");
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String event(String type, String agentId, Instant ts) {
        var node = new ObjectMapper().createObjectNode()
            .put("ts", ts.toString())
            .put("type", type)
            .put("agent", "Wyrd")
            .put("agentId", agentId)
            .put("room", "nexus")
            .put("energy", 0.5);
        try {
            return new ObjectMapper().writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String speak(String text, Instant ts) {
        var node = new ObjectMapper().createObjectNode()
            .put("ts", ts.toString())
            .put("type", "speak")
            .put("agent", "Wyrd")
            .put("agentId", AGENT_ID)
            .put("room", "nexus")
            .put("text", text);
        try {
            return new ObjectMapper().writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String message(String direction, String text, Instant ts) {
        var node = new ObjectMapper().createObjectNode()
            .put("ts", ts.toString())
            .put("type", "message")
            .put("agent", "Wyrd")
            .put("agentId", AGENT_ID)
            .put("direction", direction)
            .put("otherAgent", "anonymous")
            .put("text", text);
        try {
            return new ObjectMapper().writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Path writeEvents(Path dir, String... events) throws IOException {
        var log = dir.resolve("agent-activity.jsonl");
        Files.writeString(log, String.join("\n", events) + "\n");
        return log;
    }

    private Path writeRawEvents(Path dir, List<String> events) throws IOException {
        var log = dir.resolve("agent-activity.jsonl");
        Files.writeString(log, String.join("\n", events) + "\n");
        return log;
    }
}
