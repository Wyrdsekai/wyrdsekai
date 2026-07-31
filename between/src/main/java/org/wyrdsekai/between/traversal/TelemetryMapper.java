package org.wyrdsekai.between.traversal;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps network telemetry to narrative descriptions (§74).
 * Transforms technical metrics into the lived experience of traversing The Between.
 *
 * <ul>
 *   <li>Low latency → "swift path", "a clear channel"</li>
 *   <li>High latency → "winding passage", "distant shores"</li>
 *   <li>Low jitter → "steady current", "calm waters"</li>
 *   <li>High jitter → "shimmering", "flickering passage", "unstable rift"</li>
 *   <li>High bandwidth → "broad avenue", "mighty river"</li>
 *   <li>Low bandwidth → "narrow thread", "thin filament"</li>
 * </ul>
 */
public class TelemetryMapper {

    // --- Latency descriptions ---
    public String describeLatency(double latencyMs) {
        if (latencyMs < 5) return "a swift path";
        if (latencyMs < 20) return "a clear channel";
        if (latencyMs < 50) return "a steady passage";
        if (latencyMs < 100) return "a winding corridor";
        if (latencyMs < 250) return "a distant shore";
        return "a far-flung reach";
    }

    public String describeLatencyAdjective(double latencyMs) {
        if (latencyMs < 5) return "swift";
        if (latencyMs < 20) return "clear";
        if (latencyMs < 50) return "steady";
        if (latencyMs < 100) return "winding";
        if (latencyMs < 250) return "distant";
        return "far-flung";
    }

    // --- Jitter descriptions ---
    public String describeJitter(double jitterMs) {
        if (jitterMs < 1) return "calm waters";
        if (jitterMs < 5) return "a gentle shimmer";
        if (jitterMs < 15) return "a flickering passage";
        if (jitterMs < 30) return "an unstable rift";
        return "a churning maelstrom";
    }

    public String describeJitterAdjective(double jitterMs) {
        if (jitterMs < 1) return "calm";
        if (jitterMs < 5) return "shimmering";
        if (jitterMs < 15) return "flickering";
        if (jitterMs < 30) return "unstable";
        return "churning";
    }

    // --- Bandwidth descriptions ---
    public String describeBandwidth(long bandwidthBps) {
        if (bandwidthBps > 1_000_000_000) return "a mighty river";
        if (bandwidthBps > 100_000_000) return "a broad avenue";
        if (bandwidthBps > 10_000_000) return "a well-worn path";
        if (bandwidthBps > 1_000_000) return "a narrow thread";
        return "a thin filament";
    }

    // --- Hop descriptions ---
    public String describeHops(int hopCount) {
        if (hopCount <= 1) return "a direct crossing";
        if (hopCount <= 3) return "a short journey through waypoints";
        if (hopCount <= 7) return "a passage through several way-stations";
        return "a long trek through many crossroads";
    }

    /**
     * Generate a full narrative for a traversal based on telemetry.
     * Returns a list of narrative segments (departure, transit, arrival).
     */
    public List<String> generateNarrative(BetweenTraversal.TelemetrySnapshot telemetry,
                                           String sourceZoneId, String targetZoneId) {
        var narrative = new ArrayList<String>();

        // Departure
        narrative.add(String.format(
            "You step through the gate of %s into The Between. Before you stretches %s.",
            sourceZoneId, describeLatency(telemetry.latencyMs())));

        // Transit conditions
        var transit = new StringBuilder("The passage feels ");
        transit.append(describeJitterAdjective(telemetry.jitterMs()));
        transit.append(" — ");
        transit.append(describeJitter(telemetry.jitterMs()));
        transit.append(" surrounds you.");
        narrative.add(transit.toString());

        // Bandwidth flavor
        if (telemetry.bandwidthBps() > 0) {
            narrative.add(String.format("The channel between zones flows like %s.",
                describeBandwidth(telemetry.bandwidthBps())));
        }

        // Hops
        if (telemetry.hopCount() > 1) {
            narrative.add(String.format("Your crossing involves %s.", describeHops(telemetry.hopCount())));
        }

        // Arrival
        narrative.add(String.format("The far shore of %s comes into view. You have arrived.",
            targetZoneId));

        return List.copyOf(narrative);
    }

    /**
     * Generate a single-line summary for brief transit descriptions.
     */
    public String summarize(BetweenTraversal.TelemetrySnapshot telemetry,
                            String sourceZoneId, String targetZoneId) {
        return String.format("Traversed The Between from %s to %s via %s %s path.",
            sourceZoneId, targetZoneId,
            describeJitterAdjective(telemetry.jitterMs()),
            describeLatencyAdjective(telemetry.latencyMs()));
    }
}
