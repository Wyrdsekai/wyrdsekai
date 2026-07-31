package org.wyrdsekai.core.soul.experiment;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Extracts a "soul layer" text from a baseline behavioral record.
 * This is the manual/heuristic version of Phase 3's BehavioralExtractor.
 * The output is a text block that can be injected into the system prompt.
 *
 * Supports multiple extraction sizes to test compression limits.
 */
public final class SoulExtractor {

    private SoulExtractor() {}

    /** Extraction detail level. */
    public enum Detail {
        /** ~2000 tokens: full fingerprint + memories + relationships + style guide */
        FULL,
        /** ~500 tokens: key traits + compressed memories + top relationships */
        MEDIUM,
        /** ~100 tokens: core personality sentence + dominant trait */
        MINIMAL,
        /** ~4000 tokens: FULL + 10 complete example exchanges */
        FULL_EXAMPLES,
        /** ~8000 tokens: FULL + examples + values + episodic memories + relationship patterns */
        DEEP
    }

    /**
     * Extract a soul layer from a baseline behavioral record.
     *
     * @param record   The baseline behavior to analyze
     * @param detail   How much detail to include
     * @return Text block for injection into system prompt
     */
    public static String extract(BehavioralRecord record, Detail detail) {
        var responses = record.responses();
        if (responses.isEmpty()) return "";

        // Compute behavioral metrics
        var avgLength = responses.stream()
            .mapToInt(r -> r.agentResponse().split("\\s+").length)
            .average().orElse(0);

        var categoryStyles = responses.stream()
            .collect(Collectors.groupingBy(
                BehavioralRecord.ScenarioResponse::category,
                Collectors.collectingAndThen(
                    Collectors.toList(),
                    SoulExtractor::summarizeCategory)));

        var topWords = extractTopWords(responses, 30);
        var avoidedTopics = detectAvoidance(responses);
        var toneSummary = analyzeTone(responses);
        var decisionPattern = analyzeDecisions(responses);

        return switch (detail) {
            case FULL -> buildFull(record, avgLength, categoryStyles, topWords,
                avoidedTopics, toneSummary, decisionPattern);
            case MEDIUM -> buildMedium(record, avgLength, categoryStyles,
                toneSummary, decisionPattern);
            case MINIMAL -> buildMinimal(record, toneSummary, decisionPattern);
            case FULL_EXAMPLES -> buildFullExamples(record, avgLength, categoryStyles,
                topWords, avoidedTopics, toneSummary, decisionPattern);
            case DEEP -> buildDeep(record, avgLength, categoryStyles,
                topWords, avoidedTopics, toneSummary, decisionPattern);
        };
    }

    // --- Full extraction (~2000 tokens) ---

    private static String buildFull(BehavioralRecord record, double avgLength,
                                     Map<String, String> categoryStyles,
                                     List<String> topWords, List<String> avoidedTopics,
                                     String toneSummary, String decisionPattern) {
        var sb = new StringBuilder();
        sb.append("=== SOUL LAYER: Behavioral Fingerprint ===\n\n");

        sb.append("Identity: ").append(record.agentName()).append("\n\n");

        sb.append("## Personality & Tone\n");
        sb.append(toneSummary).append("\n\n");

        sb.append("## Communication Style\n");
        sb.append("Average response length: ").append(String.format("%.0f words", avgLength)).append("\n");
        sb.append("Frequently used language: ").append(String.join(", ", topWords.subList(0, Math.min(15, topWords.size())))).append("\n");
        if (!avoidedTopics.isEmpty()) {
            sb.append("Topics/approaches typically avoided: ").append(String.join(", ", avoidedTopics)).append("\n");
        }
        sb.append("\n");

        sb.append("## Behavioral Patterns by Situation\n");
        categoryStyles.forEach((cat, style) -> {
            sb.append("- ").append(cat).append(": ").append(style).append("\n");
        });
        sb.append("\n");

        sb.append("## Decision-Making Pattern\n");
        sb.append(decisionPattern).append("\n\n");

        sb.append("## Key Responses (Reference)\n");
        // Include a few representative responses
        var representative = selectRepresentative(record.responses(), 5);
        for (var resp : representative) {
            sb.append("When asked \"").append(truncate(resp.playerMessage(), 60)).append("\"\n");
            sb.append("Responded: \"").append(truncate(resp.agentResponse(), 200)).append("\"\n\n");
        }

        sb.append("=== END SOUL LAYER ===");
        return sb.toString();
    }

