package org.wyrdsekai.core.agent.classifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only log of classification events for a single companion.
 *
 * <p>Every time the classifier answers, we record (head, text, label,
 * confidence, timestamp). The log is the raw material the Forge cycle feeds
 * on during sleep — high-confidence events become pseudo-labeled training
 * data; low-confidence events are ignored until a Layer-2 signal arrives.
 *
 * <p>Layout:
 * <pre>
 *   ~/.wyrdsekai/classifiers/&lt;did&gt;/events.jsonl
 * </pre>
 *
 * <p>Each line is one JSON object. Writes are line-level and synchronized so
 * concurrent classify calls don't interleave bytes. Rotation happens at Forge
 * time (the event file is consumed and renamed to {@code events.consumed-TS.jsonl}
 * so a concurrent classifier can keep writing to a fresh file without loss).
 */
public final class ClassifierEventLog {

    private static final Logger log = LoggerFactory.getLogger(ClassifierEventLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Outcome signal attached to a prior classification. Used by the Forge
     * pass to break the pseudo-label circularity: POSITIVE events reinforce
     * (become pseudo-labels), NEGATIVE events are excluded from the corpus,
     * UNKNOWN falls back to confidence-only rules.
     */
    public enum Outcome { POSITIVE, NEGATIVE, UNKNOWN }

    /**
     * Classification event record. Text is retained raw — the Forge pass
     * decides whether to sanitize / dedupe it before merging into a training
     * corpus.
     *
     * @param eventId UUID assigned at record time; callers use it to later
     *                attach an {@link Outcome} via {@link #markOutcome}.
     */
    public record Event(
        String eventId,        // UUID, stable across outcome marking
        Instant timestamp,
        String head,           // REQUEST_TYPE, CLEANLINESS, ...
        String text,
        String label,          // classifier's predicted label
        double confidence,
        String source,         // "L1" (classical) or "L2" (LLM fallback — future)
        Outcome outcome        // UNKNOWN at write time; resolved by a later markOutcome
    ) {
        /** Back-compat ctor — assigns a fresh UUID and UNKNOWN outcome. */
        public Event(Instant timestamp, String head, String text, String label,
                     double confidence, String source) {
            this(UUID.randomUUID().toString(), timestamp, head, text,
                label, confidence, source, Outcome.UNKNOWN);
        }
    }

    /**
     * Outcome delta record — appended to the log when a routing decision's
     * result becomes known. Forge replays the log, collects Outcome deltas
     * by eventId, and applies them to the corresponding Event.
     */
    public record OutcomeRecord(
        Instant timestamp,
        String eventId,        // references Event.eventId
        Outcome outcome,
        String note            // free-form: "goal_done:success", "abort:user-cancel", ...
    ) {}

    private final Path logPath;
    private final Object writeLock = new Object();

    private ClassifierEventLog(Path logPath) {
        this.logPath = logPath;
    }

    /** Create a log rooted at this agent's classifier directory. */
    public static ClassifierEventLog forAgent(Path perAgentDir) {
        try {
            Files.createDirectories(perAgentDir);
        } catch (IOException e) {
            log.warn("Failed to create classifier event log dir {}: {}",
                perAgentDir, e.getMessage());
            return null;
        }
        return new ClassifierEventLog(perAgentDir.resolve("events.jsonl"));
    }

    /**
     * Append one classification event. Fail-safe: IO errors are logged but
     * never propagate — a missing event is less bad than a crashed classifier.
     * Returns the event UUID so callers can later call {@link #markOutcome}.
     */
    public String record(Event event) {
        if (event == null) return null;
        // Auto-assign UUID if caller used the legacy ctor path that left it null.
        var eventId = event.eventId() != null
            ? event.eventId()
            : UUID.randomUUID().toString();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("v", 1);   // line-schema version (data-durability, 2026-07-09)
        payload.put("type", "event");
        payload.put("id", eventId);
        payload.put("ts", event.timestamp().toString());
        payload.put("head", event.head());
        payload.put("text", event.text());
        payload.put("label", event.label() == null ? "" : event.label());
        payload.put("confidence", event.confidence());
        payload.put("source", event.source() == null ? "L1" : event.source());
        payload.put("outcome", (event.outcome() == null
            ? Outcome.UNKNOWN : event.outcome()).name());
        try {
            var line = MAPPER.writeValueAsString(payload) + "\n";
            synchronized (writeLock) {
                Files.writeString(logPath, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.debug("Event log write failed ({}): {}", logPath, e.getMessage());
        }
        return eventId;
    }

    /**
     * Attach an outcome to a prior classification event. Forge replays both
     * event and outcome records, applies deltas by eventId, and uses the
     * resolved outcome to decide whether a classification becomes a
     * pseudo-label (POSITIVE), gets excluded (NEGATIVE), or falls back to
     * confidence-only rules (UNKNOWN).
     *
     * <p>Fail-safe: IO errors are swallowed. A missing outcome degrades to
     * UNKNOWN, which is the current circular pseudo-label behavior — not
     * worse than before.
     */
    public void markOutcome(String eventId, Outcome outcome, String note) {
        if (eventId == null || outcome == null) return;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("v", 1);   // line-schema version (data-durability, 2026-07-09)
        payload.put("type", "outcome");
        payload.put("ts", Instant.now().toString());
        payload.put("id", eventId);
        payload.put("outcome", outcome.name());
        payload.put("note", note == null ? "" : note);
        try {
            var line = MAPPER.writeValueAsString(payload) + "\n";
            synchronized (writeLock) {
                Files.writeString(logPath, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.debug("Outcome log write failed ({}): {}", logPath, e.getMessage());
        }
    }

    /**
     * Rotate the log by renaming the active file with a timestamp suffix, so
     * the consumer (Forge) can read the snapshot while new classifications
     * continue writing to a fresh {@code events.jsonl}. Returns the path of
     * the rotated file, or null if rotation failed or no events existed.
     */
    public Path rotate() {
        synchronized (writeLock) {
            if (!Files.exists(logPath)) return null;
            var stamp = Instant.now().toString().replace(':', '-');
            var rotated = logPath.resolveSibling("events.consumed-" + stamp + ".jsonl");
            try {
                Files.move(logPath, rotated);
                return rotated;
            } catch (IOException e) {
                log.warn("Event log rotate failed: {}", e.getMessage());
                return null;
            }
        }
    }

    /**
     * Read events from a rotated log file, with outcome deltas applied.
     * Outcome records (type=outcome) are merged into matching events by
     * eventId. Events without a matching outcome keep {@link Outcome#UNKNOWN}.
     * Malformed lines are skipped.
     */
    public static List<Event> read(Path rotatedPath) {
        if (rotatedPath == null || !Files.exists(rotatedPath)) return List.of();
        var events = new LinkedHashMap<String, Event>();
        var outcomes = new HashMap<String, Outcome>();
        try (var lines = Files.lines(rotatedPath, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                if (line == null || line.isBlank()) return;
                try {
                    var node = MAPPER.readTree(line);
                    var type = node.path("type").asText("event");
                    if ("outcome".equals(type)) {
                        var id = node.path("id").asText("");
                        var o = node.path("outcome").asText("UNKNOWN");
                        if (!id.isEmpty()) {
                            outcomes.put(id, Outcome.valueOf(o));
                        }
                        return;
                    }
                    // event line (type=event, or legacy line without type)
                    var ts = node.has("ts") ? Instant.parse(node.get("ts").asText()) : Instant.now();
                    var id = node.path("id").asText(UUID.randomUUID().toString());
                    var head = node.path("head").asText("");
                    var text = node.path("text").asText("");
                    var label = node.path("label").asText("");
                    var conf = node.path("confidence").asDouble(0.0);
                    var source = node.path("source").asText("L1");
                    var outcomeStr = node.path("outcome").asText("UNKNOWN");
                    Outcome outcome;
                    try { outcome = Outcome.valueOf(outcomeStr); }
                    catch (Exception ex2) { outcome = Outcome.UNKNOWN; }
                    events.put(id, new Event(id, ts, head, text, label, conf, source, outcome));
                } catch (Exception ex) {
                    // Skip malformed lines — the log is append-only and
                    // a partial write or future schema tweak shouldn't
                    // poison the whole batch.
                }
            });
        } catch (IOException e) {
            log.warn("Event log read failed ({}): {}", rotatedPath, e.getMessage());
        }
        // Apply outcome deltas to matching events.
        var resolved = new ArrayList<Event>(events.size());
        for (var e : events.values()) {
            var o = outcomes.getOrDefault(e.eventId(), e.outcome());
            resolved.add(new Event(e.eventId(), e.timestamp(), e.head(), e.text(),
                e.label(), e.confidence(), e.source(), o));
        }
        return resolved;
    }

    /** Path exposed for tests. */
    public Path logPath() {
        return logPath;
    }
}
