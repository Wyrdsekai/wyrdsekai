package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillUsageTrackerTest {

    private SkillUsageTracker tracker;

    @BeforeEach void setUp() {
        tracker = new SkillUsageTracker();
    }

    // --- Recording ---

    @Nested class Recording {
        @Test void record_creates_entry() {
            tracker.record("weather", true, 100, "manual");
            assertThat(tracker.recordsFor("weather")).hasSize(1);
            assertThat(tracker.recordsFor("weather").getFirst().success()).isTrue();
        }

        @Test void multiple_records_accumulate() {
            tracker.record("weather", true, 100, "manual");
            tracker.record("weather", false, 200, "auto");
            tracker.record("weather", true, 50, "manual");
            assertThat(tracker.recordsFor("weather")).hasSize(3);
        }

        @Test void records_for_unknown_skill_returns_empty() {
            assertThat(tracker.recordsFor("nonexistent")).isEmpty();
        }
    }

    // --- Stats ---

    @Nested class Stats {
        @Test void statsFor_computes_correctly() {
            tracker.record("weather", true, 100, null);
            tracker.record("weather", true, 200, null);
            tracker.record("weather", false, 300, null);

            var stats = tracker.statsFor("weather");
            assertThat(stats).isPresent();
            var s = stats.get();
            assertThat(s.totalUses()).isEqualTo(3);
            assertThat(s.successes()).isEqualTo(2);
            assertThat(s.failures()).isEqualTo(1);
            assertThat(s.avgLatencyMs()).isEqualTo(200L);
            assertThat(s.successRate()).isCloseTo(0.667, org.assertj.core.data.Offset.offset(0.01));
        }

        @Test void statsFor_unknown_returns_empty() {
            assertThat(tracker.statsFor("nope")).isEmpty();
        }

        @Test void allStats_sorted_by_usage_descending() {
            tracker.record("alpha", true, 10, null);
            tracker.record("beta", true, 10, null);
            tracker.record("beta", true, 10, null);
            tracker.record("gamma", true, 10, null);
            tracker.record("gamma", true, 10, null);
            tracker.record("gamma", true, 10, null);

            var stats = tracker.allStats();
            assertThat(stats).hasSize(3);
            assertThat(stats.get(0).skillId()).isEqualTo("gamma");
            assertThat(stats.get(1).skillId()).isEqualTo("beta");
            assertThat(stats.get(2).skillId()).isEqualTo("alpha");
        }

        @Test void trackedSkills_returns_all_ids() {
            tracker.record("a", true, 0, null);
            tracker.record("b", false, 0, null);
            assertThat(tracker.trackedSkills()).containsExactlyInAnyOrder("a", "b");
        }

        @Test void totalInvocations_sums_all() {
            tracker.record("a", true, 0, null);
            tracker.record("b", true, 0, null);
            tracker.record("a", false, 0, null);
            assertThat(tracker.totalInvocations()).isEqualTo(3);
        }

        @Test void trackedSkillCount() {
            assertThat(tracker.trackedSkillCount()).isEqualTo(0);
            tracker.record("x", true, 0, null);
            tracker.record("y", true, 0, null);
            assertThat(tracker.trackedSkillCount()).isEqualTo(2);
        }
    }

    // --- Gap detection ---

    @Nested class GapDetection {
        @Test void recordGap_accumulates() {
            tracker.recordGap("calendar management");
            tracker.recordGap("calendar management");
            assertThat(tracker.gaps()).hasSize(1);
            assertThat(tracker.gaps().getFirst().occurrences()).isEqualTo(2);
        }

        @Test void multiple_distinct_gaps() {
            tracker.recordGap("calendar");
            tracker.recordGap("data analysis");
            assertThat(tracker.gaps()).hasSize(2);
        }

        @Test void triggeredGaps_empty_below_threshold() {
            // Records (threshold - 1) occurrences so this stays below regardless
            // of whether the threshold is 2, 3, or anything else.
            int below = SkillUsageTracker.GAP_TRIGGER_THRESHOLD - 1;
            for (int i = 0; i < below; i++) tracker.recordGap("calendar");
            assertThat(tracker.triggeredGaps()).isEmpty();
            assertThat(tracker.shouldTriggerAssessment()).isFalse();
        }

        @Test void triggeredGaps_fires_at_threshold() {
            for (int i = 0; i < SkillUsageTracker.GAP_TRIGGER_THRESHOLD; i++) {
                tracker.recordGap("calendar");
            }
            assertThat(tracker.triggeredGaps()).hasSize(1);
            assertThat(tracker.shouldTriggerAssessment()).isTrue();
        }

        @Test void clearTriggeredGaps_removes_only_triggered() {
            // "calendar" gets enough hits to trigger; "data analysis" gets one
            // fewer than threshold, so it stays after clear.
            for (int i = 0; i < SkillUsageTracker.GAP_TRIGGER_THRESHOLD + 1; i++) {
                tracker.recordGap("calendar");
            }
            for (int i = 0; i < SkillUsageTracker.GAP_TRIGGER_THRESHOLD - 1; i++) {
                tracker.recordGap("data analysis");
            }

            assertThat(tracker.gaps()).hasSize(2);
            tracker.clearTriggeredGaps();
            assertThat(tracker.gaps()).hasSize(1);
            assertThat(tracker.gaps().getFirst().description()).isEqualTo("data analysis");
        }
    }

    // --- Summary ---

    @Nested class Summary {
        @Test void empty_tracker_returns_no_data_message() {
            assertThat(tracker.buildSummary(5)).isEqualTo("No skill usage recorded.");
        }

        @Test void summary_includes_skills_and_gaps() {
            tracker.record("weather", true, 100, null);
            tracker.record("weather", true, 50, null);
            tracker.recordGap("calendar");

            var summary = tracker.buildSummary(5);
            assertThat(summary).contains("weather");
            assertThat(summary).contains("2 uses");
            assertThat(summary).contains("100%");
            assertThat(summary).contains("calendar");
            assertThat(summary).contains("1x");
        }

        @Test void summary_limits_skill_count() {
            for (int i = 0; i < 10; i++) {
                tracker.record("skill-" + i, true, 10, null);
            }
            var summary = tracker.buildSummary(3);
            // Should only list 3 skills
            int count = 0;
            for (String line : summary.split("\n")) {
                if (line.startsWith("- skill-")) count++;
            }
            assertThat(count).isEqualTo(3);
        }
    }
}
