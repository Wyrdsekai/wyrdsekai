package org.wyrdsekai.core.economy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent reputation system (§98.6).
 * Aggregates multiple reputation signals into a composite score.
 * Used for A2A trust decisions, service pricing, and economic interactions.
 * <p>
 * Signals: age, transaction history, task completion, endorsements.
 */
public class AgentReputation {

    /** A signed endorsement from another entity. */
    public record Endorsement(
        @JsonProperty("endorserDid") String endorserDid,
        @JsonProperty("endorserType") EndorserType endorserType,
        @JsonProperty("message") String message,
        @JsonProperty("score") double score,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator
        public Endorsement {}
    }

    public enum EndorserType {
        STEWARD, PEER_AGENT, EXTERNAL_AGENT, ATTESTATION_SERVICE
    }

    /** Composite reputation score. */
    public record ReputationScore(
        double overall,
        double ageScore,
        double transactionScore,
        double completionScore,
        double endorsementScore,
        int endorsementCount
    ) {
        /** Whether this score meets a minimum threshold. */
        public boolean meetsThreshold(double threshold) {
            return overall >= threshold;
        }
    }

    private final String agentDid;
    private final Instant createdAt;
    private int tasksCompleted;
    private int tasksFailed;
    private int transactionsSuccessful;
    private int transactionsFailed;
    private final List<Endorsement> endorsements = new CopyOnWriteArrayList<>();

    public AgentReputation(String agentDid, Instant createdAt) {
        this.agentDid = agentDid;
        this.createdAt = createdAt;
    }

    /** Record a completed A2A task. */
    public void recordTaskCompletion(boolean success) {
        if (success) tasksCompleted++;
        else tasksFailed++;
    }

    /** Record a transaction outcome. */
    public void recordTransaction(boolean success) {
        if (success) transactionsSuccessful++;
        else transactionsFailed++;
    }

    /** Add an endorsement. */
    public void addEndorsement(Endorsement endorsement) {
        endorsements.add(endorsement);
    }

    /** Remove an endorsement. */
    public boolean removeEndorsement(String endorserDid) {
        return endorsements.removeIf(e -> e.endorserDid().equals(endorserDid));
    }

    /**
     * Compute the composite reputation score.
     * All sub-scores range [0.0, 1.0]. Overall is weighted average.
     */
    public ReputationScore computeScore() {
        // Age score: logarithmic, peaks at ~365 days
        long ageDays = (Instant.now().getEpochSecond() - createdAt.getEpochSecond()) / 86400;
        double ageScore = Math.min(1.0, Math.log1p(ageDays) / Math.log1p(365));

        // Transaction score: success rate (needs minimum 5 transactions)
        int totalTx = transactionsSuccessful + transactionsFailed;
        double txScore = totalTx >= 5
            ? (double) transactionsSuccessful / totalTx
            : 0.5; // default if insufficient history

        // Task completion score: success rate (needs minimum 3 tasks)
        int totalTasks = tasksCompleted + tasksFailed;
        double completionScore = totalTasks >= 3
            ? (double) tasksCompleted / totalTasks
            : 0.5;

        // Endorsement score: weighted by endorser type
        double endorsementScore = 0;
        if (!endorsements.isEmpty()) {
            double totalWeight = 0;
            for (var e : endorsements) {
                double weight = switch (e.endorserType()) {
                    case STEWARD -> 2.0;
                    case PEER_AGENT -> 1.0;
                    case EXTERNAL_AGENT -> 0.5;
                    case ATTESTATION_SERVICE -> 1.5;
                };
                endorsementScore += e.score() * weight;
                totalWeight += weight;
            }
            endorsementScore = totalWeight > 0 ? endorsementScore / totalWeight : 0;
        } else {
            endorsementScore = 0.0;
        }

        // Weighted average
        double overall = ageScore * 0.15 + txScore * 0.25
            + completionScore * 0.30 + endorsementScore * 0.30;

        return new ReputationScore(overall, ageScore, txScore,
            completionScore, endorsementScore, endorsements.size());
    }

    // ── Getters ──

    public String agentDid() { return agentDid; }
    public Instant createdAt() { return createdAt; }
    public int tasksCompleted() { return tasksCompleted; }
    public int tasksFailed() { return tasksFailed; }
    public int transactionsSuccessful() { return transactionsSuccessful; }
    public int transactionsFailed() { return transactionsFailed; }
    public List<Endorsement> endorsements() { return List.copyOf(endorsements); }
}