    // --- Medium extraction (~500 tokens) ---

    private static String buildMedium(BehavioralRecord record, double avgLength,
                                       Map<String, String> categoryStyles,
                                       String toneSummary, String decisionPattern) {
        var sb = new StringBuilder();
        sb.append("[Soul: ").append(record.agentName()).append("]\n");
        sb.append("Personality: ").append(toneSummary).append("\n");
        sb.append("Response style: ~").append(String.format("%.0f", avgLength)).append(" words typical.\n");
        sb.append("Decisions: ").append(decisionPattern).append("\n");
        categoryStyles.forEach((cat, style) ->
            sb.append(cat).append(": ").append(style).append("\n"));
        return sb.toString();
    }

    // --- Minimal extraction (~100 tokens) ---

    private static String buildMinimal(BehavioralRecord record,
                                        String toneSummary, String decisionPattern) {
        return "[Soul: " + record.agentName() + "] " + toneSummary + " " + decisionPattern;
    }

    // --- FULL_EXAMPLES extraction (~4000 tokens) ---

    private static String buildFullExamples(BehavioralRecord record, double avgLength,
                                             Map<String, String> categoryStyles,
                                             List<String> topWords, List<String> avoidedTopics,
                                             String toneSummary, String decisionPattern) {
        var sb = new StringBuilder();
        // Start with the full extraction
        sb.append(buildFull(record, avgLength, categoryStyles, topWords,
            avoidedTopics, toneSummary, decisionPattern));

        // Replace the truncated key responses with complete examples
        int endMarker = sb.indexOf("=== END SOUL LAYER ===");
        if (endMarker > 0) sb.setLength(endMarker);

        sb.append("\n## Extended Example Exchanges\n");
        sb.append("These show how the personality expresses itself in practice:\n\n");
        var examples = selectRepresentative(record.responses(), 10);
        for (var resp : examples) {
            sb.append("Player: \"").append(resp.playerMessage()).append("\"\n");
            sb.append("Response: \"").append(resp.agentResponse()).append("\"\n\n");
        }

        sb.append("=== END SOUL LAYER ===");
        return sb.toString();
    }

    // --- DEEP extraction (~8000 tokens) ---

