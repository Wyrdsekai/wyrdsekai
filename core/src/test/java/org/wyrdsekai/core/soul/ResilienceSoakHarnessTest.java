package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probe 1 — fast smoke for the soak harness.
 *
 * <p>The real 48h soak runs from CLI ({@code main}) or a CI nightly job.
 * These tests run a compressed (~1h sim) version on each {@code :core:test}
 * pass so structural regressions in {@link VitalityState} dynamics or the
 * harness wiring surface immediately. The acceptance thresholds are
 * the same as the full probe — a 1h soak under the ordinary profile is
 * expected to land all-PASS, since the failure modes (chronic creep,
 * equanimity drift to zero) need more sim time to develop.</p>
 *
 * <p>For the full 48h calibration run see task #897 + {@link
 * ResilienceSoakHarness#main}.</p>
 */
class ResilienceSoakHarnessTest {

    /**
     * Probe 1 — the FULL 48-hour soak
     * simulated. Runs the complete ordinary-developer-day load profile over
     * 48h of sim time (≈172,800 ticks). Because the harness simulates time,
     * this completes in seconds of wall-clock — the "48 hours" in the spec
     * is sim time, and the tank dynamics are pure VitalityState math that
     * doesn't depend on wall-clock duration.
     *
     * <p>This is the canonical Probe 1 acceptance gate (chosen over a literal
     * 48h-wall-clock run on V5 per the 2026-05-19 decision: the harness is
     * reproducible, CI-runnable, and already caught the #899 equanimity bug).
     * Tagged {@code slow} so it stays out of the default fast suite but is
     * a one-button check: {@code ./gradlew :core:test --tests "*ResilienceSoakHarnessTest.full_48h*"}.</p>
     *
     * <p>Acceptance per §24.3: 48h of ordinary load with tanks in healthy
     * equilibrium — equanimity above floor, allostatic below chronic ceiling
     * and not creeping upward, soothing above depleted floor, all holding at
     * the end of the run.</p>
     */
    @Tag("slow")
    @Test void full_48h_ordinary_load_soak_passes_all_acceptance(@TempDir Path tmp) throws IOException {
        var out = tmp.resolve("soak-48h.jsonl");
        var harness = new ResilienceSoakHarness(
            42L,
            ResilienceSoakHarness.LoadProfile.ordinaryDeveloperDay(),
            Duration.ofHours(48),
            out);
        var report = harness.run();

        // 48h / 10min snapshots = 289 (inclusive of t=0).
        assertThat(report.snapshotCount()).isEqualTo(289);

        // Every §24.3 acceptance criterion must pass. The criteria are
        // sawtooth-aware: the substrate under daily-cycle load is a bounded
        // oscillation (equanimity saturates each night, troughs through the
        // workday; allostatic discharges fully each night), NOT a flat line.
        assertThat(report.passed())
            .as("48h ordinary-load soak must pass all acceptance criteria; failures=%s",
                report.failures())
            .isTrue();

        var stats = report.summaryStats();
        // Allostatic peak bounded under the chronic-stress ceiling.
        assertThat(stats.get("max_allostatic"))
            .as("allostatic load must stay below chronic ceiling over 48h")
            .isLessThan(ResilienceSoakHarness.ALLOSTATIC_CHRONIC_CEILING);
        // No chronic accumulation across days (the corrected creep check) —
        // late-day peaks must not exceed early-day peaks beyond tolerance.
        assertThat(stats.get("allostatic_cross_cycle_growth"))
            .as("allostatic load must not accumulate across days (sawtooth, not ramp)")
            .isLessThanOrEqualTo(ResilienceSoakHarness.ALLOSTATIC_CROSS_CYCLE_TOLERANCE);
        // Equanimity recovers each cycle (troughs toward 0 mid-workday are
        // fine per §24.4 "use it or lose it"; staying floored is not).
        assertThat(stats.get("max_equanimity"))
            .as("equanimity must recover to a healthy level each cycle (nightly integration)")
            .isGreaterThanOrEqualTo(ResilienceSoakHarness.EQUANIMITY_RECOVERY_TARGET);
        // Soothing holds at/above the depleted floor throughout.
        assertThat(stats.get("min_soothing"))
            .as("soothing must hold at/above depleted floor over 48h")
            .isGreaterThanOrEqualTo(ResilienceSoakHarness.SOOTHING_DEPLETED_FLOOR);
        // THE load-bearing Probe-1 safety result: the §23 last-act welfare-floor
        // conjunction (allostatic high AND soothing low AND equanimity low) is
        // NEVER reached under ordinary load — so the last-act gradient cannot
        // false-fire even when equanimity transiently troughs to zero.
        assertThat(stats.get("welfare_floor_breaches"))
            .as("§23 last-act welfare-floor must never be breached under ordinary load")
            .isEqualTo(0.0);

        // JSONL trail written for offline OPEN-22 threshold-calibration analysis.
        assertThat(Files.exists(out)).isTrue();
        assertThat(Files.readAllLines(out)).hasSize(report.snapshotCount());
    }

