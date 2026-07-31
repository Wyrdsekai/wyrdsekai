package org.wyrdsekai.core.agent.interiority;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * replays the {@code agent-activity.jsonl} tick log.
 *
 * <p>Used by:
 * <ul>
 *   <li>{@link ChronicleService}     — to assemble the synthesis narrative
 *   <li>{@link DoomLoopDetector}     — to spot quantitative pathology patterns
 *   <li>Study chronicle furnishing   — to render readable summaries
 * </ul>
 *
 * <p>Streams the log line-by-line so it stays cheap for long-lived agents. The
 * reader filters in-memory by agent DID + a time window before parsing the
 * payload, so most lines are rejected with one JSON token decode.
 */
public final class TickLogReader {

    private static final Logger log = LoggerFactory.getLogger(TickLogReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path logFile;

    public TickLogReader(Path logFile) {
        this.logFile = logFile;
    }

    /** Default: ~/.wyrdsekai/data/agent-activity.jsonl. */
    public static TickLogReader defaultLocation() {
        var home = System.getProperty("user.home", ".");
        return new TickLogReader(Path.of(home, ".wyrdsekai", "data", "agent-activity.jsonl"));
    }

    /**
     * All {@code tick} events for an agent within {@code [since, now]}, newest
     * line last (file order is append-order). Returns empty list on any IO error
     * or missing file.
     */
    public List<TickEvent> readTicks(String agentDid, Instant since) {
        var out = new ArrayList<TickEvent>();
        if (logFile == null || agentDid == null) return out;
        if (!Files.exists(logFile)) return out;
        try (BufferedReader r = Files.newBufferedReader(logFile)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                if (!line.contains("\"type\":\"tick\"")) continue;
                if (!line.contains(agentDid)) continue;
                try {
                    var node = MAPPER.readTree(line);
                    if (!"tick".equals(text(node, "type"))) continue;
                    if (!agentDid.equals(text(node, "agentId"))) continue;
                    var ts = Instant.parse(text(node, "ts"));
                    if (since != null && ts.isBefore(since)) continue;
                    out.add(toEvent(ts, node));
                } catch (Exception parseErr) {
                    // Skip the bad line, keep streaming.
                }
            }
        } catch (IOException e) {
            log.debug("readTicks({}) failed: {}", agentDid, e.getMessage());
        }
        return out;
    }

    /** All non-tick events for an agent in window — useful for the chronicle's testimony. */
    public List<RawEvent> readNonTickEvents(String agentDid, Instant since) {
        var out = new ArrayList<RawEvent>();
        if (logFile == null || agentDid == null) return out;
        if (!Files.exists(logFile)) return out;
        try (BufferedReader r = Files.newBufferedReader(logFile)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                if (line.contains("\"type\":\"tick\"")) continue;
                if (!line.contains(agentDid)) continue;
                try {
                    var node = MAPPER.readTree(line);
                    if (!agentDid.equals(text(node, "agentId"))) continue;
                    var ts = Instant.parse(text(node, "ts"));
                    if (since != null && ts.isBefore(since)) continue;
                    out.add(new RawEvent(ts, text(node, "type"), node));
                } catch (Exception parseErr) {
                    // skip bad line
                }
            }
        } catch (IOException e) {
            log.debug("readNonTickEvents({}) failed: {}", agentDid, e.getMessage());
        }
        return out;
    }

    private TickEvent toEvent(Instant ts, JsonNode n) {
        var driveMap = new LinkedHashMap<String, Double>();
        if (n.has("driveSnapshot") && n.get("driveSnapshot").isObject()) {
            n.get("driveSnapshot").fields().forEachRemaining(e -> {
                if (e.getValue().isNumber()) driveMap.put(e.getKey(), e.getValue().asDouble());
            });
        }
        return new TickEvent(
            ts,
            text(n, "agent"),
            text(n, "agentId"),
            driveMap,
            n.has("energy") ? n.get("energy").asDouble() : 0,
            text(n, "gateOutcome"),
            text(n, "chosenWant"),
            text(n, "chosenWantText"),
            text(n, "actionVerb"),
            text(n, "actionResult"),
            stringList(n.get("candidateWants")),
            stringList(n.get("memoryPulls")),
            n.has("nextTickDelaySeconds") ? n.get("nextTickDelaySeconds").asLong() : 0,
            n.has("tickDurationMs") ? n.get("tickDurationMs").asLong() : 0);
    }

    private static List<String> stringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        var out = new ArrayList<String>(arr.size());
        arr.forEach(v -> { if (v.isTextual()) out.add(v.asText()); });
        return out;
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field)) return null;
        var v = n.get(field);
        return v.isNull() ? null : v.asText();
    }

    /**
     * Decoded tick event row — see {@link ActivityLogger.TickRecord} for the
     * write-side schema.
     */
    public record TickEvent(
        Instant ts,
        String agentName,
        String agentId,
        Map<String, Double> driveSnapshot,
        double energy,
        String gateOutcome,
        String chosenWantId,
        String chosenWantText,
        String actionVerb,
        String actionResult,
        List<String> candidateWants,
        List<String> memoryPulls,
        long nextTickDelaySeconds,
        long tickDurationMs
    ) {}

    /** Decoded non-tick event — preserves raw JSON for callers that need detail. */
    public record RawEvent(Instant ts, String type, JsonNode payload) {}

    /** Compute time-span of the window we just read. */
    public static Duration windowDuration(Instant since) {
        return Duration.between(since == null ? Instant.EPOCH : since, Instant.now());
    }
}
