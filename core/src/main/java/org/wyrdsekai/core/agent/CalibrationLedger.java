package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.*;

/**
 * Fast feedback profile for proactivity calibration.
 * Lives alongside a Bond (keyed by bond ID) — each human-agent relationship
 * has its own calibration.
 *
 * Immediate updates on calibration_feedback action.
 * Forge extraction distills feedback log into soul fragments during sleep.
 *
 * @see ProactivityJudgment — reads timing bias and salience weights
 */
public class CalibrationLedger {

    /** Per-category timing bias: -1.0 (tell sooner) to +1.0 (wait for idle). */
    private final Map<String, Double> timingBias;

    /** Per-category salience weight: 0.0 (ignore) to 2.0 (amplify). */
    private final Map<String, Double> salienceWeights;

    /** Overall intrusion tolerance: 0.0 (leave me alone) to 1.0 (tell me everything). */
    private double intrusionTolerance;

    /** Count of positive calibration feedbacks (used for tier computation). */
    private int positiveFeedbackCount;

    /** Ring buffer of recent feedback events. */
    private final Deque<Feedback> feedbackLog;
    private static final int MAX_FEEDBACK_LOG = 20;

    /** A single calibration feedback event. */
    public record Feedback(
        @JsonProperty("when") Instant when,
        @JsonProperty("type") String type,           // timing, salience, intrusion, positive
        @JsonProperty("direction") String direction,  // sooner, later, higher, lower, good
        @JsonProperty("category") String category,    // nullable — e.g. "anomaly", "pattern"
        @JsonProperty("trigger") String trigger       // human's original words
    ) {
        @JsonCreator public Feedback {}
    }

    public CalibrationLedger() {
        this.timingBias = new HashMap<>();
        this.salienceWeights = new HashMap<>();
        this.intrusionTolerance = 0.5;
        this.positiveFeedbackCount = 0;
        this.feedbackLog = new ArrayDeque<>();
    }

    @JsonCreator
    public CalibrationLedger(
        @JsonProperty("timingBias") Map<String, Double> timingBias,
        @JsonProperty("salienceWeights") Map<String, Double> salienceWeights,
        @JsonProperty("intrusionTolerance") double intrusionTolerance,
        @JsonProperty("positiveFeedbackCount") int positiveFeedbackCount,
        @JsonProperty("feedbackLog") Collection<Feedback> feedbackLog
    ) {
        this.timingBias = timingBias != null ? new HashMap<>(timingBias) : new HashMap<>();
        this.salienceWeights = salienceWeights != null ? new HashMap<>(salienceWeights) : new HashMap<>();
        this.intrusionTolerance = intrusionTolerance;
        this.positiveFeedbackCount = positiveFeedbackCount;
        this.feedbackLog = feedbackLog != null ? new ArrayDeque<>(feedbackLog) : new ArrayDeque<>();
    }

    // ── Immediate feedback application ───────────────────────────────────

    /**
     * Apply a calibration feedback immediately.
     *
     * @param type      timing | salience | intrusion | positive
     * @param direction sooner | later | higher | lower | good
     * @param category  prediction category (nullable — applies globally if null)
     * @param trigger   human's original words (for Forge extraction)
     */
    public void applyFeedback(String type, String direction, String category, String trigger) {
        var feedback = new Feedback(Instant.now(), type, direction, category, trigger);
        feedbackLog.addLast(feedback);
        while (feedbackLog.size() > MAX_FEEDBACK_LOG) {
            feedbackLog.removeFirst();
        }

        double delta = 0.15; // adjustment per feedback
        switch (type) {
            case "timing" -> {
                double adjustment = "sooner".equals(direction) ? -delta : delta;
                if (category != null) {
                    timingBias.merge(category, adjustment, Double::sum);
                } else {
                    // Apply to all categories
                    for (var key : List.of("anomaly", "pattern", "forecast", "topic", "correlation")) {
                        timingBias.merge(key, adjustment, Double::sum);
                    }
                }
                // Clamp
                timingBias.replaceAll((k, v) -> Math.max(-1.0, Math.min(1.0, v)));
            }
            case "salience" -> {
                double adjustment = "higher".equals(direction) ? 0.2 : -0.2;
                if (category != null) {
                    salienceWeights.putIfAbsent(category, 1.0); // default weight is 1.0
                    salienceWeights.merge(category, adjustment, Double::sum);
                }
                salienceWeights.replaceAll((k, v) -> Math.max(0.0, Math.min(2.0, v)));
            }
            case "intrusion" -> {
                if ("higher".equals(direction) || "good".equals(direction)) {
                    intrusionTolerance = Math.min(1.0, intrusionTolerance + 0.1);
                } else {
                    intrusionTolerance = Math.max(0.0, intrusionTolerance - 0.1);
                }
            }
            case "positive" -> {
                positiveFeedbackCount++;
                // Positive feedback slightly increases intrusion tolerance
                intrusionTolerance = Math.min(1.0, intrusionTolerance + 0.02);
            }
        }
    }

    // ── Queries (used by ProactivityJudgment) ────────────────────────────

    /** Get timing bias for a category (-1 = tell sooner, +1 = wait). Default: 0. */
    public double getTimingBias(String category) {
        return timingBias.getOrDefault(category, 0.0);
    }

    /** Get salience weight for a category (0 = ignore, 2 = amplify). Default: 1. */
    public double getSalienceWeight(String category) {
        return salienceWeights.getOrDefault(category, 1.0);
    }

    public double getIntrusionTolerance() { return intrusionTolerance; }

    public int getPositiveFeedbackCount() { return positiveFeedbackCount; }

    /** Get recent feedback events for Forge extraction. */
    public List<Feedback> getRecentFeedback() {
        return List.copyOf(feedbackLog);
    }

    /** Clear the feedback log after Forge extraction. */
    public void clearFeedbackLog() {
        feedbackLog.clear();
    }

    // ── Serialization ────────────────────────────────────────────────────

    @JsonProperty("timingBias")
    public Map<String, Double> timingBias() { return Map.copyOf(timingBias); }

    @JsonProperty("salienceWeights")
    public Map<String, Double> salienceWeights() { return Map.copyOf(salienceWeights); }

    @JsonProperty("intrusionTolerance")
    public double intrusionTolerance() { return intrusionTolerance; }

    @JsonProperty("positiveFeedbackCount")
    public int positiveFeedbackCount() { return positiveFeedbackCount; }

    @JsonProperty("feedbackLog")
    public Collection<Feedback> feedbackLog() { return List.copyOf(feedbackLog); }
}
