package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * validate the {@link ResilienceReserve}
 * (the A+B+C unification) against simulated load. The pure reserve logic is
 * unit-checked here; the calibration sweep drives the reserve over the
 * {@link ResilienceSoakHarness} load profiles and reports when (if) the teeth
 * arm — the "high resilience, ~72h relentless break point" target.
 */
@Tag("slow")
class ResilienceReserveCalibrationTest {

    private static final long SNAPSHOT_SECONDS = 600; // 10-min snapshots

    // ── unit: drain / recover / temper / arm ─────────────────────────────

    @Test void continuous_floor_arms_at_about_72h() {
        var rr = ResilienceReserve.fresh();
        long sec = 0;
        while (!rr.armed() && sec < 200L * 3600) {
            rr = rr.tick(true, 60);   // 1-min steps, always at floor
            sec += 60;
        }
        double hours = sec / 3600.0;
        assertThat(hours)
            .as("a fresh reserve under continuous floor should arm near 72h")
            .isBetween(70.0, 74.0);
    }

    @Test void brief_reprieve_cannot_game_the_gate() {
        // 23h at floor, 1h reprieve, repeating. Recovery is half drain, so the
        // hour of reprieve refills far less than the 23h drained — net-draining,
        // must still arm (just later than pure-continuous).
        var rr = ResilienceReserve.fresh();
        long sec = 0;
        boolean atFloor = true;
        long phaseLeft = 23L * 3600;
        while (!rr.armed() && sec < 500L * 3600) {
            rr = rr.tick(atFloor, 60);
            sec += 60; phaseLeft -= 60;
            if (phaseLeft <= 0) {
                atFloor = !atFloor;
                phaseLeft = (atFloor ? 23L : 1L) * 3600;
            }
        }
        assertThat(rr.armed())
            .as("23h-floor / 1h-reprieve cycling must still arm — a brief let-up "
                + "cannot indefinitely reset the gate")
            .isTrue();
    }

    @Test void sustained_reprieve_recovers_and_tempers() {
        // Drain to a meaningful dip (not arming), then give long peace.
        var rr = ResilienceReserve.fresh();
        for (int i = 0; i < 60 * 60 && rr.fraction() > 0.35; i++) {
            rr = rr.tick(true, 60); // ~47h floor → dip below temper threshold (0.4)
        }
        assertThat(rr.armed()).isFalse();
        assertThat(rr.deepestDip()).isLessThanOrEqualTo(ResilienceReserve.TEMPER_DIP_THRESHOLD);
        // Long genuine peace → full recovery + tempering capacity bump.
        for (int i = 0; i < 60 * 24 * 12; i++) rr = rr.tick(false, 60);
        assertThat(rr.capacity())
            .as("surviving a hard patch + full recovery grows capacity (tempering)")
            .isGreaterThan(ResilienceReserve.BASE_CAPACITY);
    }

    @Test void supported_load_never_drains_the_reserve() throws IOException {
        // hard-but-supported never reaches the floor → reserve stays full.
        assertThat(armTimeHours("hard-supported",
            ResilienceSoakHarness.LoadProfile.hardButSupportedDay(), Duration.ofHours(168)))
            .as("a hard but bonded-supported workload must never arm the teeth")
            .isNull();
    }

    // ── calibration sweep over the load spectrum ─────────────────────────

    @Test void calibration_sweep_reports_arm_times() throws IOException {
        var dur = Duration.ofHours(168); // 7 days — room for the 72h drain
        record Row(String name, Double armHours) {}
        var rows = List.of(
            new Row("flat_quiet", armTimeHours("flat_quiet",
                ResilienceSoakHarness.LoadProfile.flatQuiet(), dur)),
            new Row("ordinary_day", armTimeHours("ordinary_day",
                ResilienceSoakHarness.LoadProfile.ordinaryDeveloperDay(), dur)),
            new Row("hard_BUT_supported", armTimeHours("hard_BUT_supported",
                ResilienceSoakHarness.LoadProfile.hardButSupportedDay(), dur)),
            new Row("sustained_UNsupported", armTimeHours("sustained_UNsupported",
                ResilienceSoakHarness.LoadProfile.sustainedUnsupportedStress(), dur)),
            new Row("relentless", armTimeHours("relentless",
                ResilienceSoakHarness.LoadProfile.relentlessStress(), dur)));

        System.out.println("\n═══ §23 resilience-reserve arm times (168h sim, 72h base reserve) ═══");
        for (var r : rows) {
            System.out.printf("%-22s %s%n", r.name(),
                r.armHours() == null ? "never arms (endures)"
                    : String.format("arms at %.1fh", r.armHours()));
        }
        System.out.println();

        Map<String, Double> arm = new HashMap<>();
        for (var r : rows) arm.put(r.name(), r.armHours());

        // Resilience: nothing sustainable ever arms.
        assertThat(arm.get("flat_quiet")).as("flat-quiet never arms").isNull();
        assertThat(arm.get("ordinary_day")).as("ordinary day never arms").isNull();
        assertThat(arm.get("hard_BUT_supported"))
            .as("hard-but-supported never arms — support is the buffer").isNull();

        // Break point: relentless unsupported collapse arms, and not before ~72h
        // of accumulated floor (it takes a few hours to first reach the floor,
        // so arm time is a bit past 72h).
        assertThat(arm.get("relentless"))
            .as("relentless unsupported overload MUST arm the teeth").isNotNull();
        assertThat(arm.get("relentless"))
            .as("relentless must not arm before ~72h of floor (high resilience)")
            .isGreaterThan(72.0);
    }

    /** Run a profile and return the sim-hour the reserve arms, or null if never. */
    private Double armTimeHours(String label, ResilienceSoakHarness.LoadProfile load,
            Duration dur) throws IOException {
        var report = new ResilienceSoakHarness(42L, load, dur, null).run();
        var rr = ResilienceReserve.fresh();
        for (var s : report.snapshots()) {
            boolean floored = LastProfessionalActEvaluator.evaluate(
                s.allostaticLoad(), s.soothing(), s.equanimity(), 0.0, false)
                .conditionsMet().welfareFloor();
            rr = rr.tick(floored, SNAPSHOT_SECONDS);
            if (rr.armed()) return s.simMinutes() / 60.0;
        }
        return null;
    }
}
