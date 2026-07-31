package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Tracks an agent's commitments — things it promised to do.
 * Thread-confined to the CompanionActor (no synchronization needed).
 *
 * <p>Max 20 active commitments. Oldest pending commitments are expired
 * if the limit is exceeded. Commitments older than 7 days with no deadline
 * are automatically expired.
 */
public final class CommitmentTracker {

    private static final Logger log = LoggerFactory.getLogger(CommitmentTracker.class);

    /** Maximum number of active (non-terminal) commitments. */
    static final int MAX_ACTIVE = 20;

    /** Commitments without a deadline expire after this duration. */
    static final Duration DEFAULT_EXPIRY = Duration.ofDays(7);

    private final List<Commitment> commitments = new ArrayList<>();

    /**
     * Add a new commitment.
     *
     * @param description What the agent committed to
     * @param context     Why (conversation context)
     * @param deadline    When it should be done (nullable)
     * @return the created commitment, or null if the description is blank
     */
    public Commitment add(String description, String context, Instant deadline) {
        if (description == null || description.isBlank()) {
            return null;
        }

        // Enforce max active limit — expire oldest pending if at capacity
        var active = commitments.stream()
            .filter(c -> c.status() == Commitment.CommitmentStatus.PENDING
                      || c.status() == Commitment.CommitmentStatus.IN_PROGRESS)
            .count();
        if (active >= MAX_ACTIVE) {
            // Expire the oldest pending commitment to make room
            for (int i = 0; i < commitments.size(); i++) {
                if (commitments.get(i).status() == Commitment.CommitmentStatus.PENDING) {
                    var old = commitments.get(i);
                    commitments.set(i, old.withStatus(Commitment.CommitmentStatus.EXPIRED));
                    log.info("Expired oldest commitment to make room: '{}'", old.description());
                    break;
                }
            }
        }

        var commitment = new Commitment(
            UUID.randomUUID().toString(),
            description.strip(),
            context != null ? context.strip() : "",
            Instant.now(),
            deadline,
            Commitment.CommitmentStatus.PENDING
        );
        commitments.add(commitment);
        log.debug("Commitment added: '{}' (deadline={})", description,
            deadline != null ? deadline : "none");
        return commitment;
    }

    /**
     * Mark a commitment as completed.
     *
     * @return true if the commitment was found and completed
     */
    public boolean complete(String id) {
        for (int i = 0; i < commitments.size(); i++) {
            var c = commitments.get(i);
            if (c.id().equals(id) && (c.status() == Commitment.CommitmentStatus.PENDING
                                   || c.status() == Commitment.CommitmentStatus.IN_PROGRESS)) {
                commitments.set(i, c.withStatus(Commitment.CommitmentStatus.COMPLETED));
                log.debug("Commitment completed: '{}'", c.description());
                return true;
            }
        }
        return false;
    }

    /** Get all pending commitments (PENDING or IN_PROGRESS). */
    public List<Commitment> getPending() {
        return commitments.stream()
            .filter(c -> c.status() == Commitment.CommitmentStatus.PENDING
                      || c.status() == Commitment.CommitmentStatus.IN_PROGRESS)
            .toList();
    }

    /** Get overdue commitments (past deadline and still pending). */
    public List<Commitment> getOverdue() {
        return commitments.stream()
            .filter(Commitment::isOverdue)
            .toList();
    }

    /**
     * Expire old pending commitments that have no deadline and are older than 7 days.
     *
     * @return number of commitments expired
     */
    public int expire() {
        int count = 0;
        var cutoff = Instant.now().minus(DEFAULT_EXPIRY);
        for (int i = 0; i < commitments.size(); i++) {
            var c = commitments.get(i);
            if (c.status() == Commitment.CommitmentStatus.PENDING
                    && c.deadline() == null
                    && c.createdAt().isBefore(cutoff)) {
                commitments.set(i, c.withStatus(Commitment.CommitmentStatus.EXPIRED));
                count++;
            }
        }
        if (count > 0) {
            log.debug("Expired {} old commitments", count);
        }
        return count;
    }

    /** All commitments (for serialization/persistence). */
    public List<Commitment> getAll() {
        return Collections.unmodifiableList(commitments);
    }

    /** Load commitments from persisted state (e.g., soul items). */
    public void loadAll(List<Commitment> persisted) {
        commitments.clear();
        if (persisted != null) {
            commitments.addAll(persisted);
        }
    }

    /** Total number of commitments (all statuses). */
    public int size() {
        return commitments.size();
    }
}
