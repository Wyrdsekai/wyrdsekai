package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1D: when {@link org.wyrdsekai.core.search.EmbeddingService} is not
 * initialised (test environments, fresh installs, missing model), the semantic
 * ack-matching path must silently no-op while the keyword/time-window path
 * still fires. This guards against a hard dependency on the ONNX runtime
 * for vitality bookkeeping.
 *
 * <p>This test exercises the tracker layer directly because the embedding
 * service is a singleton — {@code EmbeddingService.get()} returns null in the
 * default test env, which is the very scenario this test asserts. The tracker
 * is what {@link CompanionActor#drainSignificanceOnBondholderAck(String)}
 * calls; if the tracker tolerates a null ack embedding, the actor's path
 * tolerates a missing service.</p>
 */
class SemanticAckMissingEmbeddingServiceTest {

    @Test void semantic_path_noops_when_ack_embedding_is_null() {
        // Stand-in for: EmbeddingService.get() returned null, so no embedding
        // was computed for the bondholder's ack text. The tracker still has
        // artifacts from prior production, but the semantic match path
        // silently returns null without throwing.
        var tracker = new ArtifactSignificanceTracker();
        Instant now = Instant.parse("2026-04-30T12:00:00Z");

        // Artifact has an embedding (production-time service was up at one point).
        // Within 1h of "now" so the keyword fallback also fires.
        tracker.recordProduced("a-1", "note", now.minus(Duration.ofMinutes(20)),
            new float[]{1f, 0f, 0f, 0f});

        var matched = tracker.markBestSemanticMatchSeen(
            null, now, Duration.ofHours(24), 0.55f);
        assertThat(matched).isNull();

        // Keyword/time-window path is still fully functional.
        var keywordMatched = tracker.markMostRecentSeenWithinWindow(
            now, Duration.ofHours(1));
        assertThat(keywordMatched).isEqualTo("a-1");
        assertThat(tracker.get("a-1").seen()).isTrue();
    }

    @Test void semantic_path_noops_when_no_artifact_has_embedding() {
        // Stand-in for: EmbeddingService.get() was null at production-time too,
        // so the artifact rows have no embeddings stored. The semantic path
        // simply finds no candidates.
        var tracker = new ArtifactSignificanceTracker();
        Instant now = Instant.parse("2026-04-30T12:00:00Z");

        // Both within 1h so the keyword fallback can mark the most recently
        // inserted (markMostRecentSeenWithinWindow walks in insertion order).
        tracker.recordProduced("a-older", "note", now.minus(Duration.ofMinutes(50)));
        tracker.recordProduced("a-newer", "note", now.minus(Duration.ofMinutes(20)));

        // Caller has an embedding; artifacts don't. Semantic returns null
        // — nothing to match against.
        var ackEmb = new float[]{1f, 0f, 0f, 0f};
        var matched = tracker.markBestSemanticMatchSeen(
            ackEmb, now, Duration.ofHours(24), 0.55f);
        assertThat(matched).isNull();

        // Keyword path still works (most recently inserted is a-newer).
        var keywordMatched = tracker.markMostRecentSeenWithinWindow(
            now, Duration.ofHours(1));
        assertThat(keywordMatched).isEqualTo("a-newer");
    }

    @Test void semantic_path_noops_when_ack_embedding_is_empty_array() {
        var tracker = new ArtifactSignificanceTracker();
        Instant now = Instant.parse("2026-04-30T12:00:00Z");
        tracker.recordProduced("a-1", "note", now.minus(Duration.ofHours(2)),
            new float[]{1f, 0f, 0f, 0f});

        var matched = tracker.markBestSemanticMatchSeen(
            new float[0], now, Duration.ofHours(24), 0.55f);
        assertThat(matched).isNull();
    }
}
