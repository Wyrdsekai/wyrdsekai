package org.wyrdsekai.core.inference;

import java.util.Map;

/**
 * Estimates the USD cost of an inference call based on model name and token counts.
 * Uses a simple per-model price table (USD per 1M tokens, as of early 2026).
 *
 * <p>Models are matched by substring containment against the model string,
 * so "claude-3-5-sonnet-20241022" matches the "claude-sonnet" entry.
 * If no entry matches, a conservative default ($1/M input, $3/M output) is used.
 */
public final class InferenceCostEstimator {

    /** Input token prices in USD per 1M tokens. */
    static final Map<String, Double> INPUT_PRICES = Map.of(
        "claude-sonnet", 3.0,
        "claude-opus", 15.0,
        "claude-haiku", 0.25,
        "gpt-4o-mini", 0.15,
        "gpt-4o", 2.5,
        "deepseek-chat", 0.27,
        "deepseek-reasoner", 0.55
    );

    /** Output token prices in USD per 1M tokens. */
    static final Map<String, Double> OUTPUT_PRICES = Map.of(
        "claude-sonnet", 15.0,
        "claude-opus", 75.0,
        "claude-haiku", 1.25,
        "gpt-4o-mini", 0.60,
        "gpt-4o", 10.0,
        "deepseek-chat", 1.10,
        "deepseek-reasoner", 2.19
    );

    /** Default prices when model is unknown (conservative estimate). */
    static final double DEFAULT_INPUT_PRICE = 1.0;
    static final double DEFAULT_OUTPUT_PRICE = 3.0;

    private InferenceCostEstimator() {} // utility class

    /**
     * Estimate the USD cost for an inference call.
     *
     * @param model         model name string (e.g., "claude-3-5-sonnet-20241022")
     * @param inputTokens   number of input (prompt) tokens
     * @param outputTokens  number of output (completion) tokens
     * @return estimated cost in USD
     */
    public static double estimateCostUSD(String model, int inputTokens, int outputTokens) {
        if (inputTokens == 0 && outputTokens == 0) return 0.0;

        var modelLower = model != null ? model.toLowerCase() : "";

        var inputPrice = findPrice(modelLower, INPUT_PRICES, DEFAULT_INPUT_PRICE);
        var outputPrice = findPrice(modelLower, OUTPUT_PRICES, DEFAULT_OUTPUT_PRICE);

        return (inputTokens * inputPrice + outputTokens * outputPrice) / 1_000_000.0;
    }

    private static double findPrice(String modelLower, Map<String, Double> prices,
                                     double defaultPrice) {
        // Match by checking that ALL hyphen-separated parts of the key appear in the model name.
        // e.g., key "claude-sonnet" matches "claude-3-5-sonnet-20241022" because both
        // "claude" and "sonnet" are present. Longer keys (more parts) are preferred
        // to avoid "gpt-4o" matching before "gpt-4o-mini".
        return prices.entrySet().stream()
                .filter(e -> allPartsMatch(modelLower, e.getKey()))
                .sorted((a, b) -> Integer.compare(
                        b.getKey().split("-").length,
                        a.getKey().split("-").length))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(defaultPrice);
    }

    private static boolean allPartsMatch(String modelLower, String key) {
        for (var part : key.split("-")) {
            if (!modelLower.contains(part)) return false;
        }
        return true;
    }
}
