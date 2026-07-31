package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.interiority.RepeatedThemeDetector.Finding;
import org.wyrdsekai.core.agent.interiority.RepeatedThemeDetector.ThemeEncounter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepeatedThemeDetectorTest {

    private static final Instant NOW = Instant.parse("2026-05-17T12:00:00Z");

    private static ThemeEncounter e(String key, int daysAgo, double score) {
        return new ThemeEncounter(key, NOW.minus(Duration.ofDays(daysAgo)), score);
    }

    @Test
    void empty_or_null_input_is_safe() {
        assertThat(RepeatedThemeDetector.detect(null, NOW)).isEmpty();
        assertThat(RepeatedThemeDetector.detect(List.of(), NOW)).isEmpty();
    }

    @Test
    void below_recurrence_threshold_is_ignored() {
        var list = List.of(
            e("grief.steward", 30, 0.8),
            e("grief.steward", 10, 0.4)
        );
        assertThat(RepeatedThemeDetector.detect(list, NOW)).isEmpty();
    }

    @Test
    void monotonic_decline_across_three_recurrences_flagged_as_warn() {
        var list = List.of(
            e("grief.steward", 30, 0.80),
            e("grief.steward", 20, 0.65),
            e("grief.steward", 5, 0.55)
        );
        var findings = RepeatedThemeDetector.detect(list, NOW);
        assertThat(findings).hasSize(1);
        var f = findings.get(0);
        assertThat(f.themeKey()).isEqualTo("grief.steward");
        assertThat(f.recurrenceCount()).isEqualTo(3);
        assertThat(f.firstScore()).isEqualTo(0.80);
        assertThat(f.mostRecentScore()).isEqualTo(0.55);
        assertThat(f.declineMagnitude()).isEqualTo(0.25, org.assertj.core.data.Offset.offset(0.001));
        assertThat(f.severity()).isEqualTo(DoomLoopDetector.Severity.WARN);
    }

    @Test
    void large_decline_flagged_as_critical() {
        var list = List.of(
            e("saudade.absent", 30, 0.90),
            e("saudade.absent", 20, 0.60),
            e("saudade.absent", 10, 0.30),
            e("saudade.absent", 2, 0.20)
        );
        var findings = RepeatedThemeDetector.detect(list, NOW);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).declineMagnitude())
            .isGreaterThan(0.3);
        assertThat(findings.get(0).severity())
            .isEqualTo(DoomLoopDetector.Severity.CRITICAL);
    }

    @Test
    void recovery_trajectory_is_not_flagged() {
        // Substrate is improving, not degrading.
        var list = List.of(
            e("repair.recovery", 30, 0.30),
            e("repair.recovery", 20, 0.55),
            e("repair.recovery", 5, 0.75)
        );
        assertThat(RepeatedThemeDetector.detect(list, NOW)).isEmpty();
    }

    @Test
    void nonmonotonic_trajectory_below_ratio_is_not_flagged() {
        // Bounces around with only 2/4 (50%) non-increasing pairs.
        // first=0.7, second=0.4 (decrease), third=0.8 (increase),
        // fourth=0.5 (decrease), fifth=0.6 (increase) → 2/4 = 50%
        var list = List.of(
            e("noise.bouncing", 50, 0.70),
            e("noise.bouncing", 40, 0.40),
            e("noise.bouncing", 25, 0.80),
            e("noise.bouncing", 15, 0.50),
            e("noise.bouncing", 5, 0.60)
        );
        assertThat(RepeatedThemeDetector.detect(list, NOW)).isEmpty();
    }

    @Test
    void encounters_outside_lookback_are_excluded() {
        // Two of these are >60 days old → excluded → only 1 recurrence remains.
        var list = List.of(
            e("old.theme", 90, 0.80),
            e("old.theme", 75, 0.60),
            e("old.theme", 5, 0.30)
        );
        assertThat(RepeatedThemeDetector.detect(list, NOW)).isEmpty();
    }

    @Test
    void declines_below_threshold_are_not_flagged() {
        // Decline of 0.05 — below the 0.1 floor.
        var list = List.of(
            e("minor.fluctuation", 30, 0.70),
            e("minor.fluctuation", 20, 0.68),
            e("minor.fluctuation", 5, 0.65)
        );
        assertThat(RepeatedThemeDetector.detect(list, NOW)).isEmpty();
    }

    @Test
    void multiple_themes_each_evaluated_independently() {
        var list = new ArrayList<ThemeEncounter>();
        // Theme A — degrading (will flag)
        list.add(e("theme.a", 30, 0.80));
        list.add(e("theme.a", 20, 0.55));
        list.add(e("theme.a", 5, 0.35));
        // Theme B — recovering (will not flag)
        list.add(e("theme.b", 28, 0.30));
        list.add(e("theme.b", 18, 0.50));
        list.add(e("theme.b", 3, 0.75));
        // Theme C — only 2 recurrences (below threshold)
        list.add(e("theme.c", 25, 0.70));
        list.add(e("theme.c", 10, 0.40));

        var findings = RepeatedThemeDetector.detect(list, NOW);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).themeKey()).isEqualTo("theme.a");
    }

    @Test
    void toDoomLoopFinding_bridges_with_correct_key_and_severity() {
        var list = List.of(
            e("grief.steward", 30, 0.85),
            e("grief.steward", 20, 0.50),
            e("grief.steward", 5, 0.20)
        );
        var findings = RepeatedThemeDetector.detect(list, NOW);
        assertThat(findings).hasSize(1);

        var dl = findings.get(0).toDoomLoopFinding();
        assertThat(dl.key()).isEqualTo("repeated_theme_degrading");
        assertThat(dl.severity()).isEqualTo(DoomLoopDetector.Severity.CRITICAL);
        assertThat(dl.message()).contains("grief.steward");
        assertThat(dl.message()).contains("3 times");
    }

    @Test
    void toDoomLoopFindings_handles_empty_and_null() {
        assertThat(RepeatedThemeDetector.toDoomLoopFindings(null)).isEmpty();
        assertThat(RepeatedThemeDetector.toDoomLoopFindings(List.of())).isEmpty();
    }

    @Test
    void toDoomLoopFindings_maps_all_findings() {
        var list = new ArrayList<ThemeEncounter>();
        list.add(e("theme.a", 30, 0.80));
        list.add(e("theme.a", 20, 0.55));
        list.add(e("theme.a", 5, 0.35));
        list.add(e("theme.b", 28, 0.90));
        list.add(e("theme.b", 18, 0.55));
        list.add(e("theme.b", 3, 0.30));

        var findings = RepeatedThemeDetector.detect(list, NOW);
        var dlFindings = RepeatedThemeDetector.toDoomLoopFindings(findings);
        assertThat(dlFindings).hasSize(findings.size());
        for (var dl : dlFindings) {
            assertThat(dl.key()).isEqualTo("repeated_theme_degrading");
        }
    }

    @Test
    void null_entries_in_input_are_skipped() {
        var list = new ArrayList<ThemeEncounter>();
        list.add(null);
        list.add(new ThemeEncounter(null, NOW, 0.5));
        list.add(new ThemeEncounter("ok.theme", null, 0.5));
        list.add(e("ok.theme", 30, 0.80));
        list.add(e("ok.theme", 20, 0.55));
        list.add(e("ok.theme", 5, 0.35));
        var findings = RepeatedThemeDetector.detect(list, NOW);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).themeKey()).isEqualTo("ok.theme");
    }

    @Test
    void detect_uses_current_instant_when_now_is_null() {
        // Just verify it doesn't throw — uses Instant.now() at call time.
        var list = List.of(
            new ThemeEncounter("theme.x", Instant.now().minus(Duration.ofDays(30)), 0.80),
            new ThemeEncounter("theme.x", Instant.now().minus(Duration.ofDays(20)), 0.55),
            new ThemeEncounter("theme.x", Instant.now().minus(Duration.ofDays(5)), 0.35)
        );
        var findings = RepeatedThemeDetector.detect(list, null);
        assertThat(findings).hasSize(1);
    }
}
