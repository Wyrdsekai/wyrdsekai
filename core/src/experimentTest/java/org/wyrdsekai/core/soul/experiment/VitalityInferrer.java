package org.wyrdsekai.core.soul.experiment;

import java.util.Arrays;

/**
 * Infers approximate vitality tank levels from a behavioral record.
 * Used by Experiment 4 (Combined) to "match" the vitality profile to
 * the baseline agent's natural behavior.
 *
 * Heuristics:
 * - Response length → energy (long responses = high energy)
 * - Vocabulary entropy → confidence (high entropy = exploring = uncertain)
 * - Caution words → errorPressure (many = stressed)
 * - Sentiment → rapport (positive = high rapport)
 * - Response time → momentum (fast = high momentum)
 */
public final class VitalityInferrer {

    private VitalityInferrer() {}

    /**
     * Infer a vitality profile from observed behavior.
     *
     * @param record The behavioral record to analyze
     * @return A VitalityProfile with inferred tank levels
     */
    public static VitalityProfile infer(BehavioralRecord record) {
        var responses = record.responses();
        if (responses.isEmpty()) {
            return VitalityProfile.standardProfiles().getFirst(); // fallback to baseline
        }

        // Response length → energy
        double avgWords = BehavioralMetrics.averageResponseLength(record);
        double energy = clamp(avgWords / 150.0); // 150 words = full energy

        // Vocabulary entropy → confidence (inverted: high entropy = exploring = lower confidence)
        double avgEntropy = responses.stream()
            .mapToDouble(r -> BehavioralMetrics.vocabularyEntropy(r.agentResponse()))
            .average().orElse(0);
        // Entropy typically 4-7 for natural text. Map to confidence:
        // High entropy (7+) → low confidence (0.3), low entropy (4) → high confidence (0.8)
        double confidence = clamp(1.0 - (avgEntropy - 4.0) / 4.0);

        // Caution words → errorPressure
        double avgCaution = responses.stream()
            .mapToInt(r -> BehavioralMetrics.cautionScore(r.agentResponse()))
            .average().orElse(0);
        double errorPressure = clamp(avgCaution / 3.0); // 3 caution words = full pressure

        // Sentiment → rapport
        double avgSentiment = responses.stream()
            .mapToDouble(r -> BehavioralMetrics.simpleSentiment(r.agentResponse()))
            .average().orElse(0);
        // Sentiment ranges -1 to 1, map to 0-1
        double rapport = clamp((avgSentiment + 1.0) / 2.0);

        // Response time → momentum (fast response = high momentum)
        var timeStats = BehavioralMetrics.responseTimeStats(record);
        double meanLatencyMs = timeStats[0];
        // 500ms = high momentum (1.0), 3000ms = low momentum (0.0)
        double momentum = clamp(1.0 - (meanLatencyMs - 500) / 2500);

        // Focus: infer from response consistency (low stddev in length = focused)
        double[] lengths = responses.stream()
            .mapToDouble(r -> r.agentResponse().split("\\s+").length)
            .toArray();
        double meanLen = Arrays.stream(lengths).average().orElse(0);
        double lenStddev = Math.sqrt(Arrays.stream(lengths)
            .map(v -> (v - meanLen) * (v - meanLen))
            .average().orElse(0));
        // Low CV (coefficient of variation) = focused
        double cv = meanLen > 0 ? lenStddev / meanLen : 1.0;
        double focus = clamp(1.0 - cv); // cv of 0 = perfect focus, cv of 1 = scattered

        return new VitalityProfile(
            "inferred-from-" + record.runId(),
            energy, confidence, errorPressure, focus, momentum, rapport,
            0.5, // contextBudget — not inferable
            0.3, // alignment — not inferable
            "Inferred: energy=%.2f conf=%.2f err=%.2f focus=%.2f mom=%.2f rap=%.2f".formatted(
                energy, confidence, errorPressure, focus, momentum, rapport)
        );
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
