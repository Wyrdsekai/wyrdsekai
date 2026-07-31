package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the Commitment record (Phase F: Commitment System).
 */
class CommitmentTest {

    @Test void create_commitment_with_all_fields() {
        var now = Instant.now();
        var deadline = now.plusSeconds(3600);
        var c = new Commitment("id-1", "Check training results", "user asked",
            now, deadline, Commitment.CommitmentStatus.PENDING);

        assertThat(c.id()).isEqualTo("id-1");
        assertThat(c.description()).isEqualTo("Check training results");
        assertThat(c.context()).isEqualTo("user asked");
        assertThat(c.createdAt()).isEqualTo(now);
        assertThat(c.deadline()).isEqualTo(deadline);
        assertThat(c.status()).isEqualTo(Commitment.CommitmentStatus.PENDING);
    }

    @Test void complete_changes_status() {
        var c = new Commitment("id-1", "Do something", "context",
            Instant.now(), null, Commitment.CommitmentStatus.PENDING);

        var completed = c.withStatus(Commitment.CommitmentStatus.COMPLETED);

        assertThat(completed.status()).isEqualTo(Commitment.CommitmentStatus.COMPLETED);
        assertThat(completed.description()).isEqualTo("Do something");
        assertThat(completed.id()).isEqualTo("id-1");
    }

    @Test void overdue_detection_with_past_deadline() {
        var pastDeadline = Instant.now().minusSeconds(3600);
        var c = new Commitment("id-1", "Overdue task", "context",
            Instant.now().minusSeconds(7200), pastDeadline,
            Commitment.CommitmentStatus.PENDING);

        assertThat(c.isOverdue()).isTrue();
    }

    @Test void not_overdue_when_completed() {
        var pastDeadline = Instant.now().minusSeconds(3600);
        var c = new Commitment("id-1", "Done task", "context",
            Instant.now().minusSeconds(7200), pastDeadline,
            Commitment.CommitmentStatus.COMPLETED);

        assertThat(c.isOverdue()).isFalse();
    }

    @Test void not_overdue_without_deadline() {
        var c = new Commitment("id-1", "No deadline task", "context",
            Instant.now().minusSeconds(86400 * 30), null,
            Commitment.CommitmentStatus.PENDING);

        assertThat(c.isOverdue()).isFalse();
    }

    @Test void not_overdue_when_deadline_is_future() {
        var futureDeadline = Instant.now().plusSeconds(3600);
        var c = new Commitment("id-1", "Future task", "context",
            Instant.now(), futureDeadline,
            Commitment.CommitmentStatus.PENDING);

        assertThat(c.isOverdue()).isFalse();
    }
}
