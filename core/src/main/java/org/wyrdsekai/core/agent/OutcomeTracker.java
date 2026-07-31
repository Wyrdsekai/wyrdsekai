package org.wyrdsekai.core.agent;

import java.time.Instant;
import java.util.*;

/**
 * Tracks predicted vs actual outcomes for confidence calibration.
 *
 * <p>Research shows agents overestimate success 73% vs 35% actual.
 * This tracker compares predictions against reality and computes a
 * calibration score that adjusts escalation thresholds and
 * DecisionCapacity growth rates.</p>
 */
public class OutcomeTracker {

    private static final int MAX_RECORDS = 100;

    public record OutcomeRecord(
        String planId,
        String goalDescription,
        String domain,              // "navigation", "search", "communication"
        boolean predictedSuccess,   // did the agent think it would work?
        boolean actualSuccess,      // did it actually work?
        Instant timestamp
    ) {}

    private final ArrayDeque<OutcomeRecord> records = new ArrayDeque<>();

    /** Record a predicted vs actual outcome. */
    public void record(String planId, String goalDescription, String domain,
                       boolean predicted, boolean actual) {
        records.addLast(new OutcomeRecord(planId, goalDescription, domain,
            predicted, actual, Instant.now()));
        while (records.size() > MAX_RECORDS) {
            records.removeFirst();
        }
    }

    /**
     * Overall calibration score (0.0-1.0).
     * 1.0 = perfect calibration (predictions match reality).
     * 0.0 = maximally miscalibrated.
     */
    public double calibrationScore() {
        if (records.size() < 5) return 0.5; // insufficient data
        double predictedRate = records.stream()
            .filter(r -> r.predictedSuccess).count() / (double) records.size();
        double actualRate = records.stream()
            .filter(r -> r.actualSuccess).count() / (double) records.size();
        return 1.0 - Math.abs(predictedRate - actualRate);
    }

    /**
     * Domain-specific calibration score.
     */
    public double calibrationScore(String domain) {
        var domainRecords = records.stream()
            .filter(r -> domain.equals(r.domain)).toList();
        if (domainRecords.size() < 3) return 0.5;
        double predictedRate = domainRecords.stream()
            .filter(r -> r.predictedSuccess).count() / (double) domainRecords.size();
        double actualRate = domainRecords.stream()
            .filter(r -> r.actualSuccess).count() / (double) domainRecords.size();
        return 1.0 - Math.abs(predictedRate - actualRate);
    }

    /** Actual success rate overall. */
    public double actualSuccessRate() {
        if (records.isEmpty()) return 0.5;
        return records.stream().filter(r -> r.actualSuccess).count() / (double) records.size();
    }

    /** Predicted success rate overall. */
    public double predictedSuccessRate() {
        if (records.isEmpty()) return 0.5;
        return records.stream().filter(r -> r.predictedSuccess).count() / (double) records.size();
    }

    /**
     * Whether the agent is overconfident (predicts success much more than actual).
     */
    public boolean isOverconfident() {
        if (records.size() < 10) return false;
        return predictedSuccessRate() - actualSuccessRate() > 0.2;
    }

    /**
     * Suggested retry adjustment for a domain.
     * Returns negative if calibration is low (reduce retries),
     * positive if calibration is high (allow more retries).
     */
    public int retryAdjustment(String domain) {
        var cal = calibrationScore(domain);
        if (cal < 0.4) return -1; // poor calibration → fewer retries
        if (cal > 0.8) return 1;  // good calibration → more retries
        return 0;
    }

    public int recordCount() { return records.size(); }

    public List<OutcomeRecord> recentRecords(int n) {
        var list = new ArrayList<>(records);
        return list.subList(Math.max(0, list.size() - n), list.size());
    }
}
