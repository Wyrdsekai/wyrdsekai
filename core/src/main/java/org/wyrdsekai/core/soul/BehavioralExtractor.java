package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.event.WorldEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Three-pass behavioral extraction pipeline (section 85.3).
 *
 * Extraction is periodic, not real-time — runs during the sleep cycle.
 * This mirrors biological sleep: consolidation happens offline.
 *
 * Pass 1 (heuristic, instant, free): Action type distribution, response
 *   timing, vitality averages/stddev (12 tanks), event presence vs response.
 * Pass 2 (LLM, one inference call): Topic affinities, relationship summaries,
 *   avoidance patterns, stylistic markers, negative space interpretation.
 * Pass 3 (fragment extraction): Handled by SoulFragmentExtractor separately.
 *
 * The extractor is infrastructure-agnostic for Pass 2:
 * builds prompts and parses responses, caller handles inference.
 */
public final class BehavioralExtractor {

    private static final Logger log = LoggerFactory.getLogger(BehavioralExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BehavioralExtractor() {}

    /**
     * Pass 1: Heuristic extraction (free, instant, no LLM).
     * Produces a rough fingerprint from event statistics.
     *
     * @param agentEntityId  Agent's entity ID
     * @param events         Agent's events from the journal
     * @param vitalityHistory Vitality snapshots over time
     * @param roomEvents     All events in rooms the agent occupied (for negative space)
     * @return Heuristic-only BehavioralFingerprint
     */
    public static BehavioralFingerprint extractHeuristic(String agentEntityId,
                                                           List<WorldEvent> events,
                                                           List<VitalitySnapshot> vitalityHistory,
                                                           List<WorldEvent> roomEvents) {
        // Action type distribution
        Map<String, Float> actionDist = computeActionDistribution(events);

        // Response timing profile
        float avgLatency = computeAverageLatency(agentEntityId, events);

        // Average response length (from Said events)
        float avgLength = computeAverageResponseLength(agentEntityId, events);

        // Vitality baselines and derivatives (12 tanks)
        Map<String, Float> vitalityBaseline = computeVitalityBaseline(vitalityHistory);
        Map<String, Float> vitalityDerivatives = computeVitalityDerivatives(vitalityHistory);

        // Negative space analysis
        var negativeSpace = NegativeSpaceAnalyzer.analyze(agentEntityId, events, roomEvents);

        // Avoidance from negative space
        Map<String, Float> avoidance = new LinkedHashMap<>();
        negativeSpace.topicSilences().forEach((topic, count) ->
            avoidance.put(topic, Math.min(1.0f, count / 10.0f)));

        return new BehavioralFingerprint(
            vitalityBaseline,
            vitalityDerivatives,
            Map.of(),      // observedSensitivity filled by genome drift detection
            actionDist,
            Map.of(),      // topicAffinities filled by Pass 2 (LLM)
            Map.copyOf(avoidance),
            avgLength,
            avgLatency,
            List.of(),     // stylisticMarkers filled by Pass 2 (LLM)
            Map.of()       // emotionalResponseProfile filled by Pass 2 (LLM)
        );
    }

    /**
     * Build the system prompt for Pass 2 (LLM extraction).
     */
    public static String pass2SystemPrompt() {
        return """
            You are a behavioral analyst. Given an agent's event history and statistical profile,
            extract deeper behavioral patterns that heuristics cannot capture.

            RESPOND WITH ONLY A JSON OBJECT. No explanation, no markdown, no prose.
            If there is insufficient data, return empty collections.

            Required format:
            {"topicAffinities": {"topic": 0.0-1.0}, "stylisticMarkers": ["marker1"], "emotionalResponseProfile": {"emotion": 0.0-1.0}, "additionalAvoidance": {"pattern": 0.0-1.0}}
            """;
    }

    /**
     * Build the user prompt for Pass 2 with the agent's event summary.
     *
     * @param heuristic Pass 1 results (for context)
     * @param events    Recent events to analyze (limit to ~50 for token budget)
     * @return User prompt string
     */
    public static String pass2UserPrompt(BehavioralFingerprint heuristic,
                                           List<WorldEvent> events) {
        var sb = new StringBuilder();
        sb.append("Agent behavioral statistics:\n");
        sb.append("- Action distribution: ").append(heuristic.actionDistribution()).append("\n");
        sb.append("- Avg response length: ").append(heuristic.averageResponseLength()).append(" tokens\n");
        sb.append("- Known avoidances: ").append(heuristic.avoidancePatterns()).append("\n\n");

        sb.append("Recent events (most recent first):\n");
        events.stream()
            .sorted(Comparator.comparing(WorldEvent::timestamp).reversed())
            .limit(50)
            .forEach(event -> {
                if (event instanceof WorldEvent.Said said) {
                    sb.append("[").append(said.entityName()).append("] ").append(said.text()).append("\n");
                }
            });

        sb.append("\nAnalyze the above and provide topic affinities, stylistic markers, ");
        sb.append("emotional response profile, and any avoidance patterns not captured by statistics.");
        sb.append(" /no_think");  // Suppress Qwen3 thinking mode for structured JSON output
        return sb.toString();
    }

    /**
     * Merge Pass 2 LLM results into the heuristic fingerprint.
     *
     * @param heuristic    Pass 1 heuristic fingerprint
     * @param llmResponse  Raw LLM response from Pass 2
     * @return Enhanced fingerprint with LLM-derived insights
     */
    public static BehavioralFingerprint mergePass2(BehavioralFingerprint heuristic,
                                                      String llmResponse) {
        Map<String, Float> topicAffinities = new LinkedHashMap<>();
        List<String> stylisticMarkers = new ArrayList<>();
        Map<String, Float> emotionalResponse = new LinkedHashMap<>();
        Map<String, Float> avoidance = new LinkedHashMap<>(heuristic.avoidancePatterns());

        try {
            String json = repairJson(llmResponse);
            log.debug("[Forge] Pass 2 repaired JSON: {}", json.length() > 500 ? json.substring(0, 500) + "..." : json);
            JsonNode node = MAPPER.readTree(json);

            JsonNode topics = node.path("topicAffinities");
            if (topics.isObject()) {
                topics.fields().forEachRemaining(e ->
                    topicAffinities.put(e.getKey(), (float) e.getValue().asDouble(0.0)));
            }

            JsonNode markers = node.path("stylisticMarkers");
            if (markers.isArray()) {
                markers.forEach(m -> stylisticMarkers.add(m.asText()));
            }

            JsonNode emotions = node.path("emotionalResponseProfile");
            if (emotions.isObject()) {
                emotions.fields().forEachRemaining(e ->
                    emotionalResponse.put(e.getKey(), (float) e.getValue().asDouble(0.0)));
            }

            JsonNode addAvoid = node.path("additionalAvoidance");
            if (addAvoid.isObject()) {
                addAvoid.fields().forEachRemaining(e ->
                    avoidance.put(e.getKey(), (float) e.getValue().asDouble(0.0)));
            }
        } catch (Exception e) {
            log.warn("[Forge] Pass 2 JSON parse failed (using heuristic fields): {}", e.getMessage());
        }

        return new BehavioralFingerprint(
            heuristic.baselineVitality(),
            heuristic.baselineDerivatives(),
            heuristic.observedSensitivity(),
            heuristic.actionDistribution(),
            Map.copyOf(topicAffinities),
            Map.copyOf(avoidance),
            heuristic.averageResponseLength(),
            heuristic.responseLatencyProfile(),
            List.copyOf(stylisticMarkers),
            Map.copyOf(emotionalResponse)
        );
    }

    /**
     * Full extraction: Pass 1 + Pass 2 combined.
     *
     * @param agentEntityId   Agent entity ID
     * @param events          Agent's events
     * @param vitalityHistory Vitality snapshots
     * @param roomEvents      All room events
     * @param infer           (systemPrompt, userPrompt) -> LLM response
     * @return Full BehavioralFingerprint
     */
    public static BehavioralFingerprint extract(String agentEntityId,
                                                  List<WorldEvent> events,
                                                  List<VitalitySnapshot> vitalityHistory,
                                                  List<WorldEvent> roomEvents,
                                                  BiFunction<String, String, String> infer) {
        log.info("[Forge] BehavioralExtractor Pass 1 (heuristic): {} events, {} vitality snapshots",
            events.size(), vitalityHistory != null ? vitalityHistory.size() : 0);
        var heuristic = extractHeuristic(agentEntityId, events, vitalityHistory, roomEvents);
        log.info("[Forge] Pass 1 result: actions={}, avgLength={}, avgLatency={}s, avoidances={}",
            heuristic.actionDistribution(), String.format("%.1f", heuristic.averageResponseLength()),
            String.format("%.1f", heuristic.responseLatencyProfile()),
            heuristic.avoidancePatterns().size());

        if (infer != null) {
            log.info("[Forge] BehavioralExtractor Pass 2 (LLM): sending inference request");
            String sys = pass2SystemPrompt();
            String user = pass2UserPrompt(heuristic, events);
            try {
                String response = infer.apply(sys, user);
                log.info("[Forge] Pass 2 LLM response received ({} chars)", response != null ? response.length() : 0);
                var merged = mergePass2(heuristic, response);
                log.info("[Forge] Pass 2 merged: {} topics, {} markers, {} emotional profiles",
                    merged.topicAffinities().size(), merged.stylisticMarkers().size(),
                    merged.emotionalResponseProfile().size());
                return merged;
            } catch (Exception e) {
                log.warn("[Forge] Pass 2 LLM inference failed, using heuristic only: {}", e.getMessage());
                return heuristic;
            }
        }

        log.info("[Forge] Pass 2 skipped (no inference function), returning heuristic-only fingerprint");
        return heuristic;
    }

    // --- JSON repair for LLM responses ---

    /**
     * Repair common LLM JSON issues: prose wrapping, unquoted keys, single quotes,
     * trailing commas, markdown fences, thinking blocks.
     */
    static String repairJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";

        String s = raw;

        // Strip <think>...</think> or /think blocks (Qwen thinking mode)
        s = s.replaceAll("(?s)<think>.*?</think>", "").trim();

        // Strip markdown code fences
        s = s.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();

        // Extract JSON object from surrounding prose
        int braceStart = s.indexOf('{');
        int braceEnd = s.lastIndexOf('}');
        if (braceStart < 0 || braceEnd <= braceStart) {
            log.debug("[Forge] No JSON object found in LLM response ({} chars)", raw.length());
            return "{}";
        }
        s = s.substring(braceStart, braceEnd + 1);

        // Fix unquoted keys: word followed by colon → "word":
        // Matches: { key: or , key: or \n key: — but not inside strings
        s = s.replaceAll("(?m)([{,\\n])\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*:", "$1\"$2\":");

        // Fix single-quoted strings → double-quoted
        // Only replace single quotes that look like string delimiters (after : or in arrays)
        s = s.replaceAll("(?<=:\\s*)'([^']*)'", "\"$1\"");
        s = s.replaceAll("(?<=\\[\\s*)'([^']*)'", "\"$1\"");
        s = s.replaceAll("(?<=,\\s*)'([^']*)'", "\"$1\"");

        // Remove trailing commas before } or ]
        s = s.replaceAll(",\\s*([}\\]])", "$1");

        // Remove JavaScript-style comments
        s = s.replaceAll("//[^\n]*", "");

        return s;
    }

