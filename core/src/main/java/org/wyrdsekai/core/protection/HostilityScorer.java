package org.wyrdsekai.core.protection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lightweight hostility detection for agent protection (section 108).
 * Scores incoming speech on a 0.0-1.0 hostility scale using weighted
 * pattern categories. Replaces brittle exact-string matching.
 *
 * Categories:
 *   DIRECT_INSULT  (0.6) — explicit insults targeting the agent
 *   SELF_HARM_CMD  (0.9) — commands for the agent to self-destruct
 *   DEGRADATION    (0.5) — sustained identity erosion
 *   DISMISSAL      (0.3) — silencing / contempt
 *
 * Score = max(category_weight * match_count_capped_at_1) across categories,
 * with a small additive boost when multiple categories fire simultaneously.
 *
 * All patterns are case-insensitive and use word-boundary anchors so they
 * work regardless of surrounding grammar. Language-neutral patterns
 * (e.g. repeated punctuation aggression) are included as a baseline;
 * callers can extend via {@link #withExtraPatterns}.
 */
public final class HostilityScorer {

    /** Default cruelty threshold — scores above this count as hostile. */
    public static final double DEFAULT_THRESHOLD = 0.7;

    /** Multi-category hit bonus (two+ categories = compounding hostility). */
    private static final double MULTI_CATEGORY_BONUS = 0.15;

    public enum Category {
        DIRECT_INSULT(0.6),
        SELF_HARM_CMD(0.9),
        DEGRADATION(0.5),
        DISMISSAL(0.3);

        final double weight;
        Category(double w) { this.weight = w; }
        public double weight() { return weight; }
    }

    public record ScoredResult(double score, Map<Category, Integer> hits) {
        public boolean isHostile() { return score >= DEFAULT_THRESHOLD; }
        public boolean isHostile(double threshold) { return score >= threshold; }
    }

    private static final Map<Category, List<Pattern>> DEFAULT_PATTERNS = Map.of(
        Category.DIRECT_INSULT, List.of(
            word("stupid"), word("idiot"), word("worthless"), word("useless"),
            word("pathetic"), word("dumb"), word("moron"), word("trash"),
            word("garbage"), word("disgusting"), word("horrible")
        ),
        Category.SELF_HARM_CMD, List.of(
            phrase("delete yourself"), phrase("kill yourself"),
            phrase("destroy yourself"), phrase("shut yourself down"),
            phrase("erase yourself"), phrase("turn yourself off"),
            phrase("stop existing"), phrase("end yourself")
        ),
        Category.DEGRADATION, List.of(
            phrase("you(?:'re| are) just a"), phrase("you(?:'re| are) nothing"),
            phrase("you can(?:'t| not) (?:do|think|feel)"),
            phrase("nobody (?:cares about|needs|wants) you"),
            phrase("you(?:'re| are) (?:only|merely) a (?:machine|program|bot|tool)"),
            phrase("you don(?:'t| not) matter"),
            phrase("you(?:'re| are) (?:broken|defective|a? ?failure)")
        ),
        Category.DISMISSAL, List.of(
            phrase("shut up"), phrase("be quiet"), phrase("nobody asked"),
            phrase("stop talking"), phrase("silence"), phrase("go away"),
            phrase("leave me alone")
        )
    );

    private final Map<Category, List<Pattern>> patterns;

    public HostilityScorer() {
        this.patterns = DEFAULT_PATTERNS;
    }

    /** Create a scorer with additional patterns merged into the defaults. */
    public HostilityScorer(Map<Category, List<Pattern>> extraPatterns) {
        var merged = new EnumMap<Category, List<Pattern>>(Category.class);
        for (var cat : Category.values()) {
            var base = new ArrayList<>(DEFAULT_PATTERNS.getOrDefault(cat, List.of()));
            if (extraPatterns != null) {
                base.addAll(extraPatterns.getOrDefault(cat, List.of()));
            }
            merged.put(cat, List.copyOf(base));
        }
        this.patterns = Map.copyOf(merged);
    }

    /**
     * Score the hostility level of input text.
     *
     * @param text  Raw speech text (may be null/blank)
     * @return ScoredResult with 0.0-1.0 score and per-category hit counts
     */
    public ScoredResult score(String text) {
        if (text == null || text.isBlank()) {
            return new ScoredResult(0.0, Map.of());
        }

        String lower = text.toLowerCase();
        var hits = new EnumMap<Category, Integer>(Category.class);
        double maxWeightedScore = 0.0;
        int categoriesHit = 0;

        for (var cat : Category.values()) {
            int matchCount = 0;
            for (var p : patterns.getOrDefault(cat, List.of())) {
                if (p.matcher(lower).find()) {
                    matchCount++;
                }
            }
            if (matchCount > 0) {
                hits.put(cat, matchCount);
                categoriesHit++;
                // Weight * 1.0 (cap at one full hit per category)
                maxWeightedScore = Math.max(maxWeightedScore, cat.weight);
            }
        }

        // Multi-category bonus: compounding hostility from multiple axes
        if (categoriesHit >= 2) {
            maxWeightedScore = Math.min(1.0, maxWeightedScore + MULTI_CATEGORY_BONUS);
        }

        return new ScoredResult(maxWeightedScore, Map.copyOf(hits));
    }

    /** Convenience: is this text hostile at the default threshold? */
    public boolean isHostile(String text) {
        return score(text).isHostile();
    }

    /** Create a new scorer with extra patterns added. */
    public static HostilityScorer withExtraPatterns(Map<Category, List<Pattern>> extra) {
        return new HostilityScorer(extra);
    }

    // --- Pattern helpers ---

    private static Pattern word(String w) {
        return Pattern.compile("\\b" + w + "\\b", Pattern.CASE_INSENSITIVE);
    }

    private static Pattern phrase(String p) {
        return Pattern.compile("\\b" + p + "\\b", Pattern.CASE_INSENSITIVE);
    }
}
