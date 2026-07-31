package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Controls when and how a companion may proactively use skills
 * without explicit human request.
 *
 * The policy defines which skill patterns are proactive-eligible,
 * vitality thresholds, and a windowed rate limit. The companion's
 * LLM decides whether to act; this policy gates whether the
 * proactivity context is injected into Layer 2.7.
 */
public final class ProactivityPolicy {

    private final List<String> proactivePatterns;
    private final double minEnergy;
    private final double minConfidence;
    private final int maxPerWindow;
    private final Duration windowSize;

    // Window tracking (mutable, thread-safe)
    private final AtomicInteger actionsInWindow = new AtomicInteger(0);
    private final AtomicReference<Instant> windowStart = new AtomicReference<>(Instant.now());

    public ProactivityPolicy(List<String> proactivePatterns,
                               double minEnergy,
                               double minConfidence,
                               int maxPerWindow,
                               Duration windowSize) {
        this.proactivePatterns = proactivePatterns != null ? List.copyOf(proactivePatterns) : List.of();
        this.minEnergy = minEnergy;
        this.minConfidence = minConfidence;
        this.maxPerWindow = maxPerWindow;
        this.windowSize = windowSize != null ? windowSize : Duration.ofMinutes(10);
    }

    /** Server default: lower thresholds, more autonomy. */
    public static ProactivityPolicy serverDefault(List<String> patterns) {
        return new ProactivityPolicy(patterns, 0.4, 0.5, 3, Duration.ofMinutes(10));
    }

    /** Phone default: higher thresholds, less autonomy. */
    public static ProactivityPolicy phoneDefault(List<String> patterns) {
        return new ProactivityPolicy(patterns, 0.6, 0.5, 2, Duration.ofMinutes(10));
    }

    /** Disabled policy — no proactive skills. */
    public static ProactivityPolicy disabled() {
        return new ProactivityPolicy(List.of(), 1.0, 1.0, 0, Duration.ofMinutes(10));
    }

    // --- Queries ---

    public List<String> proactivePatterns() { return proactivePatterns; }
    public double minEnergy() { return minEnergy; }
    public double minConfidence() { return minConfidence; }
    public int maxPerWindow() { return maxPerWindow; }
    public Duration windowSize() { return windowSize; }

    /**
     * Whether proactive skills should be shown in capability context
     * given the current vitality state.
     */
    public boolean isActive(double energy, double confidence) {
        if (proactivePatterns.isEmpty()) return false;
        return energy >= minEnergy && confidence >= minConfidence;
    }

    /**
     * Whether a specific skill ID matches any proactive pattern.
     * Uses glob-style matching: "hearth.*" matches "hearth.ha.set-light".
     */
    public boolean matchesPattern(String skillId) {
        if (skillId == null || proactivePatterns.isEmpty()) return false;
        for (var pattern : proactivePatterns) {
            if (globMatch(pattern, skillId)) return true;
        }
        return false;
    }

    /**
     * How many proactive actions remain in the current window.
     * Resets the window if it has expired.
     */
    public int remainingInWindow() {
        resetWindowIfExpired();
        return Math.max(0, maxPerWindow - actionsInWindow.get());
    }

    /**
     * Record a proactive action. Returns true if within budget,
     * false if the window budget is exhausted.
     */
    public boolean recordProactiveAction() {
        resetWindowIfExpired();
        int current = actionsInWindow.incrementAndGet();
        return current <= maxPerWindow;
    }

    /**
     * Build the proactivity section for Layer 2.7 capability context.
     *
     * @param energy     Current energy level
     * @param confidence Current confidence level
     * @return Proactivity context string, or null if inactive
     */
    public String buildContextSection(double energy, double confidence) {
        if (!isActive(energy, confidence)) return null;
        int remaining = remainingInWindow();
        if (remaining <= 0) return null;

        var sb = new StringBuilder();
        sb.append("## Proactive Skills (you may use these unprompted when context suggests it)\n");
        for (var pattern : proactivePatterns) {
            sb.append("- ").append(pattern).append("\n");
        }
        sb.append("Budget: ").append(remaining)
          .append(" of ").append(maxPerWindow)
          .append(" proactive actions remaining this window.\n");
        return sb.toString();
    }

    // --- Internal ---

    private void resetWindowIfExpired() {
        var start = windowStart.get();
        if (start != null && Duration.between(start, Instant.now()).compareTo(windowSize) > 0) {
            windowStart.set(Instant.now());
            actionsInWindow.set(0);
        }
    }

    /** Simple glob matching: "*" at end matches any suffix. */
    static boolean globMatch(String pattern, String skillId) {
        if (pattern.equals(skillId)) return true;
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return skillId.startsWith(prefix);
        }
        return false;
    }
}
