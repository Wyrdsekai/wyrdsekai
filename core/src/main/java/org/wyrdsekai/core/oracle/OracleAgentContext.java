package org.wyrdsekai.core.oracle;

import java.util.List;

/**
 * Builds Oracle prediction context for injection into agent prompts.
 *
 * Called by PromptAssembler at Layer 3.5 (after TimeContext, before Vitality).
 * Produces a compact text summary of the most relevant predictions.
 *
 * Token budget: ~50-100 tokens. Only top predictions included.
 */
public final class OracleAgentContext {

    private OracleAgentContext() {}

    /** Maximum predictions to include in prompt context. */
    private static final int MAX_PREDICTIONS = 5;

    /**
     * Build context string from Oracle predictions.
     *
     * @param predictions Recent predictions (from OracleForgeHook or cached)
     * @return Context string for prompt injection, or empty string if none
     */
    public static String build(List<OraclePrediction> predictions) {
        if (predictions == null || predictions.isEmpty()) {
            return "";
        }

        var relevant = predictions.stream()
            .filter(p -> p.confidence() >= 0.5)
            .sorted((a, b) -> Double.compare(b.confidence(), a.confidence()))
            .limit(MAX_PREDICTIONS)
            .toList();

        if (relevant.isEmpty()) return "";

        var sb = new StringBuilder("Oracle insights:");
        for (int i = 0; i < relevant.size(); i++) {
            var p = relevant.get(i);
            sb.append(" (").append(i + 1).append(") ").append(p.text());
            if (p.actionable()) sb.append(" [actionable]");
            sb.append(".");
        }

        return sb.toString();
    }
}
