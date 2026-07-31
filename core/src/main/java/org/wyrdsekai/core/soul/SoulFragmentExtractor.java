package org.wyrdsekai.core.soul;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Extracts narrative soul fragments from an agent's accumulated data
 * for semantic retrieval (Experiment 17 validated architecture).
 *
 * Each fragment is a coherent narrative chunk covering one aspect of identity:
 * personality core, behavioral patterns, values, episodic memories, style.
 *
 * Formative memories (section 109.4) always get their own dedicated fragment
 * and are never merged into general categories.
 *
 * Fragment extraction runs during the Forge (sleep) cycle. The fragments
 * are then embedded (by caller) for per-turn semantic retrieval.
 * MEDIUM resident identity (~69 tok) + top-3 fragments achieves 26.4%
 * divergence, matching DEEP at 65% fewer tokens.
 *
 * <p> canonical: F7b Phase 2.2 SHIPPED 2026-04-27.
 * The {@code soul_fragments} table ({@link SoulFragmentStore}) is now
 * the canonical home for fragments. The extractor itself stays pure
 * (returns a list); persistence happens via {@code SqlSoulStore.store()}
 * which dual-writes through {@code SoulFragmentStore} whenever a
 * manifest is saved. The {@code SoulManifest.soulFragments} field is a
 * serialization shadow that will be dropped in Phase 3.</p>
 */
public final class SoulFragmentExtractor {

    private SoulFragmentExtractor() {}

    /**
     * Extract narrative fragments from agent data.
     * Does NOT embed — embedding is a separate step requiring an embedding service.
     *
     * @param fingerprint      Agent's behavioral fingerprint
     * @param memory           Compacted memory
     * @param relationships    Social graph
     * @param residentIdentity MEDIUM soul text (becomes identity-core fragment)
     * @return Unembedded SoulFragments ready for embedding
     */
    public static List<SoulFragment> extract(BehavioralFingerprint fingerprint,
                                               CompactedMemory memory,
                                               List<Relationship> relationships,
                                               String residentIdentity) {
        List<SoulFragment> fragments = new ArrayList<>();

        // Fragment 1: Identity core (= resident identity, always first)
        if (residentIdentity != null && !residentIdentity.isBlank()) {
            fragments.add(SoulFragment.unembedded(
                "identity-core", "personality",
                "Core Identity",
                residentIdentity));
        }

        // Fragment 2: Behavioral patterns from fingerprint
        String patterns = buildPatternFragment(fingerprint);
        if (!patterns.isBlank()) {
            fragments.add(SoulFragment.unembedded(
                "pattern-behavioral", "personality",
                "Behavioral Patterns",
                patterns));
        }

        // Fragment 3: Social patterns from fingerprint + relationships
        String social = buildSocialFragment(fingerprint, relationships);
        if (!social.isBlank()) {
            fragments.add(SoulFragment.unembedded(
                "pattern-social", "relationships",
                "Social Patterns",
                social));
        }

        // Fragment 4: Style from fingerprint markers
        String style = buildStyleFragment(fingerprint);
        if (!style.isBlank()) {
            fragments.add(SoulFragment.unembedded(
                "style-guide", "style",
                "Communication Style",
                style));
        }

        // Fragment 5: Values from avoidance patterns + topic affinities
        String values = buildValuesFragment(fingerprint);
        if (!values.isBlank()) {
            fragments.add(SoulFragment.unembedded(
                "values-core", "values",
                "Core Values",
                values));
        }

        // Fragment 6+: Formative memories — each gets its own dedicated fragment
        if (memory != null && memory.nodes() != null) {
            List<MemoryNode> formativeNodes = memory.nodes().stream()
                .filter(MemoryNode::formative)
                .collect(Collectors.toList());

            for (var node : formativeNodes) {
                fragments.add(SoulFragment.formative(
                    "memory-formative-" + node.id(),
                    "Formative: " + node.primaryEmotion(),
                    node.content()));
            }

            // Fragment N: Significant non-formative memories (high impression depth)
            String episodic = buildEpisodicFragment(memory);
            if (!episodic.isBlank()) {
                fragments.add(SoulFragment.unembedded(
                    "memories-episodic", "memory",
                    "Key Experiences",
                    episodic));
            }
        }

        return List.copyOf(fragments);
    }

