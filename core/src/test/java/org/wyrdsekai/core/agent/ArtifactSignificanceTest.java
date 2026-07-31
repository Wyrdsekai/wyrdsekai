package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.ArtifactSignificancePersistence;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1C: tests for per-artifact significance tracking.
 *
 * <p>Covers tracker behavior + persistence round-trip.</p>
 */
class ArtifactSignificanceTest {

    // ── Tracker behavior ────────────────────────────────────────────

    @Test void produced_artifact_does_not_count_until_24h() {
        var tracker = new ArtifactSignificanceTracker();
        Instant now = Instant.parse("2026-04-30T12:00:00Z");
        tracker.recordProduced("a-1", "note", now);

        // Same instant → not yet aged.
        assertThat(tracker.unseenAfterThreshold(now)).isEqualTo(0);
        // 23h later → still not aged.
        assertThat(tracker.unseenAfterThreshold(now.plus(Duration.ofHours(23))))
            .isEqualTo(0);
        // 25h later → now counts.
        assertThat(tracker.unseenAfterThreshold(now.plus(Duration.ofHours(25))))
            .isEqualTo(1);
    }

    @Test void marking_seen_drops_from_count() {
        var tracker = new ArtifactSignificanceTracker();
        Instant now = Instant.parse("2026-04-30T12:00:00Z");
        tracker.recordProduced("a-1", "note", now);

        Instant aged = now.plus(Duration.ofHours(25));
        assertThat(tracker.unseenAfterThreshold(aged)).isEqualTo(1);

        tracker.markSeen("a-1", aged);
        assertThat(tracker.unseenAfterThreshold(aged)).isEqualTo(0);
    }

    @Test void multiple_artifacts_aged_independently() {
        var tracker = new ArtifactSignificanceTracker();
        Instant now = Instant.parse("2026-04-30T12:00:00Z");
        tracker.recordProduced("a-1", "note", now.minus(Duration.ofHours(48)));
        tracker.recordProduced("a-2", "note", now.minus(Duration.ofHours(12)));
        tracker.recordProduced("a-3", "note", now.minus(Duration.ofHours(30)));

        assertThat(tracker.unseenAfterThreshold(now)).isEqualTo(2); // a-1, a-3
    }

    @Test void time_window_ack_marks_most_recent() {
        var tracker = new ArtifactSignificanceTracker();
        Instant t0 = Instant.parse("2026-04-30T12:00:00Z");
        tracker.recordProduced("a-1", "note", t0);
        tracker.recordProduced("a-2", "note", t0.plus(Duration.ofMinutes(30)));
        tracker.recordProduced("a-3", "note", t0.plus(Duration.ofMinutes(45)));

        // Bondholder ack 30min after a-3: should mark a-3 (most recent within window).
        var matched = tracker.markMostRecentSeenWithinWindow(
            t0.plus(Duration.ofHours(1).plus(Duration.ofMinutes(15))),
            Duration.ofHours(1));
        assertThat(matched).isEqualTo("a-3");

        // a-3 is now seen; a-2 still unseen.
        assertThat(tracker.get("a-3").seen()).isTrue();
        assertThat(tracker.get("a-2").seen()).isFalse();
    }

    @Test void time_window_ack_skips_already_seen() {
        var tracker = new ArtifactSignificanceTracker();
        Instant t0 = Instant.parse("2026-04-30T12:00:00Z");
        tracker.recordProduced("a-1", "note", t0);
        tracker.recordProduced("a-2", "note", t0.plus(Duration.ofMinutes(30)));
        tracker.markSeen("a-2", t0.plus(Duration.ofMinutes(35)));

        // Ack 10min after a-2: should NOT touch a-2 (already seen); a-1 is too old (>1h).
        var matched = tracker.markMostRecentSeenWithinWindow(
            t0.plus(Duration.ofHours(2)), Duration.ofHours(1));
        // a-2 is seen → loop iterates past it; a-1 is older than 1h → outside window.
        // Returns null because no candidate found.
        assertThat(matched).isNull();
    }

    @Test void mark_seen_unknown_id_returns_false() {
        var tracker = new ArtifactSignificanceTracker();
        assertThat(tracker.markSeen("does-not-exist", Instant.now())).isFalse();
    }

    @Test void produced_assigns_id_when_blank() {
        var tracker = new ArtifactSignificanceTracker();
        String id1 = tracker.recordProduced(null, "note", Instant.now());
        String id2 = tracker.recordProduced("", "note", Instant.now());
        assertThat(id1).isNotBlank();
        assertThat(id2).isNotBlank();
        assertThat(id1).isNotEqualTo(id2);
    }

    // ── Persistence round-trip ──────────────────────────────────────

