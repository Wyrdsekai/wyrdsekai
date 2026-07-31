package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Ring buffer of observations with belief decay (OODA Observe phase).
 *
 * <p>Observations lose relevance over time. Room events decay faster (tau=300s)
 * than Oracle predictions (tau=3600s). The buffer is used to build context
 * for the Orient phase — contextualizing what's happening around the agent.</p>
 */
public class ObservationBuffer {

    private static final int MAX_SIZE = 50;

    public record Observation(
        String source,          // "event", "room", "oracle", "plan", "tell", "system"
        String content,         // human-readable summary
        double baseRelevance,   // 0.0-1.0 at time of observation
        Instant observedAt
    ) {
        /** Compute decayed relevance at the given time. */
        public double relevanceAt(Instant now) {
            var tau = tauForSource(source);
            var elapsed = Duration.between(observedAt, now).toSeconds();
            return baseRelevance * Math.exp(-elapsed / tau);
        }

        /** Decay constant per source type (seconds). */
        private static double tauForSource(String source) {
            return switch (source) {
                case "room" -> 300;       // 5 minutes
                case "event" -> 600;      // 10 minutes
                case "system" -> 600;
                case "oracle" -> 3600;    // 1 hour
                case "plan" -> 1800;      // 30 minutes
                case "tell" -> 900;       // 15 minutes
                default -> 600;
            };
        }
    }

    private final ArrayDeque<Observation> buffer = new ArrayDeque<>();

    /** Add an observation. Evicts oldest if at capacity. */
    public void observe(String source, String content, double relevance) {
        buffer.addLast(new Observation(source, content, relevance, Instant.now()));
        while (buffer.size() > MAX_SIZE) {
            buffer.removeFirst();
        }
    }

    /** Get all observations above a relevance threshold, sorted by decayed relevance. */
    public List<Observation> relevant(double minRelevance) {
        var now = Instant.now();
        return buffer.stream()
            .filter(o -> o.relevanceAt(now) >= minRelevance)
            .sorted((a, b) -> Double.compare(b.relevanceAt(now), a.relevanceAt(now)))
            .collect(Collectors.toList());
    }

    /** Get top N observations by relevance. */
    public List<Observation> top(int n) {
        var now = Instant.now();
        return buffer.stream()
            .sorted((a, b) -> Double.compare(b.relevanceAt(now), a.relevanceAt(now)))
            .limit(n)
            .collect(Collectors.toList());
    }

    /** Build prompt context from top observations. */
    public String buildContext(int maxObservations) {
        var top = top(maxObservations);
        if (top.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("## Recent Observations\n");
        var now = Instant.now();
        for (var obs : top) {
            var age = Duration.between(obs.observedAt(), now).toSeconds();
            var ageStr = age < 60 ? age + "s ago" : (age / 60) + "m ago";
            sb.append("- [").append(obs.source()).append(", ").append(ageStr).append("] ");
            sb.append(obs.content()).append("\n");
        }
        return sb.toString();
    }

    public int size() { return buffer.size(); }

    public void clear() { buffer.clear(); }
}