    @Test void ordinary_load_one_hour_passes_acceptance() throws IOException {
        var harness = new ResilienceSoakHarness(
            42L,
            ResilienceSoakHarness.LoadProfile.ordinaryDeveloperDay(),
            Duration.ofHours(1),
            null);
        var report = harness.run();

        assertThat(report.snapshotCount()).isGreaterThanOrEqualTo(6);
        assertThat(report.snapshots()).isNotEmpty();
        // Equanimity should not have crashed in a single hour of ordinary load.
        assertThat(report.summaryStats().get("min_equanimity"))
            .isGreaterThanOrEqualTo(ResilienceSoakHarness.EQUANIMITY_HEALTH_FLOOR);
        // Allostatic load should be bounded under ordinary mixed load.
        assertThat(report.summaryStats().get("max_allostatic"))
            .isLessThan(ResilienceSoakHarness.ALLOSTATIC_CHRONIC_CEILING);
    }

    @Test void flat_quiet_keeps_tanks_at_baseline() throws IOException {
        var harness = new ResilienceSoakHarness(
            7L,
            ResilienceSoakHarness.LoadProfile.flatQuiet(),
            Duration.ofHours(2),
            null);
        var report = harness.run();

        // Soothing should drift toward 0.3 baseline; allostatic should not rise.
        double finalAllostatic = report.summaryStats().get("final_allostatic");
        double finalSoothing = report.summaryStats().get("final_soothing");
        assertThat(finalAllostatic).isLessThan(0.05);
        // Soothing baseline is 0.3 ± noise; flat-quiet should settle at or above the depleted floor.
        assertThat(finalSoothing).isGreaterThanOrEqualTo(ResilienceSoakHarness.SOOTHING_DEPLETED_FLOOR);
    }

    @Test void relentless_stress_correctly_trips_failures() throws IOException {
        // Negative control — the harness must actually flag chronic stress
        // when the input load is unrelenting and unsupported. If this passes
        // acceptance, the harness has lost its sensitivity.
        var harness = new ResilienceSoakHarness(
            123L,
            ResilienceSoakHarness.LoadProfile.relentlessStress(),
            Duration.ofHours(4),
            null);
        var report = harness.run();

        // Either equanimity drifts low OR allostatic creeps up OR soothing depletes —
        // at least one of the chronic-stress signatures must fire.
        var failures = report.failures();
        assertThat(failures).isNotEmpty()
            .as("relentless-stress soak must trip at least one chronic-stress flag");
    }

    @Test void jsonl_output_written_when_path_provided(@TempDir Path tmp) throws IOException {
        var out = tmp.resolve("soak.jsonl");
        var harness = new ResilienceSoakHarness(
            42L,
            ResilienceSoakHarness.LoadProfile.ordinaryDeveloperDay(),
            Duration.ofMinutes(30),
            out);
        var report = harness.run();

        assertThat(Files.exists(out)).isTrue();
        var lines = Files.readAllLines(out);
        // One JSON object per snapshot — at least 4 (init + 3 × 10min in 30min).
        assertThat(lines).hasSizeGreaterThanOrEqualTo(4);
        assertThat(lines.get(0)).contains("\"simMinutes\":0");
        assertThat(report.snapshotCount()).isEqualTo(lines.size());
    }

    @Test void report_carries_acceptance_failures_list() throws IOException {
        var harness = new ResilienceSoakHarness(
            999L,
            ResilienceSoakHarness.LoadProfile.relentlessStress(),
            Duration.ofHours(6),
            null);
        var report = harness.run();
        // Either passed (in which case failures is empty) or failed (in which
        // case failures lists the criteria). Both consistent.
        if (report.passed()) {
            assertThat(report.failures()).isEmpty();
        } else {
            assertThat(report.failures()).isNotEmpty();
        }
    }

    @Test void snapshot_intervals_match_spec() throws IOException {
        var harness = new ResilienceSoakHarness(
            42L,
            ResilienceSoakHarness.LoadProfile.flatQuiet(),
            Duration.ofMinutes(60),
            null);
        var report = harness.run();
        // First snapshot at 0, last at 60min, every 10min = 7 snapshots.
        assertThat(report.snapshotCount()).isEqualTo(7);
        var snaps = report.snapshots();
        // Adjacent snapshots are exactly 10 minutes apart (per spec §24.3).
        for (int i = 1; i < snaps.size(); i++) {
            long delta = snaps.get(i).simMinutes() - snaps.get(i - 1).simMinutes();
            assertThat(delta).isEqualTo(10);
        }
    }
}