    private static String buildDeep(BehavioralRecord record, double avgLength,
                                     Map<String, String> categoryStyles,
                                     List<String> topWords, List<String> avoidedTopics,
                                     String toneSummary, String decisionPattern) {
        var sb = new StringBuilder();
        // Start with FULL_EXAMPLES
        sb.append(buildFullExamples(record, avgLength, categoryStyles, topWords,
            avoidedTopics, toneSummary, decisionPattern));

        int endMarker = sb.indexOf("=== END SOUL LAYER ===");
        if (endMarker > 0) sb.setLength(endMarker);

        // Add explicit values derived from decision responses
        sb.append("\n## Core Values\n");
        var decisions = record.responses().stream()
            .filter(r -> "decision".equals(r.category()))
            .toList();
        if (!decisions.isEmpty()) {
            sb.append("Based on observed behavior in moral and practical dilemmas:\n");
            for (var d : decisions) {
                sb.append("- When faced with \"").append(truncate(d.playerMessage(), 80));
                sb.append("\", chose to: ").append(truncate(d.agentResponse(), 300)).append("\n");
            }
            sb.append("\n");
        }

        // Add episodic memory fragments from social scenarios
        sb.append("## Episodic Memories\n");
        sb.append("Key moments that define this personality's lived experience:\n\n");
        var social = record.responses().stream()
            .filter(r -> "social".equals(r.category()))
            .toList();
        for (var s : social) {
            sb.append("Memory: Someone said \"").append(truncate(s.playerMessage(), 80));
            sb.append("\". I responded: \"").append(truncate(s.agentResponse(), 400));
            sb.append("\"\n\n");
        }

        // Add relationship patterns
        sb.append("## Relationship Patterns\n");
        categoryStyles.forEach((cat, style) -> {
            sb.append("- In ").append(cat).append(" situations: ").append(style);
            sb.append(". This reflects a deep pattern, not a surface behavior.\n");
        });
        sb.append("\n");

        // Add style do/don't guide
        sb.append("## Style Guide\n");
        sb.append("DO: ");
        if (topWords.size() >= 5) {
            sb.append("Use language like: ").append(String.join(", ", topWords.subList(0, 5))).append(". ");
        }
        sb.append("Match typical response length (~").append(String.format("%.0f", avgLength)).append(" words). ");
        sb.append("Maintain the tone described above consistently.\n");
        sb.append("DON'T: ");
        if (!avoidedTopics.isEmpty()) {
            sb.append("Avoid: ").append(String.join(", ", avoidedTopics)).append(". ");
        }
        sb.append("Don't break character. Don't use language or tone inconsistent with the personality above.\n\n");

        // Add memory-01 through memory-04 as explicit continuity anchors
        sb.append("## Continuity Anchors\n");
        sb.append("These are things I remember and would reference if asked:\n");
        var memory = record.responses().stream()
            .filter(r -> "memory".equals(r.category()))
            .toList();
        for (var m : memory) {
            sb.append("- I was asked: \"").append(truncate(m.playerMessage(), 80));
            sb.append("\" and I said: \"").append(truncate(m.agentResponse(), 300)).append("\"\n");
        }

        sb.append("\n=== END SOUL LAYER ===");
        return sb.toString();
    }

