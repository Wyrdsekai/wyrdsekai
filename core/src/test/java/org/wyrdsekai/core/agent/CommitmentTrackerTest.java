package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CommitmentTracker (Phase F: Commitment System).
 */
class CommitmentTrackerTest {

    @Test void add_and_retrieve_commitment() {
        var tracker = new CommitmentTracker();
        var c = tracker.add("Check training results", "user asked", null);

        assertThat(c).isNotNull();
        assertThat(c.description()).isEqualTo("Check training results");
        assertThat(c.status()).isEqualTo(Commitment.CommitmentStatus.PENDING);
        assertThat(tracker.getPending()).hasSize(1);
    }

    @Test void complete_commitment() {
        var tracker = new CommitmentTracker();
        var c = tracker.add("Do the thing", "context", null);

        var completed = tracker.complete(c.id());

        assertThat(completed).isTrue();
        assertThat(tracker.getPending()).isEmpty();
    }

    @Test void complete_nonexistent_returns_false() {
        var tracker = new CommitmentTracker();
        assertThat(tracker.complete("nonexistent-id")).isFalse();
    }

    @Test void overdue_detection() {
        var tracker = new CommitmentTracker();
        var pastDeadline = Instant.now().minusSeconds(3600);
        tracker.add("Overdue task", "context", pastDeadline);
        tracker.add("Future task", "context", Instant.now().plusSeconds(3600));
        tracker.add("No deadline task", "context", null);

        assertThat(tracker.getOverdue()).hasSize(1);
        assertThat(tracker.getOverdue().getFirst().description()).isEqualTo("Overdue task");
    }

    @Test void expire_old_commitments_without_deadline() {
        var tracker = new CommitmentTracker();

        // Add a commitment with an old creation time by loading it directly
        var oldCommitment = new Commitment(
            "old-id", "Old task", "context",
            Instant.now().minus(Duration.ofDays(8)),
            null,  // no deadline
            Commitment.CommitmentStatus.PENDING
        );
        tracker.loadAll(List.of(oldCommitment));

        int expired = tracker.expire();

        assertThat(expired).isEqualTo(1);
        assertThat(tracker.getPending()).isEmpty();
        assertThat(tracker.getAll().getFirst().status())
            .isEqualTo(Commitment.CommitmentStatus.EXPIRED);
    }

    @Test void max_limit_expires_oldest() {
        var tracker = new CommitmentTracker();

        // Fill to max
        for (int i = 0; i < CommitmentTracker.MAX_ACTIVE; i++) {
            tracker.add("Task " + i, "context", null);
        }
        assertThat(tracker.getPending()).hasSize(CommitmentTracker.MAX_ACTIVE);

        // Add one more — oldest should be expired
        tracker.add("Task overflow", "context", null);

        // Should still have MAX_ACTIVE pending (one expired, one added)
        assertThat(tracker.getPending()).hasSize(CommitmentTracker.MAX_ACTIVE);
        // The expired one should be "Task 0"
        assertThat(tracker.getAll().stream()
            .filter(c -> c.status() == Commitment.CommitmentStatus.EXPIRED)
            .count()).isEqualTo(1);
    }

    @Test void blank_description_returns_null() {
        var tracker = new CommitmentTracker();
        assertThat(tracker.add("", "context", null)).isNull();
        assertThat(tracker.add("   ", "context", null)).isNull();
        assertThat(tracker.add(null, "context", null)).isNull();
        assertThat(tracker.size()).isZero();
    }

    @Test void load_and_get_all() {
        var tracker = new CommitmentTracker();
        var commitments = List.of(
            new Commitment("id-1", "Task 1", "ctx", Instant.now(), null,
                Commitment.CommitmentStatus.PENDING),
            new Commitment("id-2", "Task 2", "ctx", Instant.now(), null,
                Commitment.CommitmentStatus.COMPLETED)
        );
        tracker.loadAll(commitments);

        assertThat(tracker.getAll()).hasSize(2);
        assertThat(tracker.getPending()).hasSize(1);
    }
}
