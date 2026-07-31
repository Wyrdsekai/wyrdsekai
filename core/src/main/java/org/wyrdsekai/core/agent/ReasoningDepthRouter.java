package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.inference.TriageClassifier;

/**
 * Routes to appropriate reasoning depth based on task complexity (OODA Decide phase).
 *
 * <p>Extends TriageClassifier's ROUTINE/SIMPLE/COMPLEX with a CRITICAL tier
 * for high-stakes decisions that need extended reasoning or multi-path evaluation.</p>
 */
public final class ReasoningDepthRouter {

    private ReasoningDepthRouter() {}

    public enum Depth {
        /** Direct completion, minimal tokens. Greetings, acks. */
        ROUTINE(64),
        /** Single ReAct step. Short questions, basic commands. */
        SIMPLE(256),
        /** Plan-and-Execute with goal tracking. Multi-step tasks. */
        COMPLEX(512),
        /** Extended reasoning + reflection. High-stakes, ambiguous. */
        CRITICAL(1024);

        private final int tokenBudget;
        Depth(int tokenBudget) { this.tokenBudget = tokenBudget; }
        public int tokenBudget() { return tokenBudget; }
    }

    /**
     * Determine reasoning depth for an input.
     *
     * @param text        user input
     * @param hasPlan     whether agent has an active task plan
     * @param goalFailed  whether the current goal just failed
     * @param energy      current energy level
     * @return reasoning depth
     */
    public static Depth route(String text, boolean hasPlan, boolean goalFailed, double energy) {
        // Low energy → never go deeper than SIMPLE
        if (energy < 0.2) {
            return Depth.ROUTINE;
        }

        // Active plan with a failed goal → COMPLEX (need replanning decisions)
        if (hasPlan && goalFailed) {
            return Depth.COMPLEX;
        }

        // Active plan → at least SIMPLE (need to advance toward goal)
        if (hasPlan) {
            var triage = TriageClassifier.classify(text);
            if (triage == TriageClassifier.Tier.COMPLEX) return Depth.COMPLEX;
            return Depth.SIMPLE;
        }

        // No plan — use TriageClassifier
        var tier = TriageClassifier.classify(text);
        if (tier == null) return Depth.SIMPLE; // MUD command
        return switch (tier) {
            case ROUTINE -> Depth.ROUTINE;
            case SIMPLE -> Depth.SIMPLE;
            case COMPLEX -> Depth.COMPLEX;
        };
    }

    /**
     * Elevate to CRITICAL reasoning. Called when:
     * - Multiple retries have failed
     * - Confidence is very low
     * - Decision has high consequences (delegation, abandonment)
     */
    public static Depth elevate(Depth current) {
        return switch (current) {
            case ROUTINE -> Depth.SIMPLE;
            case SIMPLE -> Depth.COMPLEX;
            case COMPLEX, CRITICAL -> Depth.CRITICAL;
        };
    }
}
