package org.wyrdsekai.core.codemode;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Track A Phase 2b — heuristic trigger for free-form
 * code-mode entry.
 *
 * <p>Detects "research-shape" requests where the bondholder is asking for a
 * composition of multiple operations: <em>"find me X and Y"</em>,
 * <em>"compare A vs B"</em>, <em>"look at both ... and ..."</em>,
 * <em>"for each X do Y"</em>, <em>"across multiple sources"</em>.
 *
 * <p>The heuristic is intentionally <strong>readable and conservative</strong>:
 * a missed research-shape (false negative) is fine — the existing ReAct loop
 * still works. Firing on a shape the model can't handle (false positive) is
 * worse, because it offers a code-mode hint block to a turn that would have
 * worked better as a single tool call.
 *
 * <p>This is <em>not</em> semantic detection. It's pattern-matching against
 * surface English forms that strongly suggest multi-step composition. Phase 2c
 * may add classifier-based detection if soak shows under-firing.
 *
 * <p>Stateless and side-effect free.
 */
public final class ImprovisationTrigger {

    private ImprovisationTrigger() {}

    /**
     * Result of a trigger evaluation: a boolean + a short human-readable reason
     * for audit logs.
     */
    public record Decision(boolean fires, String reason) {
        public static Decision miss() {
            return new Decision(false, "no multi-step cue detected");
        }
        public static Decision hit(String reason) {
            return new Decision(true, reason);
        }
    }

    // ── Pattern table ───────────────────────────────────────────────────────
    // Each pattern carries its own rationale. Order matters loosely — the
    // first match wins, so put the strongest signals first.

    private static final List<RuleEntry> RULES = List.of(
        // "look at both X and Y" / "search both X and Y"
        new RuleEntry(
            Pattern.compile("(?i)\\b(look\\s+at|search|check|read|consult|query)\\s+both\\b"),
            "both-X-and-Y phrasing"),

        // "compare X vs Y" / "compare X to Y" / "X versus Y"
        new RuleEntry(
            Pattern.compile("(?i)\\b(compare|contrast|cross[- ]reference)\\b"),
            "comparison verb"),
        new RuleEntry(
            Pattern.compile("(?i)\\b(\\w+\\s+(vs\\.?|versus)\\s+\\w+)\\b"),
            "X vs Y phrasing"),

        // "for each X" / "for every X"
        new RuleEntry(
            Pattern.compile("(?i)\\bfor\\s+(each|every)\\b"),
            "for-each iteration cue"),

        // "across multiple sources" / "across several sources" / "across all sources"
        new RuleEntry(
            Pattern.compile("(?i)\\bacross\\s+(multiple|several|all|many)\\s+(sources|tools|sites|databases|places|results)\\b"),
            "across-multiple-sources cue"),

        // "from multiple sources" / "from several sources"
        new RuleEntry(
            Pattern.compile("(?i)\\bfrom\\s+(multiple|several|many|all)\\s+(sources|tools|sites|databases|places|results|angles)\\b"),
            "from-multiple-sources cue"),

        // "dedupe" / "merge results" / "combine results"
        new RuleEntry(
            Pattern.compile("(?i)\\b(de[- ]?dupe|merge\\s+(results?|sources?)|combine\\s+(results?|sources?))\\b"),
            "merge/dedupe cue"),

        // "find me X and Y" / "search X and Y" / "look up X and Y"
        // Tighter form: a search-verb followed by a clear two-noun coordination.
        new RuleEntry(
            Pattern.compile("(?i)\\b(find|search|look\\s+up|look\\s+for|locate|fetch|retrieve|gather)\\b[^.?!]*?\\b(and|plus|along\\s+with|together\\s+with)\\b[^.?!]*?\\b(\\w+)\\b"),
            "search-X-and-Y phrasing")
    );

    /**
     * Evaluate a user message and decide whether free-form code-mode should
     * be offered to the model. {@code null} or blank input returns a miss.
     *
     * <p>Phase 2b uses surface-form heuristics only — see the rationale on the
     * class doc. This method is hot-path and must not allocate beyond the
     * matcher state already required for regex evaluation.
     */
    public static Decision evaluate(String userMessage) {
        if (userMessage == null) return Decision.miss();
        var trimmed = userMessage.strip();
        if (trimmed.isEmpty()) return Decision.miss();
        // Conservative: very short messages ("hi", "yes", "ok") are never
        // research-shape regardless of what tokens they contain. The regex
        // table is the line of defense, but the length floor catches the
        // tail of false positives like "Compare." (one-word imperative).
        if (trimmed.length() < 12) return Decision.miss();

        for (var rule : RULES) {
            if (rule.pattern.matcher(trimmed).find()) {
                return Decision.hit(rule.reason);
            }
        }
        return Decision.miss();
    }

    /** Convenience: just the boolean — for sites that don't need the audit reason. */
    public static boolean fires(String userMessage) {
        return evaluate(userMessage).fires();
    }

    private record RuleEntry(Pattern pattern, String reason) {}
}
