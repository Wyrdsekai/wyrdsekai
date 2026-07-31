package org.wyrdsekai.core.governance;

import java.time.Instant;
import java.util.Optional;

/**
 * A task posted to a room blackboard for opportunistic self-organization (§4.3).
 * Agents claim tasks atomically — first claim wins.
 */
public record BlackboardTask(
    String id,
    String description,
    String roomId,
    String postedBy,
    TaskState state,
    String claimedBy,
    Instant createdAt,
    Instant claimedAt,
    Instant completedAt,
    double relevanceScore  // set by AgentEvaluator (0.0-1.0)
) {
    public enum TaskState {
        OPEN, CLAIMED, COMPLETED, CANCELLED
    }

    /** Create a new open task. */
    public static BlackboardTask create(String id, String description, String roomId, String postedBy) {
        return new BlackboardTask(id, description, roomId, postedBy,
            TaskState.OPEN, null, Instant.now(), null, null, 0.0);
    }

    /** Attempt to claim this task. Fails if already claimed. */
    public Optional<BlackboardTask> claim(String agentId) {
        if (state != TaskState.OPEN) return Optional.empty();
        return Optional.of(new BlackboardTask(id, description, roomId, postedBy,
            TaskState.CLAIMED, agentId, createdAt, Instant.now(), null, relevanceScore));
    }

    /** Mark task as completed. Only the claimant can complete it. */
    public Optional<BlackboardTask> complete(String agentId) {
        if (state != TaskState.CLAIMED || !agentId.equals(claimedBy)) return Optional.empty();
        return Optional.of(new BlackboardTask(id, description, roomId, postedBy,
            TaskState.COMPLETED, claimedBy, createdAt, claimedAt, Instant.now(), relevanceScore));
    }

    /** Cancel the task. Only the poster or claimant can cancel. */
    public Optional<BlackboardTask> cancel(String entityId) {
        if (state == TaskState.COMPLETED || state == TaskState.CANCELLED) return Optional.empty();
        if (!entityId.equals(postedBy) && !entityId.equals(claimedBy)) return Optional.empty();
        return Optional.of(new BlackboardTask(id, description, roomId, postedBy,
            TaskState.CANCELLED, claimedBy, createdAt, claimedAt, Instant.now(), relevanceScore));
    }

    /** Set relevance score (from AgentEvaluator). */
    public BlackboardTask withRelevance(double score) {
        return new BlackboardTask(id, description, roomId, postedBy,
            state, claimedBy, createdAt, claimedAt, completedAt,
            Math.max(0.0, Math.min(1.0, score)));
    }

    public boolean isOpen() { return state == TaskState.OPEN; }
    public boolean isClaimed() { return state == TaskState.CLAIMED; }

    public String describe() {
        return String.format("[%s] %s (%s)%s — posted by %s",
            id, description, state,
            claimedBy != null ? " claimed by " + claimedBy : "",
            postedBy);
    }
}
