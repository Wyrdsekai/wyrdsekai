package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1D ( extension): semantic ack-matching for
 * {@link ArtifactSignificanceTracker}.
 *
 * <p>Operates over synthetic, pre-normalised embeddings — does not require the
 * MiniLM ONNX runtime to be present. The tracker's matching logic is pure (cosine
 * similarity over float[]); these tests verify the logic shape independent of
 * the embedding service. The {@code SemanticAckMissingEmbeddingServiceTest}
 * companion verifies the no-op fallback path.</p>
 */
class SemanticAckMatchingTest {

    /** Synthesise a 4-d unit vector in a fixed direction so cosine is exact. */
    private static float[] vec(float a, float b, float c, float d) {
        float n = (float) Math.sqrt(a * a + b * b + c * c + d * d);
        return new float[]{a / n, b / n, c / n, d / n};
    }

    @Test void semantic_match_pins_specific_artifact_above_threshold() {
        var tracker = new ArtifactSignificanceTracker();
        Instant now = Instant.parse("2026-04-30T12:00:00Z");

        // Three artifacts in last 24h, each with a distinct embedding direction.
        var mythologyEmb = vec(1, 0, 0, 0);
        var gardenEmb    = vec(0, 1, 0, 0);
        var cleaningEmb  = vec(0, 0, 1, 0);
        tracker.recordProduced("a-myth", "library_search",
            now.minus(Duration.ofHours(8)), mythologyEmb);
        tracker.recordProduced("a-garden", "journal_entry",
            now.minus(Duration.ofHours(4)), gardenEmb);
        tracker.recordProduced("a-clean", "task_plan",
            now.minus(Duration.ofHours(2)), cleaningEmb);

        // Bondholder ack semantically nearest mythology (slight noise in other dims).
        var ackEmb = vec(0.95f, 0.10f, 0.10f, 0.10f);
        var matched = tracker.markBestSemanticMatchSeen(
            ackEmb, now, Duration.ofHours(24), 0.55f);

        assertThat(matched).isEqualTo("a-myth");
        assertThat(tracker.get("a-myth").seen()).isTrue();
        assertThat(tracker.get("a-garden").seen()).isFalse();
        assertThat(tracker.get("a-clean").seen()).isFalse();
    }

    @Test void semantic_match_returns_null_when_no_artifact_clears_threshold() {
        var tracker = new ArtifactSignificanceTracker();
        Instant now = Instant.parse("2026-04-30T12:00:00Z");
        tracker.recordProduced("a-myth", "note",
            now.minus(Duration.ofHours(8)), vec(1, 0, 0, 0));

        // Ack is orthogonal — cosine ~ 0.
        var ackEmb = vec(0, 1, 0, 0);
        var matched = tracker.markBestSemanticMatchSeen(
            ackEmb, now, Duration.ofHours(24), 0.55f);

        assertThat(matched).isNull();
        assertThat(tracker.get("a-myth").seen()).isFalse();
    }

    @Test void semantic_match_skips_artifacts_outside_window() {
        var tracker = new ArtifactSignificanceTracker();
        Instant now = Instant.parse("2026-04-30T12:00:00Z");

        // Same direction, but produced 3 days ago — outside 24h window.
        tracker.recordProduced("a-old", "note",
            now.minus(Duration.ofDays(3)), vec(1, 0, 0, 0));

        var ackEmb = vec(1, 0, 0, 0);
        var matched = tracker.markBestSemanticMatchSeen(
            ackEmb, now, Duration.ofHours(24), 0.55f);

        assertThat(matched).isNull();
        assertThat(tracker.get("a-old").seen()).isFalse();
    }

    @Test void semantic_match_skips_artifacts_without_embedding() {
        var tracker = new ArtifactSignificanceTracker();
        Instant now = Instant.parse("2026-04-30T12:00:00Z");

        // Recorded with no embedding — skipped by semantic path.
        tracker.recordProduced("a-noemb", "note",
            now.minus(Duration.ofHours(2)));

        var ackEmb = vec(1, 0, 0, 0);
        var matched = tracker.markBestSemanticMatchSeen(
            ackEmb, now, Duration.ofHours(24), 0.55f);

        assertThat(matched).isNull();
        assertThat(tracker.get("a-noemb").seen()).isFalse();
    }

    @Test void semantic_match_picks_best_match_not_most_recent() {
        var tracker = new ArtifactSignificanceTracker();
        Instant now = Instant.parse("2026-04-30T12:00:00Z");

        // Older: high-match. Newer: low-match. Semantic should pick older.
        tracker.recordProduced("a-old-relevant", "note",
            now.minus(Duration.ofHours(20)), vec(1, 0, 0, 0));
        tracker.recordProduced("a-new-irrelevant", "note",
            now.minus(Duration.ofMinutes(5)), vec(0, 1, 0, 0));

        var ackEmb = vec(0.95f, 0.10f, 0.10f, 0.10f);
        var matched = tracker.markBestSemanticMatchSeen(
            ackEmb, now, Duration.ofHours(24), 0.55f);

        assertThat(matched).isEqualTo("a-old-relevant");
    }

    @Test void semantic_match_skips_already_seen_artifacts() {
        var tracker = new ArtifactSignificanceTracker();
        Instant now = Instant.parse("2026-04-30T12:00:00Z");

        tracker.recordProduced("a-1", "note",
            now.minus(Duration.ofHours(2)), vec(1, 0, 0, 0));
        tracker.markSeen("a-1", now.minus(Duration.ofHours(1)));

        var matched = tracker.markBestSemanticMatchSeen(
            vec(1, 0, 0, 0), now, Duration.ofHours(24), 0.55f);
        assertThat(matched).isNull();
    }

    @Test void semantic_match_handles_null_embedding_gracefully() {
        var tracker = new ArtifactSignificanceTracker();
        Instant now = Instant.parse("2026-04-30T12:00:00Z");
        tracker.recordProduced("a-1", "note",
            now.minus(Duration.ofHours(2)), vec(1, 0, 0, 0));

        var matched = tracker.markBestSemanticMatchSeen(
            null, now, Duration.ofHours(24), 0.55f);
        assertThat(matched).isNull();
    }

    @Test void semantic_match_handles_dimension_mismatch_gracefully() {
        var tracker = new ArtifactSignificanceTracker();
        Instant now = Instant.parse("2026-04-30T12:00:00Z");
        tracker.recordProduced("a-1", "note",
            now.minus(Duration.ofHours(2)), new float[]{1f, 0f, 0f});

        // 4-d ack vs 3-d artifact embedding — silently skipped.
        var matched = tracker.markBestSemanticMatchSeen(
            vec(1, 0, 0, 0), now, Duration.ofHours(24), 0.55f);
        assertThat(matched).isNull();
    }

    @Test void embedding_setter_attaches_to_existing_artifact() {
        var tracker = new ArtifactSignificanceTracker();
        Instant now = Instant.parse("2026-04-30T12:00:00Z");
        tracker.recordProduced("a-1", "note", now);

        assertThat(tracker.get("a-1").embedding()).isNull();
        var ok = tracker.setEmbedding("a-1", vec(1, 0, 0, 0));
        assertThat(ok).isTrue();
        assertThat(tracker.get("a-1").embedding()).hasSize(4);

        // Setting on unknown id is false, not an exception.
        assertThat(tracker.setEmbedding("nope", vec(1, 0, 0, 0))).isFalse();
    }
}
