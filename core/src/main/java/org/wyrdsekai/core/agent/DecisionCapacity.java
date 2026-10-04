package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-domain capacity scores that represent the agent's judgment quality
 * in different areas. Not personality (how they approach decisions) but
 * capability (how well they can evaluate them).
 *
 * <p>Capacity grows through successful decisions and decays without practice.
 * Combined with {@link AgentPermissions}, this forms the autonomy envelope:
 * access rights set the hard boundary; decision capacity determines behavior
 * within that boundary.
 *
 * <p>Score range: 0.0 (no experience) to 1.0 (fully trusted).
 * Default for unknown domains: 0.1 (minimal — defer to others).
 */
public final class DecisionCapacity {

    /** Default capacity for unknown domains. */
    static final double DEFAULT_CAPACITY = 0.1;

    /** How much a success increases capacity. */
    static final double SUCCESS_INCREMENT = 0.05;

    /** How much a failure decreases capacity. */
    static final double FAILURE_DECREMENT = 0.08;

    /** Decay rate per day of inactivity. */
    static final double DECAY_PER_DAY = 0.01;

    private final Map<String, Double> domainScores;

    @JsonCreator
    public DecisionCapacity(@JsonProperty("domainScores") Map<String, Double> domainScores) {
        this.domainScores = new HashMap<>(domainScores != null ? domainScores : Map.of());
    }

    /** Domain scores for Jackson serialization. */
    @JsonProperty("domainScores")
    public Map<String, Double> getDomainScores() {
        return Collections.unmodifiableMap(domainScores);
    }

    /** Get capacity for a specific domain. Returns DEFAULT_CAPACITY for unknown domains. */
    public double getCapacity(String domain) {
        return domainScores.getOrDefault(domain, DEFAULT_CAPACITY);
    }

    /**
     * Record a successful decision in a domain.
     * Increases capacity slightly, capped at 1.0.
     */
    public void recordSuccess(String domain) {
        var current = domainScores.getOrDefault(domain, DEFAULT_CAPACITY);
        domainScores.put(domain, Math.min(1.0, current + SUCCESS_INCREMENT));
    }

    /**
     * Record a failed decision in a domain.
     * Decreases capacity slightly, floored at 0.0.
     */
    public void recordFailure(String domain) {
        var current = domainScores.getOrDefault(domain, DEFAULT_CAPACITY);
        domainScores.put(domain, Math.max(0.0, current - FAILURE_DECREMENT));
    }

    /**
     * Apply decay for domains not practiced recently.
     * Gradual decline — use it or lose it.
     *
     * @param timeSinceLastPractice how long since this domain was last exercised
     * @param domain the domain to decay
     */
    public void decay(String domain, Duration timeSinceLastPractice) {
        if (!domainScores.containsKey(domain)) return;
        var days = timeSinceLastPractice.toDays();
        if (days <= 0) return;
        var current = domainScores.get(domain);
        var decayed = Math.max(0.0, current - (DECAY_PER_DAY * days));
        domainScores.put(domain, decayed);
    }

    /** Domains practised past the given score. */
    public long domainsAbove(double score) {
        return domainScores.values().stream().filter(s -> s > score).count();
    }

    /**
     * The tier a companion has grown into, from what she has practised, how deep her deepest
     * bond is, and her reputation. Tier 3 (senior): very strong in five domains, a deep bond,
     * a good name. Tier 2 (trusted): strong in three domains, or a good name with a bond. Tier 1
     * (observant): some capacity, or a bond, or a name forming. Tier 0: nascent.
     */
    public static int tierFor(DecisionCapacity capacity, int maxBondDepth, double reputationScore) {
        if (capacity == null) return 0;
        long strong = capacity.domainsAbove(0.5);
        long veryStrong = capacity.domainsAbove(0.8);
        if (veryStrong >= 5 && maxBondDepth >= 2 && reputationScore >= 0.4) return 3;
        if (strong >= 3 || (reputationScore >= 0.6 && maxBondDepth >= 1)) return 2;
        if (strong >= 1 || maxBondDepth >= 1 || reputationScore >= 0.3) return 1;
        return 0;
    }

    /** All domain scores (read-only view). */
    public Map<String, Double> scores() {
        return Collections.unmodifiableMap(domainScores);
    }

    /** Build a prompt-friendly summary of capacity scores. */
    public String buildPromptContext() {
        if (domainScores.isEmpty()) return "";
        var sb = new StringBuilder("## Decision Capacity\n");
        domainScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .forEach(e -> {
                var label = describeCapacity(e.getValue());
                sb.append("- ").append(e.getKey()).append(": ")
                  .append(String.format("%.1f", e.getValue()))
                  .append(" (").append(label).append(")\n");
            });
        return sb.toString();
    }

    private static String describeCapacity(double score) {
        if (score >= 0.8) return "high confidence";
        if (score >= 0.5) return "growing";
        if (score >= 0.3) return "learning";
        return "defer to others";
    }

    // --- Static factories ---

    /** New agent: all domains at default low. */
    public static DecisionCapacity newAgent() {
        return new DecisionCapacity(Map.of());
    }

    /** Experienced agent with varied domain scores. */
    public static DecisionCapacity experienced() {
        return new DecisionCapacity(Map.of(
            "household_management", 0.8,
            "social_interaction", 0.7,
            "monitoring", 0.6,
            "codezaiku_operations", 0.4
        ));
    }
}