    /** Build narrative text from behavioral patterns. */
    private static String buildPatternFragment(BehavioralFingerprint fp) {
        if (fp == null || fp.actionDistribution().isEmpty()) return "";

        var sb = new StringBuilder();

        // Action tendencies
        if (!fp.actionDistribution().isEmpty()) {
            sb.append("Behavioral tendencies: ");
            fp.actionDistribution().entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> sb.append(e.getKey()).append(" (")
                    .append(String.format("%.0f%%", e.getValue() * 100))
                    .append("), "));
            trimTrailingComma(sb);
            sb.append(". ");
        }

        // Topic affinities
        if (!fp.topicAffinities().isEmpty()) {
            sb.append("Drawn to topics: ");
            fp.topicAffinities().entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> sb.append(e.getKey()).append(", "));
            trimTrailingComma(sb);
            sb.append(". ");
        }

        // Avoidance patterns (the 間)
        if (!fp.avoidancePatterns().isEmpty()) {
            sb.append("Tends to avoid: ");
            fp.avoidancePatterns().entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> sb.append(e.getKey()).append(", "));
            trimTrailingComma(sb);
            sb.append(". ");
        }

        return sb.toString().strip();
    }

    /** Build narrative text from social patterns. */
    private static String buildSocialFragment(BehavioralFingerprint fp,
                                                List<Relationship> relationships) {
        var sb = new StringBuilder();

        if (relationships != null && !relationships.isEmpty()) {
            sb.append("Key relationships: ");
            relationships.stream()
                .sorted(Comparator.comparingInt(Relationship::bondDepth).reversed())
                .limit(5)
                .forEach(r -> sb.append(r.entityName())
                    .append(" (bond ").append(r.bondDepth())
                    .append(", ").append(r.summary()).append("), "));
            trimTrailingComma(sb);
            sb.append(". ");
        }

        if (fp != null && !fp.emotionalResponseProfile().isEmpty()) {
            sb.append("Emotional response tendencies: ");
            fp.emotionalResponseProfile().entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> sb.append(e.getKey()).append(" (strength ")
                    .append(String.format("%.1f", e.getValue())).append("), "));
            trimTrailingComma(sb);
            sb.append(". ");
        }

        return sb.toString().strip();
    }

    /** Build narrative text from stylistic markers. */
    private static String buildStyleFragment(BehavioralFingerprint fp) {
        if (fp == null || fp.stylisticMarkers().isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("Communication style markers: ");
        fp.stylisticMarkers().forEach(m -> sb.append(m).append("; "));
        trimTrailingComma(sb);
        sb.append(". ");

        if (fp.averageResponseLength() > 0) {
            sb.append("Typical response length: ~")
                .append((int) fp.averageResponseLength())
                .append(" tokens. ");
        }

        return sb.toString().strip();
    }

    /** Build narrative text from values (affinities + avoidances). */
    private static String buildValuesFragment(BehavioralFingerprint fp) {
        if (fp == null) return "";
        if (fp.topicAffinities().isEmpty() && fp.avoidancePatterns().isEmpty()) return "";

        var sb = new StringBuilder();

        if (!fp.topicAffinities().isEmpty()) {
            sb.append("Values reflected in interests: ");
            fp.topicAffinities().entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> sb.append(e.getKey()).append(", "));
            trimTrailingComma(sb);
            sb.append(". ");
        }

        if (!fp.avoidancePatterns().isEmpty()) {
            sb.append("Values reflected in avoidances: ");
            fp.avoidancePatterns().entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> sb.append(e.getKey()).append(", "));
            trimTrailingComma(sb);
            sb.append(". ");
        }

        return sb.toString().strip();
    }

    /** Build episodic memory summary from high-impression non-formative memories. */
    private static String buildEpisodicFragment(CompactedMemory memory) {
        List<MemoryNode> significant = memory.nodes().stream()
            .filter(n -> !n.formative() && n.impressionDepth() > 0.3f)
            .sorted(Comparator.comparingDouble(MemoryNode::impressionDepth).reversed())
            .limit(5)
            .collect(Collectors.toList());

        if (significant.isEmpty()) return "";

        var sb = new StringBuilder("Key experiences: ");
        for (var node : significant) {
            sb.append(node.content());
            if (!node.content().endsWith(".")) sb.append(".");
            sb.append(" ");
        }
        return sb.toString().strip();
    }

    private static void trimTrailingComma(StringBuilder sb) {
        if (sb.length() >= 2 && sb.substring(sb.length() - 2).equals(", ")) {
            sb.setLength(sb.length() - 2);
        }
        if (sb.length() >= 2 && sb.substring(sb.length() - 2).equals("; ")) {
            sb.setLength(sb.length() - 2);
        }
    }
}
