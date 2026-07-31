package org.wyrdsekai.core.economy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compute Unit normalizer (§68).
 * Normalizes inference costs across different model tiers to a common unit (CU).
 * Uses Shapley-value-inspired cost sharing for multi-node inference.
 */
public class ComputeUnitNormalizer {

    /** Exchange rate: model tier → CU per token. */
    private final Map<String, Double> exchangeRates = new ConcurrentHashMap<>();

    /** Default rates per model tier (CU per 1K tokens). */
    public ComputeUnitNormalizer() {
        exchangeRates.put("phone", 0.1);       // Gemma 3n, very cheap
        exchangeRates.put("edge", 0.5);         // GPT-OSS-20B
        exchangeRates.put("desktop", 2.0);      // GPT-OSS-120B
        exchangeRates.put("cluster", 5.0);      // Kimi K2.5, full household
        exchangeRates.put("external", 10.0);     // Cloud API fallback
    }

    /** Set a custom exchange rate for a model tier. */
    public void setRate(String tier, double cuPerKToken) {
        exchangeRates.put(tier, cuPerKToken);
    }

    /** Get the exchange rate for a model tier. */
    public double getRate(String tier) {
        return exchangeRates.getOrDefault(tier, 1.0);
    }

    /** Normalize token count to CU for a given tier. */
    public double toCU(String tier, int tokens) {
        double rate = getRate(tier);
        return (tokens / 1000.0) * rate;
    }

    /**
     * Shapley-value cost sharing for multi-node inference.
     * Distributes cost proportionally to each node's contribution.
     *
     * @param totalCU      Total compute units consumed
     * @param nodeShares   Map of nodeId → proportion of work done (should sum to ~1.0)
     * @return Map of nodeId → CU share
     */
    public Map<String, Double> shapleyShare(double totalCU, Map<String, Double> nodeShares) {
        double totalShare = nodeShares.values().stream().mapToDouble(d -> d).sum();
        if (totalShare <= 0) return Map.of();

        var result = new ConcurrentHashMap<String, Double>();
        nodeShares.forEach((nodeId, share) ->
            result.put(nodeId, totalCU * (share / totalShare)));
        return result;
    }

    /** Get all configured exchange rates. */
    public Map<String, Double> allRates() {
        return Map.copyOf(exchangeRates);
    }

    /** Number of configured tiers. */
    public int tierCount() {
        return exchangeRates.size();
    }
}
