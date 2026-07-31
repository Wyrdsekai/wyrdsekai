package org.wyrdsekai.core.agent.classifier;

import java.util.Map;

/**
 * Result of a classifier head invocation.
 *
 * @param label      top-1 predicted label
 * @param confidence softmax probability of top-1 label (0..1)
 * @param probs      full label→probability map (for multi-label use cases)
 * @param source     which layer produced this — "L1" (fast sklearn), "L2" (LLM fallback), or "null" (no model)
 * @param eventId    UUID of the logged classifier event (if any). Callers stash this
 *                   so they can later call {@link ClassifierEventLog#markOutcome}
 *                   when the outcome of the routing decision is known
 *                   (goal_done → POSITIVE, abort → NEGATIVE). Null when no event
 *                   was logged (unavailable, blank text, event log missing).
 */
public record Classification(
    String label,
    double confidence,
    Map<String, Double> probs,
    String source,
    String eventId
) {
    /** Three-arg ctor kept for callers that don't want eventId yet. */
    public Classification(String label, double confidence,
                           Map<String, Double> probs, String source) {
        this(label, confidence, probs, source, null);
    }

    /** Sentinel for "no classifier available" — e.g., cold-start before models load. */
    public static Classification unavailable() {
        return new Classification(null, 0.0, Map.of(), "null", null);
    }
}
