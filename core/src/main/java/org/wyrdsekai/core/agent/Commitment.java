package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * A promise the agent made — something it said it would do.
 * Commitments are stored as soul items (category "commitment") and surfaced
 * during the forge cycle so the agent follows through.
 *
 * @param id          Unique identifier
 * @param description What the agent committed to ("Check on the training results")
 * @param context     Why this commitment was made (conversation context)
 * @param createdAt   When the commitment was created
 * @param deadline    When it should be done (nullable — no deadline means "when convenient")
 * @param status      Current status
 */
public record Commitment(
    @JsonProperty("id") String id,
    @JsonProperty("description") String description,
    @JsonProperty("context") String context,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("deadline") Instant deadline,
    @JsonProperty("status") CommitmentStatus status
) {
    @JsonCreator
    public Commitment {}

    public enum CommitmentStatus { PENDING, IN_PROGRESS, COMPLETED, EXPIRED }

    /** Whether this commitment is past its deadline and still pending. */
    public boolean isOverdue() {
        return deadline != null && Instant.now().isAfter(deadline)
            && status == CommitmentStatus.PENDING;
    }

    /** Return a copy with updated status. */
    public Commitment withStatus(CommitmentStatus newStatus) {
        return new Commitment(id, description, context, createdAt, deadline, newStatus);
    }
}