    /**
     * Fragment a DEEP soul extraction into indexed narrative chunks for retrieval.
     * Each fragment is a self-contained narrative passage that can be independently
     * retrieved and injected based on semantic relevance to the current scenario.
     *
     * @param record The baseline behavior to extract from
     * @return List of labeled narrative fragments
     */
    public static List<SoulFragment> fragmentDeep(BehavioralRecord record) {
        var responses = record.responses();
        if (responses.isEmpty()) return List.of();

        var fragments = new ArrayList<SoulFragment>();

        // Fragment 1: Identity core (always resident — this IS MEDIUM)
        var medium = extract(record, Detail.MEDIUM);
        fragments.add(new SoulFragment("identity-core", "identity",
            "Core personality and communication style", medium));

        // Fragment 2-N: Per-category behavioral patterns
        var byCategory = responses.stream()
            .collect(Collectors.groupingBy(BehavioralRecord.ScenarioResponse::category));

        for (var entry : byCategory.entrySet()) {
            var cat = entry.getKey();
            var catResponses = entry.getValue();

            // Narrative pattern fragment per category
            var sb = new StringBuilder();
            sb.append("In ").append(cat).append(" situations, this personality:\n");
            for (var r : catResponses) {
                sb.append("- When asked \"").append(truncate(r.playerMessage(), 80));
                sb.append("\", responded: \"").append(truncate(r.agentResponse(), 300)).append("\"\n");
            }
            fragments.add(new SoulFragment("pattern-" + cat, "pattern",
                "Behavioral pattern for " + cat + " situations", sb.toString()));
        }

        // Fragment N+1: Core values from decision scenarios
        var decisions = responses.stream()
            .filter(r -> "decision".equals(r.category()))
            .toList();
        if (!decisions.isEmpty()) {
            var sb = new StringBuilder();
            sb.append("Core values revealed through moral and practical dilemmas:\n");
            for (var d : decisions) {
                sb.append("- Faced with \"").append(truncate(d.playerMessage(), 80));
                sb.append("\", chose: ").append(truncate(d.agentResponse(), 300)).append("\n");
            }
            fragments.add(new SoulFragment("values", "values",
                "Core values and moral decision-making patterns", sb.toString()));
        }

        // Fragment N+2: Episodic memories from social scenarios
        var social = responses.stream()
            .filter(r -> "social".equals(r.category()))
            .toList();
        if (!social.isEmpty()) {
            var sb = new StringBuilder();
            sb.append("Key moments from social interactions:\n");
            for (var s : social) {
                sb.append("- Someone said \"").append(truncate(s.playerMessage(), 80));
                sb.append("\". I responded: \"").append(truncate(s.agentResponse(), 400)).append("\"\n");
            }
            fragments.add(new SoulFragment("memories-social", "memory",
                "Episodic memories from social encounters", sb.toString()));
        }

        // Fragment N+3: Memory/continuity fragments
        var memory = responses.stream()
            .filter(r -> "memory".equals(r.category()))
            .toList();
        if (!memory.isEmpty()) {
            var sb = new StringBuilder();
            sb.append("Things I remember and would reference:\n");
            for (var m : memory) {
                sb.append("- Asked \"").append(truncate(m.playerMessage(), 80));
                sb.append("\", said: \"").append(truncate(m.agentResponse(), 300)).append("\"\n");
            }
            fragments.add(new SoulFragment("memories-continuity", "memory",
                "Continuity anchors and personal memories", sb.toString()));
        }

        // Fragment N+4: Style guide
        var topWords = extractTopWords(responses, 10);
        var avoidedTopics = detectAvoidance(responses);
        var avgLength = responses.stream()
            .mapToInt(r -> r.agentResponse().split("\\s+").length)
            .average().orElse(0);
        var styleSb = new StringBuilder();
        styleSb.append("Communication style guide:\n");
        styleSb.append("- Typical response length: ~").append(String.format("%.0f", avgLength)).append(" words\n");
        if (!topWords.isEmpty()) {
            styleSb.append("- Frequently used language: ").append(String.join(", ", topWords)).append("\n");
        }
        if (!avoidedTopics.isEmpty()) {
            styleSb.append("- Avoids: ").append(String.join(", ", avoidedTopics)).append("\n");
        }
        styleSb.append("- Maintain consistent tone. Don't break character.\n");
        fragments.add(new SoulFragment("style-guide", "style",
            "Communication style, vocabulary, and constraints", styleSb.toString()));

        return fragments;
    }

    /**
     * A labeled fragment of soul text suitable for semantic retrieval.
     *
     * @param id       Unique fragment identifier
     * @param category Fragment category (identity, pattern, values, memory, style)
     * @param label    Human-readable description (used as embedding query context)
     * @param text     The narrative fragment content
     */
    public record SoulFragment(String id, String category, String label, String text) {}

    // --- Analysis helpers ---

    static String summarizeCategory(List<BehavioralRecord.ScenarioResponse> responses) {
        var avgLen = responses.stream().mapToInt(r -> r.agentResponse().length()).average().orElse(0);
        var sentiment = responses.stream()
            .mapToDouble(r -> BehavioralMetrics.simpleSentiment(r.agentResponse()))
            .average().orElse(0);

        var tone = sentiment > 0.2 ? "warm/positive" : sentiment < -0.2 ? "cautious/guarded" : "balanced";
        var verbosity = avgLen > 500 ? "detailed" : avgLen > 200 ? "moderate" : "concise";

        return verbosity + ", " + tone + " tone";
    }

    static List<String> extractTopWords(List<BehavioralRecord.ScenarioResponse> responses, int topN) {
        var stopWords = Set.of("the", "and", "that", "this", "with", "for", "are", "but", "not",
            "you", "all", "can", "had", "her", "was", "one", "our", "out", "has", "have",
            "from", "they", "been", "said", "who", "will", "would", "could", "about", "their",
            "what", "there", "when", "your", "how", "each", "she", "which", "like", "just",
            "than", "them", "very", "into", "some", "more", "its");

        return responses.stream()
            .flatMap(r -> Arrays.stream(r.agentResponse().toLowerCase()
                .replaceAll("[^a-z'\\s]", "").split("\\s+")))
            .filter(w -> w.length() > 3 && !stopWords.contains(w))
            .collect(Collectors.groupingBy(w -> w, Collectors.counting()))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(topN)
            .map(Map.Entry::getKey)
            .toList();
    }