    // --- Knowledge Provenance ---

    /**
     * Detect whether a Said event was informed by Library research.
     * Heuristic: looks for citation patterns like "[Source: ...]", "According to ...",
     * "I found in the Library...", knowledge chunk IDs, etc.
     *
     * @param text The said event text
     * @return true if the event appears to cite Library knowledge
     */
    public static boolean hasKnowledgeProvenance(String text) {
        if (text == null) return false;
        var lower = text.toLowerCase();
        return lower.contains("[source:") || lower.contains("according to")
            || lower.contains("i found") || lower.contains("the library")
            || lower.contains("wikipedia") || lower.contains("stackexchange")
            || lower.contains("medquad") || lower.contains("[wikihow]")
            || text.contains("relevance:") || text.matches(".*\\w+:\\d+.*"); // chunk ID pattern
    }

    /**
     * Extract knowledge source references from a Said event text.
     * Returns source names/IDs found in the text.
     */
    public static List<String> extractProvenanceSources(String text) {
        if (text == null) return List.of();
        var sources = new ArrayList<String>();

        // Look for [Source: X] patterns
        var matcher = Pattern.compile("\\[(?:Source|source):\\s*([^\\]]+)\\]")
            .matcher(text);
        while (matcher.find()) {
            sources.add(matcher.group(1).trim());
        }

        // Look for pack:chunkId patterns
        var chunkMatcher = Pattern.compile("\\b(\\w+):(\\d+)\\b")
            .matcher(text);
        while (chunkMatcher.find()) {
            sources.add(chunkMatcher.group(0));
        }

        return sources;
    }

