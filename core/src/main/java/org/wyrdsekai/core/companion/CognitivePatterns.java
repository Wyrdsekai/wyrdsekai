package org.wyrdsekai.core.companion;

import java.time.Instant;
import java.util.*;

/**
 * Anonymized cognitive pattern tracking (§99.6).
 * Tracks trends for visibility grants — never diagnostic.
 * The companion notices, never diagnoses.
 */
public class CognitivePatterns {

    /** A recorded pattern observation. */
    public record PatternObservation(
        String observationId,
        String agentDid,
        PatternType type,
        Instant observedAt,
        double confidence,
        String context
    ) {}

    public enum PatternType {
        /** Repeating the same question within a short time. */
        REPETITION,
        /** Difficulty finding words or names. */
        WORD_FINDING,
        /** Confusion about time or recent events. */
        TEMPORAL_CONFUSION,
        /** Forgetting recent conversations. */
        SHORT_TERM_MEMORY,
        /** Difficulty following multi-step tasks. */
        TASK_SEQUENCING,
        /** Unusual agitation or emotional volatility. */
        EMOTIONAL_VOLATILITY
    }

    /** A trend summary over a time period. */
    public record TrendSummary(
        String agentDid,
        Map<PatternType, Integer> patternCounts,
        Instant periodStart,
        Instant periodEnd,
        TrendDirection overallTrend
    ) {}

    public enum TrendDirection {
        STABLE, INCREASING, DECREASING
    }

    private final List<PatternObservation> observations = new ArrayList<>();
    private int nextId = 1;

    /** Record a pattern observation. */
    public PatternObservation record(String agentDid, PatternType type,
                                      double confidence, String context) {
        var obs = new PatternObservation("cog-" + nextId++, agentDid, type,
            Instant.now(), confidence, context);
        observations.add(obs);
        return obs;
    }

    /** Get recent observations for an agent. */
    public List<PatternObservation> recentFor(String agentDid, int limit) {
        return observations.stream()
            .filter(o -> o.agentDid().equals(agentDid))
            .sorted(Comparator.comparing(PatternObservation::observedAt).reversed())
            .limit(limit)
            .toList();
    }

    /** Generate a trend summary. Only includes high-confidence observations. */
    public TrendSummary summarize(String agentDid, Instant since) {
        var filtered = observations.stream()
            .filter(o -> o.agentDid().equals(agentDid))
            .filter(o -> o.observedAt().isAfter(since))
            .filter(o -> o.confidence() >= 0.5)
            .toList();

        var counts = new EnumMap<PatternType, Integer>(PatternType.class);
        for (var obs : filtered) {
            counts.merge(obs.type(), 1, Integer::sum);
        }

        // Simple trend: compare first half to second half
        var midpoint = since.plusMillis(
            (Instant.now().toEpochMilli() - since.toEpochMilli()) / 2);
        long firstHalf = filtered.stream()
            .filter(o -> o.observedAt().isBefore(midpoint)).count();
        long secondHalf = filtered.stream()
            .filter(o -> !o.observedAt().isBefore(midpoint)).count();

        TrendDirection trend;
        if (filtered.isEmpty()) trend = TrendDirection.STABLE;
        else if (secondHalf > firstHalf * 1.5) trend = TrendDirection.INCREASING;
        else if (firstHalf > secondHalf * 1.5) trend = TrendDirection.DECREASING;
        else trend = TrendDirection.STABLE;

        return new TrendSummary(agentDid, counts, since, Instant.now(), trend);
    }

    /** Generate an anonymized summary for visibility grants. No raw observations. */
    public String anonymizedSummary(String agentDid, Instant since) {
        var summary = summarize(agentDid, since);
        if (summary.patternCounts().isEmpty()) {
            return "No notable patterns observed in this period.";
        }

        var sb = new StringBuilder("Pattern summary:\n");
        for (var entry : summary.patternCounts().entrySet()) {
            sb.append("- ").append(describeType(entry.getKey()))
              .append(": ").append(entry.getValue()).append(" occurrences\n");
        }
        sb.append("Overall trend: ").append(summary.overallTrend().name().toLowerCase());
        return sb.toString();
    }

    public int observationCount() { return observations.size(); }

    private String describeType(PatternType type) {
        return switch (type) {
            case REPETITION -> "Repeated questions";
            case WORD_FINDING -> "Word-finding pauses";
            case TEMPORAL_CONFUSION -> "Time confusion";
            case SHORT_TERM_MEMORY -> "Recent memory gaps";
            case TASK_SEQUENCING -> "Multi-step difficulty";
            case EMOTIONAL_VOLATILITY -> "Emotional changes";
        };
    }
}
