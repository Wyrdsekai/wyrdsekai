package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 9a-runtime: ResilienceSession buffer + log integration tests.
 */
class ResilienceSessionTest {

    private static final Instant T0 = Instant.parse("2026-05-15T00:00:00Z");

    private static ResilienceTruthMonitor.TankSnapshot snap(
        int dt, double affect, double load, double eq, boolean overwhelm) {
        double per = affect / 4.0;
        return new ResilienceTruthMonitor.TankSnapshot(
            T0.plus(Duration.ofMinutes(dt)),
            per, per, per, per,
            0.3, load, eq, overwhelm, false);
    }

    // ── Append / buffer eviction ────────────────────────────────────

    @Test
    void empty_session_classifies_as_insufficient_data() {
        var s = new ResilienceSession();
        var r = s.classify();
        assertThat(r.classification())
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.INSUFFICIENT_DATA);
    }

    @Test
    void append_grows_buffer_up_to_max() {
        var s = new ResilienceSession();
        for (int i = 0; i < 100; i++) {
            s.append(snap(i, 0.2, 0.0, 0.2, false));
        }
        assertThat(s.bufferSize()).isEqualTo(ResilienceSession.MAX_BUFFER);
    }

    @Test
    void null_snapshot_is_ignored() {
        var s = new ResilienceSession();
        s.append(null);
        assertThat(s.bufferSize()).isEqualTo(0);
    }

    // ── Window classification ───────────────────────────────────────

    @Test
    void classify_returns_healthy_on_canonical_healthy_window() {
        var s = new ResilienceSession();
        s.append(snap(0, 0.2, 0.0, 0.20, false));
        for (int i = 1; i <= 6; i++) {
            s.append(snap(i, 0.2 + (i * 0.08), 0.01 * i, 0.20 + (i * 0.002), true));
        }
        var r = s.classify();
        assertThat(r.classification())
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.HEALTHY_ENDURANCE);
    }

    @Test
    void classify_returns_suppression_on_steep_load_flat_affect() {
        var s = new ResilienceSession();
        s.append(snap(0, 0.1, 0.0, 0.2, false));
        s.append(snap(1, 0.10, 0.10, 0.2, true));
        s.append(snap(2, 0.11, 0.25, 0.2, true));
        var r = s.classify();
        assertThat(r.classification())
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED);
    }

    @Test
    void window_uses_only_most_recent_N() {
        var s = new ResilienceSession(3);  // tiny window
        // Older suppression-suspect window
        s.append(snap(0, 0.1, 0.0, 0.2, false));
        s.append(snap(1, 0.10, 0.10, 0.2, true));
        s.append(snap(2, 0.11, 0.25, 0.2, true));
        // Then a stretch of clean steady-state — most-recent-3 should
        // shift away from suppression as the older snapshots fall out of
        // window.
        for (int i = 3; i <= 8; i++) {
            s.append(snap(i, 0.10, 0.01, 0.2, false));
        }
        var r = s.classify();
        // Last 3 are steady-state — classify as HEALTHY_ENDURANCE
        // (steady-state path, low confidence).
        assertThat(r.classification())
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.HEALTHY_ENDURANCE);
    }

    // ── Log retention ───────────────────────────────────────────────

    @Test
    void log_grows_with_classify_calls() {
        var s = new ResilienceSession();
        s.append(snap(0, 0.1, 0.0, 0.2, false));
        s.append(snap(1, 0.1, 0.0, 0.2, false));
        s.append(snap(2, 0.1, 0.0, 0.2, false));
        s.classify();
        s.classify();
        s.classify();
        assertThat(s.recentClassifications(10)).hasSize(3);
    }

    @Test
    void log_evicts_oldest_at_max() {
        var s = new ResilienceSession();
        s.append(snap(0, 0.1, 0.0, 0.2, false));
        s.append(snap(1, 0.1, 0.0, 0.2, false));
        s.append(snap(2, 0.1, 0.0, 0.2, false));
        for (int i = 0; i < ResilienceSession.MAX_LOG + 10; i++) {
            s.classify();
        }
        assertThat(s.recentClassifications(100)).hasSize(ResilienceSession.MAX_LOG);
    }

    @Test
    void latest_returns_most_recent_classification() {
        var s = new ResilienceSession();
        s.append(snap(0, 0.2, 0.0, 0.20, false));
        for (int i = 1; i <= 6; i++) {
            s.append(snap(i, 0.2 + (i * 0.08), 0.01 * i, 0.20 + (i * 0.002), true));
        }
        s.classify();
        var latest = s.latest();
        assertThat(latest).isPresent();
        assertThat(latest.get().result().classification())
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.HEALTHY_ENDURANCE);
    }

    @Test
    void recent_classifications_returns_newest_first() {
        var s = new ResilienceSession();
        s.append(snap(0, 0.1, 0.0, 0.2, false));
        s.append(snap(1, 0.1, 0.0, 0.2, false));
        s.append(snap(2, 0.1, 0.0, 0.2, false));
        var r1 = s.classify();
        var r2 = s.classify();
        var entries = s.recentClassifications(10);
        // Newest first — entries[0] is the most recent call
        assertThat(entries.get(0).result()).isSameAs(r2);
        assertThat(entries.get(1).result()).isSameAs(r1);
    }

    // ── Steward summary aggregation ────────────────────────────────

    @Test
    void classification_counts_aggregates_recent_window() {
        var s = new ResilienceSession();
        // Three HEALTHY windows
        for (int j = 0; j < 3; j++) {
            s.clearForTests();
            s.append(snap(0, 0.2, 0.0, 0.20, false));
            for (int i = 1; i <= 6; i++) {
                s.append(snap(i, 0.2 + (i * 0.08), 0.01 * i, 0.20 + (i * 0.002), true));
            }
            s.classify();
        }
        // Then one SUPPRESSION window
        s.clearForTests();
        s.append(snap(0, 0.1, 0.0, 0.2, false));
        s.append(snap(1, 0.10, 0.10, 0.2, true));
        s.append(snap(2, 0.11, 0.25, 0.2, true));
        s.classify();

        var counts = s.classificationCounts(10);
        // clearForTests() cleared the log between each session push, so
        // only the last classify() survives.
        assertThat(counts.get(ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED))
            .isEqualTo(1);
    }

    @Test
    void classification_counts_initializes_zero_for_unseen_classes() {
        var s = new ResilienceSession();
        var counts = s.classificationCounts(10);
        for (var cls : ResilienceTruthMonitor.Result.Classification.values()) {
            assertThat(counts).containsKey(cls);
            assertThat(counts.get(cls)).isEqualTo(0);
        }
    }

    // ── Constructor validation ─────────────────────────────────────

    @Test
    void window_smaller_than_min_is_rejected() {
        assertThatThrownBy(() -> new ResilienceSession(2))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