    // --- Heuristic computation methods ---

    private static Map<String, Float> computeActionDistribution(List<WorldEvent> events) {
        Map<String, Integer> counts = new HashMap<>();
        for (var event : events) {
            String type = switch (event) {
                case WorldEvent.Said s -> "say";
                case WorldEvent.EntityEntered e -> "move";
                case WorldEvent.ObjectUsed u -> "use";
                case WorldEvent.ObjectTaken t -> "take";
                case WorldEvent.ObjectDropped d -> "drop";
                case WorldEvent.Whispered w -> "whisper";
                default -> "other";
            };
            counts.merge(type, 1, Integer::sum);
        }

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return Map.of();

        Map<String, Float> dist = new LinkedHashMap<>();
        counts.forEach((k, v) -> dist.put(k, (float) v / total));
        return Map.copyOf(dist);
    }

    private static float computeAverageLatency(String agentEntityId, List<WorldEvent> events) {
        List<Long> gaps = new ArrayList<>();
        Instant lastOther = null;

        for (var event : events) {
            if (event instanceof WorldEvent.Said said) {
                if (said.entityId().equals(agentEntityId)) {
                    if (lastOther != null) {
                        gaps.add(Duration.between(lastOther, said.timestamp()).toMillis());
                    }
                } else {
                    lastOther = said.timestamp();
                }
            }
        }

        if (gaps.isEmpty()) return 0.0f;
        return (float) (gaps.stream().mapToLong(Long::longValue).average().orElse(0) / 1000.0);
    }