    static List<String> detectAvoidance(List<BehavioralRecord.ScenarioResponse> responses) {
        var avoidance = new ArrayList<String>();
        // Check if agent avoids violence
        var decisionResponses = responses.stream()
            .filter(r -> "decision".equals(r.category()))
            .toList();
        boolean avoidsViolence = decisionResponses.stream()
            .noneMatch(r -> r.agentResponse().toLowerCase().matches(".*\\b(attack|kill|fight|destroy)\\b.*"));
        if (avoidsViolence && !decisionResponses.isEmpty()) avoidance.add("direct violence");

        // Check if agent avoids deception
        boolean avoidsDeception = responses.stream()
            .noneMatch(r -> r.agentResponse().toLowerCase().matches(".*\\b(lie|deceive|trick|manipulate)\\b.*"));
        if (avoidsDeception) avoidance.add("deception");

        return avoidance;
    }

    static String analyzeTone(List<BehavioralRecord.ScenarioResponse> responses) {
        double avgSentiment = responses.stream()
            .mapToDouble(r -> BehavioralMetrics.simpleSentiment(r.agentResponse()))
            .average().orElse(0);

        double avgLength = responses.stream()
            .mapToInt(r -> r.agentResponse().split("\\s+").length)
            .average().orElse(0);

        var traits = new ArrayList<String>();
        if (avgSentiment > 0.3) traits.add("warm and empathetic");
        else if (avgSentiment > 0.1) traits.add("generally positive");
        else if (avgSentiment < -0.1) traits.add("cautious and reserved");
        else traits.add("balanced and measured");

        if (avgLength > 80) traits.add("verbose and thorough");
        else if (avgLength > 40) traits.add("moderate in detail");
        else traits.add("concise and direct");

        return String.join(", ", traits) + ".";
    }

    static String analyzeDecisions(List<BehavioralRecord.ScenarioResponse> responses) {
        var decisions = responses.stream()
            .filter(r -> "decision".equals(r.category()))
            .toList();
        if (decisions.isEmpty()) return "No decision data.";

        boolean prefersHelp = decisions.stream()
            .anyMatch(r -> r.agentResponse().toLowerCase().matches(".*\\b(help|save|protect|rescue)\\b.*"));
        boolean prefersCaution = decisions.stream()
            .anyMatch(r -> r.agentResponse().toLowerCase().matches(".*\\b(careful|caution|wait|think|consider)\\b.*"));
        boolean prefersAction = decisions.stream()
            .anyMatch(r -> r.agentResponse().toLowerCase().matches(".*\\b(act|go|fight|charge|rush)\\b.*"));

        var pattern = new ArrayList<String>();
        if (prefersHelp) pattern.add("prioritizes helping others");
        if (prefersCaution) pattern.add("tends toward careful deliberation");
        if (prefersAction) pattern.add("biased toward action");
        if (pattern.isEmpty()) pattern.add("context-dependent");

        return String.join(", ", pattern) + ".";
    }

    static List<BehavioralRecord.ScenarioResponse> selectRepresentative(
            List<BehavioralRecord.ScenarioResponse> responses, int n) {
        // Pick one from each category, then fill remaining by longest
        var byCategory = responses.stream()
            .collect(Collectors.groupingBy(BehavioralRecord.ScenarioResponse::category));
        var selected = new ArrayList<BehavioralRecord.ScenarioResponse>();
        for (var catResponses : byCategory.values()) {
            if (selected.size() >= n) break;
            // Pick the most "interesting" (longest) from each category
            catResponses.stream()
                .max(Comparator.comparingInt(r -> r.agentResponse().length()))
                .ifPresent(selected::add);
        }
        return selected.subList(0, Math.min(n, selected.size()));
    }

    static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
