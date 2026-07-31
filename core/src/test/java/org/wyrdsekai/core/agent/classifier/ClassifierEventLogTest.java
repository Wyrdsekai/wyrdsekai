package org.wyrdsekai.core.agent.classifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class ClassifierEventLogTest {

    @Test void forAgent_creates_dir_and_returns_log(@TempDir Path dir) {
        var subdir = dir.resolve("agent");
        var eventLog = ClassifierEventLog.forAgent(subdir);
        assertNotNull(eventLog);
        assertTrue(Files.isDirectory(subdir));
    }

    @Test void record_appends_jsonl_line(@TempDir Path dir) throws Exception {
        var eventLog = ClassifierEventLog.forAgent(dir);
        eventLog.record(new ClassifierEventLog.Event(
            Instant.parse("2026-04-22T12:00:00Z"),
            "REQUEST_TYPE", "hello", "chat", 0.92, "L1"));
        eventLog.record(new ClassifierEventLog.Event(
            Instant.parse("2026-04-22T12:00:01Z"),
            "REQUEST_TYPE", "go research MUDs deeply", "delegate", 0.88, "L1"));

        var lines = Files.readAllLines(dir.resolve("events.jsonl"));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"head\":\"REQUEST_TYPE\""));
        assertTrue(lines.get(0).contains("\"label\":\"chat\""));
        assertTrue(lines.get(1).contains("\"label\":\"delegate\""));
    }

    @Test void record_null_event_is_noop(@TempDir Path dir) {
        var eventLog = ClassifierEventLog.forAgent(dir);
        eventLog.record(null);
        // File should not exist if no events recorded
        assertFalse(Files.exists(dir.resolve("events.jsonl")));
    }

    @Test void rotate_moves_to_consumed_file_and_read_roundtrips(@TempDir Path dir)
            throws Exception {
        var eventLog = ClassifierEventLog.forAgent(dir);
        eventLog.record(new ClassifierEventLog.Event(
            Instant.parse("2026-04-22T12:00:00Z"),
            "CLEANLINESS", "I have examined the message", "leaky", 0.93, "L1"));

        var rotated = eventLog.rotate();
        assertNotNull(rotated);
        assertTrue(Files.exists(rotated));
        assertFalse(Files.exists(dir.resolve("events.jsonl")));

        var events = ClassifierEventLog.read(rotated);
        assertEquals(1, events.size());
        assertEquals("CLEANLINESS", events.get(0).head());
        assertEquals("leaky", events.get(0).label());
        assertEquals(0.93, events.get(0).confidence(), 0.001);
    }

    @Test void rotate_with_no_log_returns_null(@TempDir Path dir) {
        var eventLog = ClassifierEventLog.forAgent(dir);
        assertNull(eventLog.rotate());
    }

    @Test void read_skips_malformed_lines(@TempDir Path dir) throws Exception {
        var path = dir.resolve("bad.jsonl");
        Files.writeString(path,
            "{\"ts\":\"2026-04-22T12:00:00Z\",\"head\":\"REQUEST_TYPE\","
                + "\"text\":\"ok\",\"label\":\"chat\",\"confidence\":0.9}\n"
                + "not valid json\n"
                + "{\"ts\":\"2026-04-22T12:00:01Z\",\"head\":\"CLEANLINESS\","
                + "\"text\":\"yes\",\"label\":\"clean\",\"confidence\":0.88}\n");

        var events = ClassifierEventLog.read(path);
        assertEquals(2, events.size());
        assertEquals("chat", events.get(0).label());
        assertEquals("clean", events.get(1).label());
    }

    @Test void read_of_missing_file_returns_empty(@TempDir Path dir) {
        var events = ClassifierEventLog.read(dir.resolve("missing.jsonl"));
        assertTrue(events.isEmpty());
    }

    @Test void record_returns_stable_event_id(@TempDir Path dir) throws Exception {
        var eventLog = ClassifierEventLog.forAgent(dir);
        var id = eventLog.record(new ClassifierEventLog.Event(
            Instant.now(), "REQUEST_TYPE", "hello", "chat", 0.9, "L1"));
        assertNotNull(id);
        assertTrue(id.length() > 10, "should be a uuid-ish string");
        var content = Files.readString(eventLog.logPath());
        assertTrue(content.contains("\"id\":\"" + id + "\""));
    }

    @Test void mark_outcome_appends_delta_line(@TempDir Path dir) throws Exception {
        var eventLog = ClassifierEventLog.forAgent(dir);
        var id = eventLog.record(new ClassifierEventLog.Event(
            Instant.now(), "REQUEST_TYPE", "go research MUDs", "delegate", 0.92, "L1"));
        eventLog.markOutcome(id, ClassifierEventLog.Outcome.POSITIVE, "goal_done");
        var lines = Files.readAllLines(eventLog.logPath());
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("\"type\":\"outcome\""));
        assertTrue(lines.get(1).contains("\"outcome\":\"POSITIVE\""));
        assertTrue(lines.get(1).contains(id));
    }

    @Test void read_applies_outcome_deltas(@TempDir Path dir) throws Exception {
        var eventLog = ClassifierEventLog.forAgent(dir);
        var idPositive = eventLog.record(new ClassifierEventLog.Event(
            Instant.now(), "REQUEST_TYPE", "positive event", "chat", 0.9, "L1"));
        var idNegative = eventLog.record(new ClassifierEventLog.Event(
            Instant.now(), "REQUEST_TYPE", "negative event", "factual", 0.85, "L1"));
        eventLog.record(new ClassifierEventLog.Event(
            Instant.now(), "REQUEST_TYPE", "unknown event", "chat", 0.88, "L1"));
        eventLog.markOutcome(idPositive, ClassifierEventLog.Outcome.POSITIVE, "goal_done");
        eventLog.markOutcome(idNegative, ClassifierEventLog.Outcome.NEGATIVE, "abort");

        var rotated = eventLog.rotate();
        var events = ClassifierEventLog.read(rotated);
        assertEquals(3, events.size());
        var byText = new HashMap<String, ClassifierEventLog.Event>();
        for (var e : events) byText.put(e.text(), e);

        assertEquals(ClassifierEventLog.Outcome.POSITIVE,
            byText.get("positive event").outcome());
        assertEquals(ClassifierEventLog.Outcome.NEGATIVE,
            byText.get("negative event").outcome());
        assertEquals(ClassifierEventLog.Outcome.UNKNOWN,
            byText.get("unknown event").outcome(),
            "unmarked events default to UNKNOWN");
    }

    @Test void write_after_rotate_starts_fresh_file(@TempDir Path dir) throws Exception {
        var eventLog = ClassifierEventLog.forAgent(dir);
        eventLog.record(new ClassifierEventLog.Event(
            Instant.now(), "REQUEST_TYPE", "first", "chat", 0.9, "L1"));
        eventLog.rotate();
        eventLog.record(new ClassifierEventLog.Event(
            Instant.now(), "REQUEST_TYPE", "second", "factual", 0.85, "L1"));

        var current = Files.readAllLines(dir.resolve("events.jsonl"));
        assertEquals(1, current.size(),
            "rotation should yield a fresh empty file that the next record opens");
        assertTrue(current.get(0).contains("\"text\":\"second\""));
    }
}