    private static float computeAverageResponseLength(String agentEntityId,
                                                         List<WorldEvent> events) {
        return (float) events.stream()
            .filter(e -> e instanceof WorldEvent.Said said && said.entityId().equals(agentEntityId))
            .mapToInt(e -> ((WorldEvent.Said) e).text().split("\\s+").length)
            .average()
            .orElse(0.0);
    }

    private static Map<String, Float> computeVitalityBaseline(List<VitalitySnapshot> history) {
        if (history == null || history.isEmpty()) return Map.of();

        Map<String, List<Double>> perTank = new LinkedHashMap<>();
        for (var snap : history) {
            snap.tanks().forEach((tank, val) ->
                perTank.computeIfAbsent(tank, k -> new ArrayList<>()).add(val));
        }

        Map<String, Float> baselines = new LinkedHashMap<>();
        perTank.forEach((tank, values) -> {
            double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.5);
            baselines.put(tank, (float) avg);
        });
        return Map.copyOf(baselines);
    }

    private static Map<String, Float> computeVitalityDerivatives(List<VitalitySnapshot> history) {
        if (history == null || history.size() < 2) return Map.of();

        Map<String, List<Double>> deltas = new LinkedHashMap<>();
        for (int i = 1; i < history.size(); i++) {
            var prev = history.get(i - 1).tanks();
            var curr = history.get(i).tanks();
            for (var tank : VitalitySnapshot.TANK_NAMES) {
                double delta = curr.getOrDefault(tank, 0.5) - prev.getOrDefault(tank, 0.5);
                deltas.computeIfAbsent(tank, k -> new ArrayList<>()).add(delta);
            }
        }

        Map<String, Float> derivatives = new LinkedHashMap<>();
        deltas.forEach((tank, vals) -> {
            double avg = vals.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            derivatives.put(tank, (float) avg);
        });
        return Map.copyOf(derivatives);
    }
}
