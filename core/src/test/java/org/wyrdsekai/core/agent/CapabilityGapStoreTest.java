package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link CapabilityGapStore} against a real SQLite database to
 * confirm the upsert/load/clear cycle survives the boundary, and that the
 * tracker rehydrates its in-memory map from the store on construction.
 */
class CapabilityGapStoreTest {

    private static final String AGENT = "did:key:zTestAgent";

    @TempDir Path tmp;
    private String jdbcUrl;
    private CapabilityGapStore store;

    @BeforeEach void initDb() {
        jdbcUrl = SchemaInitializer.initialize(tmp.resolve("test.db"));
        store = new CapabilityGapStore(jdbcUrl);
        store.deleteAll(AGENT);
    }

    @Test void recordGap_inserts_with_occurrence_one() {
        store.recordGap(AGENT, "unmatched action: scry");
        var loaded = store.loadGaps(AGENT);
        assertThat(loaded).hasSize(1);
        var gap = loaded.values().iterator().next();
        assertThat(gap.description()).isEqualTo("unmatched action: scry");
        assertThat(gap.occurrences()).isEqualTo(1);
    }

    @Test void recordGap_twice_increments_occurrences() {
        store.recordGap(AGENT, "unmatched action: scry");
        store.recordGap(AGENT, "unmatched action: scry");
        store.recordGap(AGENT, "unmatched action: scry");
        var loaded = store.loadGaps(AGENT);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.values().iterator().next().occurrences()).isEqualTo(3);
    }

    @Test void recordGap_distinct_descriptions_separate_rows() {
        store.recordGap(AGENT, "unmatched action: scry");
        store.recordGap(AGENT, "unmatched action: divine");
        assertThat(store.loadGaps(AGENT)).hasSize(2);
    }

    @Test void recordGap_different_agents_isolated() {
        store.recordGap(AGENT, "shared description");
        store.recordGap("did:key:zOther", "shared description");
        assertThat(store.loadGaps(AGENT)).hasSize(1);
        assertThat(store.loadGaps("did:key:zOther")).hasSize(1);
    }

    @Test void clearTriggered_deletes_rows_at_or_above_threshold() {
        store.recordGap(AGENT, "low");          // 1
        store.recordGap(AGENT, "high");          // 2
        store.recordGap(AGENT, "high");
        store.recordGap(AGENT, "highest");        // 3
        store.recordGap(AGENT, "highest");
        store.recordGap(AGENT, "highest");

        store.clearTriggered(AGENT, 2);
        var remaining = store.loadGaps(AGENT);
        assertThat(remaining.keySet()).containsExactly("low");
    }

    @Test void blank_inputs_are_noops() {
        store.recordGap(null, "x");
        store.recordGap("", "x");
        store.recordGap(AGENT, null);
        store.recordGap(AGENT, "");
        assertThat(store.loadGaps(AGENT)).isEmpty();
    }

    @Test void tracker_rehydrates_from_store_on_construction() {
        // Seed the store directly, then build a fresh tracker bound to it.
        store.recordGap(AGENT, "calendar management");
        store.recordGap(AGENT, "calendar management");
        store.recordGap(AGENT, "data analysis");

        var tracker = new SkillUsageTracker(store, AGENT);
        assertThat(tracker.gaps()).hasSize(2);
        // The two-hit gap should already be over the threshold (2 by default).
        assertThat(tracker.shouldTriggerAssessment()).isTrue();
        var triggered = tracker.triggeredGaps();
        assertThat(triggered).hasSize(1);
        assertThat(triggered.get(0).description()).isEqualTo("calendar management");
    }

    @Test void tracker_writes_through_to_store() {
        var tracker = new SkillUsageTracker(store, AGENT);
        tracker.recordGap("scrying");
        tracker.recordGap("scrying");

        // Reconstruct a fresh tracker from the same store — state must persist.
        var rehydrated = new SkillUsageTracker(store, AGENT);
        assertThat(rehydrated.gaps()).hasSize(1);
        assertThat(rehydrated.gaps().get(0).occurrences()).isEqualTo(2);
    }

    @Test void tracker_clearTriggeredGaps_propagates_delete_to_store() {
        var tracker = new SkillUsageTracker(store, AGENT);
        // Push one gap over threshold, leave another below.
        for (int i = 0; i < SkillUsageTracker.GAP_TRIGGER_THRESHOLD; i++) {
            tracker.recordGap("over-threshold");
        }
        tracker.recordGap("below-threshold");

        tracker.clearTriggeredGaps();
        var remaining = store.loadGaps(AGENT);
        assertThat(remaining.keySet()).containsExactly("below-threshold");
    }
}