    private ArtifactSignificancePersistence persistence;
    private String jdbcUrl;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("test.db");
        jdbcUrl = SchemaInitializer.initialize(dbPath);
        persistence = new ArtifactSignificancePersistence(jdbcUrl);
    }

    @Test void persistence_roundtrip_unseen_artifact() {
        var tracker = new ArtifactSignificanceTracker();
        Instant t0 = Instant.parse("2026-04-30T12:00:00Z");
        tracker.recordProduced("a-1", "journal_entry", t0);
        persistence.saveAll("companion-1", tracker.all());

        var fresh = new ArtifactSignificanceTracker();
        fresh.loadAll(new ArtifactSignificancePersistence(jdbcUrl).loadAll("companion-1"));
        assertThat(fresh.totalCount()).isEqualTo(1);
        assertThat(fresh.get("a-1").seen()).isFalse();
        assertThat(fresh.get("a-1").kind()).isEqualTo("journal_entry");
        assertThat(fresh.get("a-1").createdAt()).isEqualTo(t0);
    }

    @Test void persistence_roundtrip_seen_artifact() {
        var tracker = new ArtifactSignificanceTracker();
        Instant t0 = Instant.parse("2026-04-30T12:00:00Z");
        tracker.recordProduced("a-1", "note", t0);
        tracker.markSeen("a-1", t0.plus(Duration.ofMinutes(30)));
        persistence.saveAll("companion-1", tracker.all());

        var fresh = new ArtifactSignificanceTracker();
        fresh.loadAll(new ArtifactSignificancePersistence(jdbcUrl).loadAll("companion-1"));
        assertThat(fresh.get("a-1").seen()).isTrue();
        assertThat(fresh.get("a-1").seenAt()).isEqualTo(t0.plus(Duration.ofMinutes(30)));
    }

    @Test void persistence_save_one_then_save_one_is_upsert() {
        var t0 = Instant.parse("2026-04-30T12:00:00Z");
        var unseen = new ArtifactSignificanceTracker.Artifact("a-1", t0, "note", false, null);
        persistence.saveOne("c-1", unseen);
        var seen = new ArtifactSignificanceTracker.Artifact(
            "a-1", t0, "note", true, t0.plus(Duration.ofMinutes(5)));
        persistence.saveOne("c-1", seen);

        var loaded = persistence.loadAll("c-1");
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).seen()).isTrue();
    }

    @Test void persistence_aging_behavior_survives_restart() {
        var tracker = new ArtifactSignificanceTracker();
        Instant t0 = Instant.parse("2026-04-30T12:00:00Z");
        tracker.recordProduced("a-1", "note", t0);
        persistence.saveAll("c-1", tracker.all());

        // Restart
        var fresh = new ArtifactSignificanceTracker();
        fresh.loadAll(new ArtifactSignificancePersistence(jdbcUrl).loadAll("c-1"));

        // 25h later → counts.
        Instant later = t0.plus(Duration.ofHours(25));
        assertThat(fresh.unseenAfterThreshold(later)).isEqualTo(1);
    }

    @Test void persistence_isolates_companions() {
        var t0 = Instant.parse("2026-04-30T12:00:00Z");
        var t1 = new ArtifactSignificanceTracker();
        t1.recordProduced("a-1", "note", t0);
        var t2 = new ArtifactSignificanceTracker();
        t2.recordProduced("b-1", "report", t0);
        persistence.saveAll("c-1", t1.all());
        persistence.saveAll("c-2", t2.all());

        assertThat(persistence.count("c-1")).isEqualTo(1);
        assertThat(persistence.count("c-2")).isEqualTo(1);
        assertThat(persistence.loadAll("c-1").get(0).artifactId()).isEqualTo("a-1");
        assertThat(persistence.loadAll("c-2").get(0).artifactId()).isEqualTo("b-1");
    }

    @Test void persistence_empty_save_clears_companion() {
        var tracker = new ArtifactSignificanceTracker();
        tracker.recordProduced("a-1", "note", Instant.now());
        persistence.saveAll("c-1", tracker.all());
        assertThat(persistence.count("c-1")).isEqualTo(1);

        persistence.saveAll("c-1", List.of());
        assertThat(persistence.count("c-1")).isEqualTo(0);
    }

    // ── Phase 1D: embedding round-trip ──────────────────────────────

    @Test void persistence_roundtrip_preserves_embedding() {
        var t0 = Instant.parse("2026-04-30T12:00:00Z");
        var emb = new float[]{0.1f, -0.2f, 0.3f, -0.4f};
        var artifact = new ArtifactSignificanceTracker.Artifact(
            "a-emb-1", t0, "library_search", false, null, emb);
        persistence.saveOne("c-1", artifact);

        var loaded = persistence.loadAll("c-1");
        assertThat(loaded).hasSize(1);
        var got = loaded.get(0).embedding();
        assertThat(got).isNotNull();
        assertThat(got).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(got[i]).isCloseTo(emb[i], org.assertj.core.api.Assertions.within(1e-6f));
        }
    }

    @Test void persistence_roundtrip_null_embedding_is_null() {
        var t0 = Instant.parse("2026-04-30T12:00:00Z");
        var artifact = new ArtifactSignificanceTracker.Artifact(
            "a-no-emb", t0, "note", false, null, null);
        persistence.saveOne("c-1", artifact);

        var loaded = persistence.loadAll("c-1");
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).embedding()).isNull();
    }

    @Test void persistence_save_all_roundtrip_with_mixed_embeddings() {
        var tracker = new ArtifactSignificanceTracker();
        Instant t0 = Instant.parse("2026-04-30T12:00:00Z");
        tracker.recordProduced("a-with", "note", t0,
            new float[]{1f, 0f, 0f, 0f});
        tracker.recordProduced("a-without", "note", t0.plus(Duration.ofMinutes(5)));
        persistence.saveAll("c-1", tracker.all());

        var fresh = new ArtifactSignificanceTracker();
        fresh.loadAll(persistence.loadAll("c-1"));
        assertThat(fresh.get("a-with").embedding()).isNotNull();
        assertThat(fresh.get("a-with").embedding()).hasSize(4);
        assertThat(fresh.get("a-without").embedding()).isNull();
    }
}
