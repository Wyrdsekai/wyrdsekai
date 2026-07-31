package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.soul.SoulItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Retrieves top-k skill items from the family locker by keyword overlap.
 * Same scoring approach as {@link SoulFragmentRetriever} — Jaccard-like
 * keyword overlap with significance weighting.
 *
 * Skill items with higher significance (from frequent usage) surface first,
 * creating a positive feedback loop: build once, use many, reinforce.
 */
public final class SkillItemRetriever {

    /** Max skill items to include in capability context. */
    public static final int DEFAULT_K = 5;

    /** Significance boost factor — high-significance skills score higher. */
    private static final float SIGNIFICANCE_BOOST = 1.5f;

    private SkillItemRetriever() {}

    /**
     * Retrieve top-k skill items by keyword relevance to the current context.
     *
     * @param contextKeywords Keywords from conversation/room context
     * @param skillItems      All skill SoulItems from the family locker
     * @param k               Maximum items to return
     * @return Ranked skill items, at most k
     */
    public static List<SoulItem> retrieve(String contextKeywords,
                                            List<SoulItem> skillItems, int k) {
        if (skillItems == null || skillItems.isEmpty() || k <= 0) return List.of();

        if (contextKeywords == null || contextKeywords.isBlank()) {
            // No context — return by significance (most-used first)
            return skillItems.stream()
                .sorted((a, b) -> Double.compare(b.significance(), a.significance()))
                .limit(k)
                .toList();
        }

        var inputWords = tokenize(contextKeywords);
        if (inputWords.isEmpty()) {
            return skillItems.stream()
                .sorted((a, b) -> Double.compare(b.significance(), a.significance()))
                .limit(k)
                .toList();
        }

        record Scored(SoulItem item, float score) {}

        var scored = new ArrayList<Scored>();
        for (var item : skillItems) {
            // Score against label + tags + text (which contains the description in JSON)
            String searchText = buildSearchText(item);
            float score = keywordOverlapScore(inputWords, searchText);
            // Boost by significance (frequently-used skills rank higher)
            if (item.significance() > 0.5) {
                score *= SIGNIFICANCE_BOOST;
            }
            scored.add(new Scored(item, score));
        }

        return scored.stream()
            .sorted((a, b) -> Float.compare(b.score(), a.score()))
            .limit(k)
            .map(Scored::item)
            .toList();
    }

    /**
     * Build a compact capability summary line for a skill item.
     * Format: [skill] label: description (deps: dep1, dep2)
     */
    public static String formatSkillLine(SoulItem item) {
        var sb = new StringBuilder();
        sb.append("[skill] ").append(item.label()).append(": ");

        // Try to extract description from JSON text
        String desc = extractDescription(item.text());
        if (desc != null) {
            sb.append(desc);
        } else {
            sb.append("(no description)");
        }

        // Add dependency info if present
        String deps = extractDependencies(item.text());
        if (deps != null && !deps.isEmpty()) {
            sb.append(" (").append(deps).append(")");
        }

        return sb.toString();
    }

    // --- Internal ---

    private static String buildSearchText(SoulItem item) {
        var sb = new StringBuilder();
        if (item.label() != null) sb.append(item.label()).append(" ");
        if (item.tags() != null) {
            for (var tag : item.tags()) sb.append(tag).append(" ");
        }
        // Include description from JSON if extractable
        String desc = extractDescription(item.text());
        if (desc != null) sb.append(desc);
        return sb.toString();
    }

    static float keywordOverlapScore(Set<String> inputWords, String text) {
        if (inputWords.isEmpty() || text == null || text.isBlank()) return 0f;

        var textWords = tokenize(text);
        if (textWords.isEmpty()) return 0f;

        long overlap = inputWords.stream().filter(textWords::contains).count();
        var union = new HashSet<>(inputWords);
        union.addAll(textWords);
        return (float) overlap / union.size();
    }

    private static Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("[\\s\\p{Punct}]+"))
            .filter(w -> w.length() > 2)
            .collect(Collectors.toSet());
    }

    /**
     * Quick extraction of "description" field from skill JSON without full parse.
     * Used for formatting — avoids full Jackson parse in hot path.
     */
    static String extractDescription(String json) {
        if (json == null) return null;
        int idx = json.indexOf("\"description\"");
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + 13);
        if (colonIdx < 0) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    /**
     * Quick extraction of dependencies from skill JSON.
     */
    static String extractDependencies(String json) {
        if (json == null) return null;
        int idx = json.indexOf("\"dependencies\"");
        if (idx < 0) return null;
        int bracketStart = json.indexOf('[', idx);
        if (bracketStart < 0) return null;
        int bracketEnd = json.indexOf(']', bracketStart);
        if (bracketEnd < 0) return null;
        String content = json.substring(bracketStart + 1, bracketEnd).trim();
        if (content.isEmpty()) return null;
        // Strip quotes
        return content.replaceAll("\"", "").trim();
    }
}
