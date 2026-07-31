package org.wyrdsekai.core.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OODA Orient phase — contextualizes observations using memory, predictions,
 * and active plans. Produces ranked contextualized observations for the Decide phase.
 */
public final class OrientationEngine {

    private OrientationEngine() {}

    /**
     * A contextualized observation — observation + why it matters.
     */
    public record ContextualizedObservation(
        ObservationBuffer.Observation observation,
        String significance,     // why this matters right now
        double confidence,       // how sure we are this matters
        String planImpact        // nullable — which goal this affects
    ) {}

    /**
     * Contextualize observations against the current agent state.
     *
     * @param observations   raw observations from buffer
     * @param activePlan     current task plan (nullable)
     * @param calibration    user preference calibration (nullable)
     * @return ranked list of contextualized observations
     */
    public static List<ContextualizedObservation> orient(
        List<ObservationBuffer.Observation> observations,
        TaskPlan activePlan,
        CalibrationLedger calibration
    ) {
        var results = new ArrayList<ContextualizedObservation>();

        for (var obs : observations) {
            var significance = assessSignificance(obs, activePlan);
            var confidence = assessConfidence(obs);
            var planImpact = assessPlanImpact(obs, activePlan);

            // Apply calibration — user cares more about certain categories
            if (calibration != null) {
                var salienceWeight = calibration.salienceWeights()
                    .getOrDefault(obs.source(), 1.0);
                confidence *= salienceWeight;
            }

            results.add(new ContextualizedObservation(
                obs, significance, confidence, planImpact));
        }

        // Sort by confidence (highest first)
        results.sort(Comparator.comparingDouble(
            (ContextualizedObservation c) -> c.confidence()).reversed());

        return results;
    }

    // --- Internal ---

    private static String assessSignificance(ObservationBuffer.Observation obs, TaskPlan plan) {
        // Plan-relevant observations get highest significance
        if (plan != null && plan.isActive()) {
            var goal = plan.currentGoal();
            if (goal != null) {
                var goalLower = goal.description().toLowerCase();
                var obsLower = obs.content().toLowerCase();
                // Check keyword overlap
                for (var word : goalLower.split("\\s+")) {
                    if (word.length() > 3 && obsLower.contains(word)) {
                        return "Relevant to current goal: " + goal.description();
                    }
                }
            }
        }

        // Source-based significance
        return switch (obs.source()) {
            case "tell" -> "Direct message requiring response";
            case "oracle" -> "Prediction that may require action";
            case "system" -> "System status change";
            case "room" -> "Activity in current environment";
            case "event" -> "External event";
            default -> "Background observation";
        };
    }

    private static double assessConfidence(ObservationBuffer.Observation obs) {
        // Direct messages and system events are high confidence
        return switch (obs.source()) {
            case "tell" -> 0.95;
            case "system" -> 0.9;
            case "oracle" -> obs.baseRelevance(); // oracle provides its own confidence
            case "room" -> 0.7;
            case "plan" -> 0.8;
            default -> 0.5;
        };
    }

    private static String assessPlanImpact(ObservationBuffer.Observation obs, TaskPlan plan) {
        if (plan == null || !plan.isActive()) return null;

        var obsLower = obs.content().toLowerCase();
        for (int i = plan.currentGoalIndex(); i < plan.goals().size(); i++) {
            var goal = plan.goals().get(i);
            var goalLower = goal.description().toLowerCase();
            for (var word : goalLower.split("\\s+")) {
                if (word.length() > 3 && obsLower.contains(word)) {
                    return "Affects goal " + (i + 1) + ": " + goal.description();
                }
            }
        }
        return null;
    }
}
