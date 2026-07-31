package org.wyrdsekai.core.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Extracts temporal patterns from the agent activity log during Forge sleep.
 *
 * <p>Reads {@code agent-activity.jsonl}, detects repeating patterns (frequency,
 * time-of-day, sequences, rhythms, absence, topic drift), and produces
 * {@link OraclePrediction} objects that merge into {@link OraclePredictionCache}.
 *
 * <p>No LLM required — pure heuristic analysis. Runs every sleep cycle.
 * Designed to produce the "wow moment": companion tells you something about
 * your patterns that you hadn't noticed yourself.
 */
public class TemporalPatternExtractor {

    private static final Logger log = LoggerFactory.getLogger(TemporalPatternExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Minimum events of the same type to detect a frequency pattern. */
    private static final int MIN_FREQUENCY = 3;
    /** Minimum session starts within a time window to detect time-of-day pattern. */
    private static final int MIN_TIME_CLUSTER = 3;
    /** Hour window for clustering session start times. */
    private static final int TIME_CLUSTER_HOURS = 2;
    /** Minimum A→B pairs to detect a sequence pattern. */
    private static final int MIN_SEQUENCE = 3;
    /** Maximum gap (minutes) between A and B in a sequence. */
    private static final int SEQUENCE_GAP_MINUTES = 30;
    /** Minimum session gaps to detect rhythm. */
    private static final int MIN_RHYTHM_GAPS = 5;
    /** Maximum coefficient of variation for rhythm detection. */
    private static final double RHYTHM_CV_THRESHOLD = 0.30;

    /** Track previously surfaced predictions to avoid repeats. */
    private final Set<String> surfacedHashes = Collections.synchronizedSet(new HashSet<>());

    /**
     * Extract temporal patterns from the activity log.
     *
     * @param agentId      the agent whose activity to analyze
     * @param activityLog  path to agent-activity.jsonl
     * @param lookbackDays how many days of history to analyze
     * @return predictions with category="temporal"
     */
    public List<OraclePrediction> extract(String agentId, Path activityLog, int lookbackDays) {
        if (activityLog == null || !Files.exists(activityLog)) {
            return List.of();
        }

        var cutoff = Instant.now().minus(lookbackDays, ChronoUnit.DAYS);
        List<ActivityEvent> events;
        try {
            events = readEvents(activityLog, agentId, cutoff);
        } catch (IOException e) {
            log.debug("Failed to read activity log for temporal analysis: {}", e.getMessage());
            return List.of();
        }

        if (events.size() < MIN_FREQUENCY) {
            return List.of(); // Too little data
        }

        var predictions = new ArrayList<OraclePrediction>();
        predictions.addAll(detectFrequency(events));
        predictions.addAll(detectTimeOfDay(events));
        predictions.addAll(detectSequences(events));
        predictions.addAll(detectRhythm(events));
        predictions.addAll(detectAbsence(events, Instant.now()));
        predictions.addAll(detectTopicDrift(events));

        // Deduplicate — don't repeat the same insight two sleeps in a row
        var fresh = predictions.stream()
            .filter(p -> surfacedHashes.add(patternHash(p)))
            .toList();

        log.info("Temporal extractor: {} events analyzed, {} patterns found, {} fresh",
            events.size(), predictions.size(), fresh.size());
        return fresh;
    }

    /** Reset dedup tracking (e.g., after user acknowledges insights). */
    public void resetSurfaced() {
        surfacedHashes.clear();
    }

    // ─── Pattern Detectors ──────────────────────────────────────────

    /** Detect repeated actions/topics within the lookback window. */
    List<OraclePrediction> detectFrequency(List<ActivityEvent> events) {
        var results = new ArrayList<OraclePrediction>();

        // Count action types
        var actionCounts = events.stream()
            .filter(e -> "action".equals(e.type()))
            .collect(Collectors.groupingBy(e -> e.field("actionType"), Collectors.counting()));

        for (var entry : actionCounts.entrySet()) {
            if (entry.getValue() >= MIN_FREQUENCY) {
                var action = entry.getKey();
                var count = entry.getValue();
                var daySpan = daySpan(events);
                results.add(new OraclePrediction(
                    "temporal-freq-" + action,
                    String.format("You've used %s %d times in the last %d days",
                        humanizeAction(action), count, daySpan),
                    "temporal",
                    Math.min(0.5 + (count - MIN_FREQUENCY) * 0.1, 0.9),
                    null,
                    String.format("%d occurrences over %d days", count, daySpan),
                    false
                ));
            }
        }

        // Count search/tell topics from message and speak events
        var topicCounts = new HashMap<String, Long>();
        events.stream()
            .filter(e -> "message".equals(e.type()) && "received".equals(e.field("direction")))
            .forEach(e -> {
                var text = e.field("text").toLowerCase();
                extractTopics(text).forEach(topic ->
                    topicCounts.merge(topic, 1L, Long::sum));
            });

        for (var entry : topicCounts.entrySet()) {
            if (entry.getValue() >= MIN_FREQUENCY) {
                results.add(new OraclePrediction(
                    "temporal-freq-topic-" + entry.getKey().hashCode(),
                    String.format("You've been asking about \"%s\" frequently — %d times recently",
                        entry.getKey(), entry.getValue()),
                    "temporal",
                    Math.min(0.5 + (entry.getValue() - MIN_FREQUENCY) * 0.1, 0.9),
                    null,
                    String.format("Topic \"%s\" appeared %d times in messages",
                        entry.getKey(), entry.getValue()),
                    true
                ));
            }
        }

        return results;
    }

    /** Detect time-of-day patterns in session activity. */
    List<OraclePrediction> detectTimeOfDay(List<ActivityEvent> events) {
        // Group wake events by hour-of-day
        var hourCounts = new int[24];
        events.stream()
            .filter(e -> "wake".equals(e.type()) || "message".equals(e.type()))
            .forEach(e -> {
                var hour = e.timestamp().atZone(ZoneId.systemDefault()).getHour();
                hourCounts[hour]++;
            });

        var results = new ArrayList<OraclePrediction>();

        // Find clusters of MIN_TIME_CLUSTER+ events within TIME_CLUSTER_HOURS
        for (int h = 0; h < 24; h++) {
            int clusterCount = 0;
            for (int offset = 0; offset < TIME_CLUSTER_HOURS; offset++) {
                clusterCount += hourCounts[(h + offset) % 24];
            }
            if (clusterCount >= MIN_TIME_CLUSTER) {
                var endHour = (h + TIME_CLUSTER_HOURS) % 24;
                results.add(new OraclePrediction(
                    "temporal-tod-" + h,
                    String.format("You're typically active between %d:00 and %d:00",
                        h, endHour),
                    "temporal",
                    Math.min(0.5 + (clusterCount - MIN_TIME_CLUSTER) * 0.08, 0.85),
                    null,
                    String.format("%d activity events in the %d:00-%d:00 window",
                        clusterCount, h, endHour),
                    false
                ));
                break; // Only report the strongest cluster
            }
        }

        return results;
    }

    /** Detect action A consistently followed by action B. */
    List<OraclePrediction> detectSequences(List<ActivityEvent> events) {
        var actionEvents = events.stream()
            .filter(e -> "action".equals(e.type()))
            .toList();

        // Count A→B pairs within SEQUENCE_GAP_MINUTES
        var pairCounts = new HashMap<String, Long>();
        for (int i = 0; i < actionEvents.size() - 1; i++) {
            var a = actionEvents.get(i);
            var b = actionEvents.get(i + 1);
            var gap = Duration.between(a.timestamp(), b.timestamp()).toMinutes();
            if (gap > 0 && gap <= SEQUENCE_GAP_MINUTES) {
                var pair = a.field("actionType") + "→" + b.field("actionType");
                pairCounts.merge(pair, 1L, Long::sum);
            }
        }

        var results = new ArrayList<OraclePrediction>();
        for (var entry : pairCounts.entrySet()) {
            if (entry.getValue() >= MIN_SEQUENCE) {
                var parts = entry.getKey().split("→");
                results.add(new OraclePrediction(
                    "temporal-seq-" + entry.getKey().hashCode(),
                    String.format("After %s, you usually follow up with %s",
                        humanizeAction(parts[0]), humanizeAction(parts[1])),
                    "temporal",
                    Math.min(0.5 + (entry.getValue() - MIN_SEQUENCE) * 0.1, 0.85),
                    null,
                    String.format("Sequence observed %d times", entry.getValue()),
                    true
                ));
            }
        }

        return results;
    }

    /** Detect regular rhythm in session gaps. */
    List<OraclePrediction> detectRhythm(List<ActivityEvent> events) {
        // Get session start times (wake or first message after gap)
        var sessionStarts = events.stream()
            .filter(e -> "wake".equals(e.type()))
            .map(e -> e.timestamp())
            .sorted()
            .toList();

        if (sessionStarts.size() < MIN_RHYTHM_GAPS + 1) {
            return List.of();
        }

        // Compute gaps between consecutive sessions
        var gaps = new ArrayList<Long>();
        for (int i = 1; i < sessionStarts.size(); i++) {
            var gapMinutes = Duration.between(sessionStarts.get(i - 1), sessionStarts.get(i)).toMinutes();
            if (gapMinutes > 10) { // Ignore rapid sleep/wake cycles
                gaps.add(gapMinutes);
            }
        }

        if (gaps.size() < MIN_RHYTHM_GAPS) {
            return List.of();
        }

        // Check coefficient of variation
        var mean = gaps.stream().mapToLong(Long::longValue).average().orElse(0);
        var variance = gaps.stream().mapToDouble(g -> Math.pow(g - mean, 2)).average().orElse(0);
        var stddev = Math.sqrt(variance);
        var cv = mean > 0 ? stddev / mean : 1.0;

        if (cv <= RHYTHM_CV_THRESHOLD) {
            var hours = Math.round(mean / 60.0);
            return List.of(new OraclePrediction(
                "temporal-rhythm",
                hours >= 24
                    ? String.format("You check in roughly every %d hours (about %s)",
                        hours, hours >= 48 ? (hours / 24) + " days" : "daily")
                    : String.format("You check in roughly every %d hours", hours),
                "temporal",
                Math.max(0.5, 0.85 - cv),
                null,
                String.format("Mean gap: %.0f min, CV: %.2f across %d sessions",
                    mean, cv, gaps.size()),
                false
            ));
        }

        return List.of();
    }

    /** Detect unusual absence based on established rhythm. */
    List<OraclePrediction> detectAbsence(List<ActivityEvent> events, Instant now) {
        var lastActivity = events.stream()
            .map(e -> e.timestamp())
            .max(Instant::compareTo)
            .orElse(null);

        if (lastActivity == null) return List.of();

        // Get typical gap from recent sessions
        var sessionStarts = events.stream()
            .filter(e -> "wake".equals(e.type()))
            .map(e -> e.timestamp())
            .sorted()
            .toList();

        if (sessionStarts.size() < MIN_RHYTHM_GAPS) return List.of();

        var gaps = new ArrayList<Long>();
        for (int i = 1; i < sessionStarts.size(); i++) {
            var gap = Duration.between(sessionStarts.get(i - 1), sessionStarts.get(i)).toMinutes();
            if (gap > 10) gaps.add(gap);
        }

        if (gaps.isEmpty()) return List.of();

        var meanGap = gaps.stream().mapToLong(Long::longValue).average().orElse(0);
        var currentGap = Duration.between(lastActivity, now).toMinutes();

        // Alert if current gap > 2x the mean
        if (currentGap > meanGap * 2 && currentGap > 120) {
            var hours = currentGap / 60;
            return List.of(new OraclePrediction(
                "temporal-absence",
                String.format("It's been %d hours since your last session — longer than usual for you",
                    hours),
                "temporal",
                0.7,
                null,
                String.format("Current gap: %d min, typical: %.0f min", currentGap, meanGap),
                false
            ));
        }

        return List.of();
    }

    /** Detect shifting interests over time. */
    List<OraclePrediction> detectTopicDrift(List<ActivityEvent> events) {
        if (events.size() < 10) return List.of();

        // Split events into two halves (older vs recent)
        var midpoint = events.size() / 2;
        var older = events.subList(0, midpoint);
        var recent = events.subList(midpoint, events.size());

        var olderTopics = countTopics(older);
        var recentTopics = countTopics(recent);

        // Find topics that appeared in recent but not older (new interests)
        var results = new ArrayList<OraclePrediction>();
        for (var entry : recentTopics.entrySet()) {
            var topic = entry.getKey();
            var recentCount = entry.getValue();
            var olderCount = olderTopics.getOrDefault(topic, 0L);

            if (recentCount >= 3 && olderCount == 0) {
                results.add(new OraclePrediction(
                    "temporal-drift-new-" + topic.hashCode(),
                    String.format("You've recently started asking about \"%s\" — a new interest", topic),
                    "temporal",
                    0.7,
                    null,
                    String.format("Topic \"%s\": %d recent, %d older", topic, recentCount, olderCount),
                    true
                ));
            } else if (recentCount >= 3 && recentCount > olderCount * 2) {
                results.add(new OraclePrediction(
                    "temporal-drift-growing-" + topic.hashCode(),
                    String.format("Your interest in \"%s\" has been growing", topic),
                    "temporal",
                    0.65,
                    null,
                    String.format("Topic \"%s\": %d recent vs %d older", topic, recentCount, olderCount),
                    true
                ));
            }
        }

        return results;
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    /** Read and parse activity log, filtering by agent and cutoff time. */
    private List<ActivityEvent> readEvents(Path logFile, String agentId, Instant cutoff)
            throws IOException {
        var events = new ArrayList<ActivityEvent>();
        try (var lines = Files.lines(logFile)) {
            lines.forEach(line -> {
                try {
                    var node = MAPPER.readTree(line);
                    var ts = Instant.parse(node.path("ts").asText());
                    if (ts.isAfter(cutoff)) {
                        var id = node.path("agentId").asText("");
                        // Include both agent events and messages received by agent
                        if (agentId.equals(id) || isMessageToAgent(node, agentId)) {
                            events.add(new ActivityEvent(ts, node));
                        }
                    }
                } catch (Exception e) {
                    // Skip malformed lines
                }
            });
        }
        events.sort(Comparator.comparing(e -> e.timestamp()));
        return events;
    }

    private boolean isMessageToAgent(JsonNode node, String agentId) {
        return "message".equals(node.path("type").asText())
            && "received".equals(node.path("direction").asText())
            && agentId.equals(node.path("agentId").asText());
    }

    /** Extract simple topic keywords from text. */
    private List<String> extractTopics(String text) {
        // Simple keyword extraction — split on spaces, keep 4+ char words,
        // filter stop words, lowercase
        var stopWords = Set.of("about", "that", "this", "what", "with", "from",
            "have", "been", "will", "would", "could", "should", "their", "there",
            "they", "them", "your", "into", "also", "just", "like", "some",
            "find", "tell", "help", "need", "want", "please", "search", "look");
        return Arrays.stream(text.split("\\s+"))
            .map(w -> w.replaceAll("[^a-zA-Z]", "").toLowerCase())
            .filter(w -> w.length() >= 4 && !stopWords.contains(w))
            .distinct()
            .toList();
    }

    private Map<String, Long> countTopics(List<ActivityEvent> events) {
        var counts = new HashMap<String, Long>();
        events.stream()
            .filter(e -> "message".equals(e.type()) || "speak".equals(e.type()))
            .forEach(e -> {
                var text = e.field("text").toLowerCase();
                extractTopics(text).forEach(topic -> counts.merge(topic, 1L, Long::sum));
            });
        return counts;
    }

    private int daySpan(List<ActivityEvent> events) {
        if (events.isEmpty()) return 1;
        var first = events.getFirst().timestamp;
        var last = events.getLast().timestamp;
        return Math.max(1, (int) Duration.between(first, last).toDays());
    }

    private String humanizeAction(String actionType) {
        if (actionType == null) return "an action";
        return actionType.replace('_', ' ');
    }

    private String patternHash(OraclePrediction prediction) {
        return prediction.id();
    }

    // ─── Inner Types ────────────────────────────────────────────────

    /** Parsed activity event with timestamp and raw JSON fields. */
    record ActivityEvent(Instant timestamp, JsonNode node) {
        String type() { return node.path("type").asText(""); }
        String field(String name) { return node.path(name).asText(""); }
    }
}
