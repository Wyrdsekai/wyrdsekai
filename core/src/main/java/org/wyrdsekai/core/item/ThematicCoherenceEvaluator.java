package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Evaluates thematic coherence between items for composition.
 *
 * <p>Items compose through narrative coherence, not type compatibility. This evaluator
 * provides two paths:</p>
 * <ul>
 *   <li><b>Fast path</b>: ThematicProfile overlap for known templates. ~0ms.</li>
 *   <li><b>Slow path</b>: LLM evaluation for novel compositions. ~5-30s.
 *       The expense IS the security model (proof-of-work for creation).</li>
 * </ul>
 *
 * <p>Results are cached so repeated evaluation of the same pair doesn't
 * require re-inference.</p>
 *
 * @see ThematicProfile#overlapWith(ThematicProfile)
 */
public class ThematicCoherenceEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ThematicCoherenceEvaluator.class);

    /** Threshold above which items are considered thematically compatible via fast path. */
    private static final double FAST_PATH_THRESHOLD = 0.25;

    /** Threshold below which items are considered incompatible (need binding element). */
    private static final double INCOMPATIBLE_THRESHOLD = 0.05;

    /**
     * Result of a coherence evaluation.
     *
     * @param score         0.0-1.0, higher = more coherent
     * @param compatible    Whether composition is viable
     * @param suggestion    What the composition could produce (nullable)
     * @param bindingHint   What binding element could bridge the gap (nullable — only if incompatible)
     * @param evaluationPath "fast" (attribute overlap) or "llm" (inference-based)
     */
    public record CoherenceResult(
        double score,
        boolean compatible,
        String suggestion,
        String bindingHint,
        String evaluationPath
    ) {
        public static CoherenceResult compatible(double score, String suggestion, String path) {
            return new CoherenceResult(score, true, suggestion, null, path);
        }

        public static CoherenceResult incompatible(double score, String bindingHint, String path) {
            return new CoherenceResult(score, false, null, bindingHint, path);
        }
    }

    private final ConcurrentHashMap<String, CoherenceResult> cache = new ConcurrentHashMap<>();

    /**
     * Optional LLM evaluator for novel compositions. Takes (item1 description, item2 description)
     * and returns a coherence assessment string. Null = LLM path disabled (fast path only).
     */
    private final BiFunction<String, String, String> llmEvaluator;

    /**
     * Create an evaluator with LLM support for novel compositions.
     */
    public ThematicCoherenceEvaluator(BiFunction<String, String, String> llmEvaluator) {
        this.llmEvaluator = llmEvaluator;
    }

    /**
     * Create an evaluator with fast path only (no LLM).
     */
    public ThematicCoherenceEvaluator() {
        this(null);
    }

    /**
     * Evaluate whether two items can compose into something coherent.
     *
     * @param item1 First item (must have thematic profile)
     * @param item2 Second item (must have thematic profile)
     * @return CoherenceResult with score, compatibility, and suggestions
     */
    public CoherenceResult evaluate(ToolItem item1, ToolItem item2) {
        // Check cache
        var cacheKey = cacheKey(item1, item2);
        var cached = cache.get(cacheKey);
        if (cached != null) return cached;

        var result = doEvaluate(item1, item2);
        cache.put(cacheKey, result);
        return result;
    }

    /**
     * Evaluate coherence between two thematic profiles directly.
     * Useful when items aren't fully constructed yet.
     */
    public CoherenceResult evaluateProfiles(ThematicProfile p1, String name1,
                                             ThematicProfile p2, String name2) {
        if (p1 == null || p2 == null) {
            return CoherenceResult.incompatible(0.0,
                "Cannot evaluate — missing thematic profile", "fast");
        }

        double overlap = p1.overlapWith(p2);
        return interpretScore(overlap, name1, name2, p1, p2);
    }

    /** Clear the evaluation cache. */
    public void clearCache() {
        cache.clear();
    }

    /** Number of cached evaluations. */
    public int cacheSize() {
        return cache.size();
    }

    // ─── Internal ────────────────────────────────────────────────

    private CoherenceResult doEvaluate(ToolItem item1, ToolItem item2) {
        var p1 = item1.thematic();
        var p2 = item2.thematic();

        // Fast path: attribute overlap
        if (p1 != null && p2 != null) {
            double overlap = p1.overlapWith(p2);

            if (overlap >= FAST_PATH_THRESHOLD) {
                // High overlap — fast path compatible
                var suggestion = suggestComposition(item1, item2, p1, p2);
                log.debug("Fast path: {} + {} → score={}, suggestion={}",
                    item1.name(), item2.name(), overlap, suggestion);
                return CoherenceResult.compatible(overlap, suggestion, "fast");
            }

            if (overlap <= INCOMPATIBLE_THRESHOLD && llmEvaluator == null) {
                // No overlap and no LLM — incompatible with binding hint
                var hint = suggestBindingElement(item1, item2, p1, p2);
                return CoherenceResult.incompatible(overlap, hint, "fast");
            }
        }

        // Slow path: LLM evaluation for novel compositions
        if (llmEvaluator != null) {
            return evaluateViaLlm(item1, item2);
        }

        // No profiles and no LLM — return moderate uncertainty
        return CoherenceResult.incompatible(0.1,
            "Cannot evaluate without thematic profiles or LLM", "fast");
    }

    private CoherenceResult evaluateViaLlm(ToolItem item1, ToolItem item2) {
        try {
            var desc1 = item1.name() + ": " + item1.description();
            var desc2 = item2.name() + ": " + item2.description();

            var response = llmEvaluator.apply(desc1, desc2);
            if (response == null || response.startsWith("[error]")) {
                log.warn("LLM coherence evaluation failed: {}", response);
                return CoherenceResult.incompatible(0.1,
                    "Evaluation failed — try again or provide a binding element", "llm");
            }

            // Parse LLM response — look for yes/no/maybe and extract suggestion
            var lower = response.toLowerCase();
            if (lower.contains("yes") || lower.contains("coherent") || lower.contains("compatible")) {
                return CoherenceResult.compatible(0.7, response, "llm");
            }
            if (lower.contains("no") || lower.contains("incompatible") || lower.contains("unrelated")) {
                return CoherenceResult.incompatible(0.2, response, "llm");
            }
            // Ambiguous — moderate score
            return new CoherenceResult(0.4, true, response, null, "llm");

        } catch (Exception e) {
            log.error("LLM coherence evaluation error: {}", e.getMessage());
            return CoherenceResult.incompatible(0.1, "Evaluation error: " + e.getMessage(), "llm");
        }
    }

    private String suggestComposition(ToolItem item1, ToolItem item2,
                                       ThematicProfile p1, ThematicProfile p2) {
        // Find shared domains and symbols to generate a suggestion
        var sharedDomains = p1.domains().stream()
            .filter(p2.domains()::contains).toList();
        var sharedSymbols = p1.symbols().stream()
            .filter(p2.symbols()::contains).toList();

        if (!sharedDomains.isEmpty()) {
            return "These items share the " + String.join("/", sharedDomains) +
                " domain — they can complement each other.";
        }
        if (!sharedSymbols.isEmpty()) {
            return "These items resonate through shared symbolism: " +
                String.join(", ", sharedSymbols) + ".";
        }
        return item1.name() + " and " + item2.name() + " have thematic overlap.";
    }

    private String suggestBindingElement(ToolItem item1, ToolItem item2,
                                          ThematicProfile p1, ThematicProfile p2) {
        // Suggest what kind of item could bridge the gap
        var domains1 = String.join("/", p1.domains());
        var domains2 = String.join("/", p2.domains());
        return "These items don't naturally connect. A binding element spanning " +
            domains1 + " and " + domains2 + " could bridge them — " +
            "or forge a new item that embodies both concepts.";
    }

    private CoherenceResult interpretScore(double overlap, String name1, String name2,
                                            ThematicProfile p1, ThematicProfile p2) {
        if (overlap >= FAST_PATH_THRESHOLD) {
            var shared = p1.symbols().stream().filter(p2.symbols()::contains).toList();
            var suggestion = !shared.isEmpty()
                ? "Shared resonance: " + String.join(", ", shared)
                : name1 + " and " + name2 + " are thematically compatible.";
            return CoherenceResult.compatible(overlap, suggestion, "fast");
        }
        if (overlap <= INCOMPATIBLE_THRESHOLD) {
            return CoherenceResult.incompatible(overlap,
                "No thematic connection. Create a bridge item or speak a binding.", "fast");
        }
        // Middle ground — technically possible but weak
        return new CoherenceResult(overlap, true,
            "Weak connection — a binding element would strengthen this composition.", null, "fast");
    }

    private static String cacheKey(ToolItem item1, ToolItem item2) {
        var id1 = item1.id() != null ? item1.id() : item1.name();
        var id2 = item2.id() != null ? item2.id() : item2.name();
        // Order-independent cache key
        return id1.compareTo(id2) <= 0 ? id1 + "+" + id2 : id2 + "+" + id1;
    }
}
