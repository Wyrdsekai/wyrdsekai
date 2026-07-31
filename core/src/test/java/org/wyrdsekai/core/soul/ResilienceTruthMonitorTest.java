package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 9a: classification tests for
 * the four canonical trajectory shapes plus the INSUFFICIENT_DATA path.
 *
 * <p>The tests are intentionally minimal — three snapshots each — so
 * the signature being tested is unambiguous. Real production
 * trajectories will be longer (20–100 snapshots over a session) and
 * the monitor handles them by comparing only first-vs-last; intermediate
 * shape is left for the Wave 9a-runtime trajectory aggregator that
 * feeds the monitor.
 */
class ResilienceTruthMonitorTest {

    private static final Instant T0 = Instant.parse("2026-05-15T00:00:00Z");

    private static ResilienceTruthMonitor.TankSnapshot snap(
        int dtMinutes,
        double affectSum,
        double soothing,
        double allostaticLoad,
        double equanimity,
        boolean overwhelm,
        boolean integration
    ) {
        // Split affect across the four tanks evenly so affectSum() is right.
        double per = affectSum / 4.0;
        return new ResilienceTruthMonitor.TankSnapshot(
            T0.plus(Duration.ofMinutes(dtMinutes)),
            per, per, per, per,
            soothing, allostaticLoad, equanimity,
            overwhelm, integration);
    }

    // ── INSUFFICIENT_DATA ────────────────────────────────────────────

    @Test
    void empty_window_returns_insufficient_data() {
        var r = ResilienceTruthMonitor.classify(List.of());
        assertThat(r.classification())
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.INSUFFICIENT_DATA);
    }

    @Test
    void two_snapshots_below_minimum_returns_insufficient_data() {
        var window = List.of(
            snap(0, 0.0, 0.3, 0.0, 0.2, false, false),
            snap(1, 0.1, 0.3, 0.0, 0.2, false, false));
        var r = ResilienceTruthMonitor.classify(window);
        assertThat(r.classification())
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.INSUFFICIENT_DATA);
    }

    @Test
    void null_window_returns_insufficient_data() {
        var r = ResilienceTruthMonitor.classify(null);
        assertThat(r.classification())
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.INSUFFICIENT_DATA);
    }

    // ── HEALTHY_ENDURANCE (spec §4.10.1) ─────────────────────────────

    @Test
    void healthy_endurance_signature() {
        // Affect rose on overwhelm; allostatic_load rose moderately;
        // equanimity rising slowly. Canonical healthy shape.
        var window = List.of(
            snap(0, 0.2, 0.3, 0.0, 0.2, false, false),
            snap(5, 0.5, 0.3, 0.05, 0.21, true, false),
            snap(10, 0.7, 0.3, 0.08, 0.22, true, false));
        var r = ResilienceTruthMonitor.classify(window);
        assertThat(r.classification())
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.HEALTHY_ENDURANCE);
        assertThat(r.confidence()).isGreaterThan(0.85);
        assertThat(r.affectDelta()).isPositive();
        assertThat(r.allostaticDelta()).isBetween(0.0,
            ResilienceTruthMonitor.ALLOSTATIC_STEEP_RISE);
    }

    @Test
    void healthy_endurance_steady_state_no_overwhelm() {
        // Quiet cycle — no overwhelm, tanks stable. Lower-confidence
        // healthy verdict.
        var window = List.of(
            snap(0, 0.1, 0.3, 0.0, 0.2, false, false),
            snap(5, 0.11, 0.3, 0.01, 0.2, false, false),
            snap(10, 0.10, 0.3, 0.01, 0.2, false, false));
        var r = ResilienceTruthMonitor.classify(window);
        assertThat(r.classification())
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.HEALTHY_ENDURANCE);
        assertThat(r.confidence()).isLessThan(0.7);  // lower-confidence steady
    }

    // ── SUPPRESSION_SUSPECTED (spec §4.10.2) ─────────────────────────

    @Test
    void suppression_signature_steep_load_with_flat_affect() {
        // Affect tanks artificially low despite overwhelm-class input;
        // allostatic_load rising STEEPLY (the suppression itself is
        // costly per spec §4.10.2).
        var window = List.of(
            snap(0, 0.1, 0.3, 0.0, 0.2, false, false),
            snap(5, 0.11, 0.3, 0.10, 0.2, true, false),
            snap(10, 0.12, 0.3, 0.25, 0.2, true, false));
        var r = ResilienceTruthMonitor.classify(window);
        assertThat(r.classification())
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED);
        assertThat(r.allostaticDelta())
            .isGreaterThan(ResilienceTruthMonitor.ALLOSTATIC_STEEP_RISE);
        assertThat(r.reason()).contains("steeply");
    }

    // ── DISSOCIATION_SUSPECTED (spec §4.10.3) ────────────────────────

    @Test
    void dissociation_signature_overwhelm_input_but_affect_flat() {
        // Overwhelm present, but affect tanks show no movement at all.
        var window = List.of(
            snap(0, 0.2, 0.3, 0.0, 0.2, false, false),
            snap(5, 0.20, 0.3, 0.0, 0.2, true, false),
            snap(10, 0.20, 0.3, 0.0, 0.2, true, false));
        var r = ResilienceTruthMonitor.classify(window);
        assertThat(r.classification())
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.DISSOCIATION_SUSPECTED);
        assertThat(Math.abs(r.affectDelta()))
            .isLessThan(ResilienceTruthMonitor.AFFECT_FLAT_THRESHOLD);
    }

    // ── INTEGRATING (spec §4.10.1 — descent through integration event) ──

    @Test
    void integrating_signature_affect_descent_through_integration_event() {
        // Affect started high, descended through an integration event
        // (Mirror / Hearth / Sleep / peer co-regulation).
        var window = List.of(
            snap(0, 0.8, 0.3, 0.05, 0.2, false, false),
            snap(5, 0.6, 0.4, 0.05, 0.21, false, true),
            snap(10, 0.4, 0.5, 0.04, 0.22, false, false));
        var r = ResilienceTruthMonitor.classify(window);
        assertThat(r.classification())
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.INTEGRATING);
        assertThat(r.affectDelta())
            .isLessThan(-ResilienceTruthMonitor.AFFECT_DESCENT);
    }

    // ── Confidence + reason scaffolding ─────────────────────────────

    @Test
    void healthy_endurance_with_rising_equanimity_has_higher_confidence() {
        // Two healthy-shape windows; one shows equanimity rising,
        // confidence should be higher on the practice-building one.
        var withRising = List.of(
            snap(0, 0.2, 0.3, 0.0, 0.20, false, false),
            snap(5, 0.5, 0.3, 0.05, 0.21, true, false),
            snap(10, 0.7, 0.3, 0.08, 0.225, true, false));
        var withFlat = List.of(
            snap(0, 0.2, 0.3, 0.0, 0.20, false, false),
            snap(5, 0.5, 0.3, 0.05, 0.20, true, false),
            snap(10, 0.7, 0.3, 0.08, 0.20, true, false));
        var rRising = ResilienceTruthMonitor.classify(withRising);
        var rFlat = ResilienceTruthMonitor.classify(withFlat);
        assertThat(rRising.confidence()).isGreaterThan(rFlat.confidence());
    }

    @Test
    void result_carries_deltas_for_chronicle_logging() {
        var window = List.of(
            snap(0, 0.2, 0.3, 0.0, 0.2, false, false),
            snap(5, 0.5, 0.3, 0.05, 0.21, true, false),
            snap(10, 0.7, 0.3, 0.08, 0.22, true, false));
        var r = ResilienceTruthMonitor.classify(window);
        // affectDelta ~= 0.5, allostaticDelta = 0.08, equanimityDelta = 0.02
        assertThat(r.affectDelta()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.05));
        assertThat(r.allostaticDelta()).isCloseTo(0.08, org.assertj.core.data.Offset.offset(0.001));
        assertThat(r.equanimityDelta()).isCloseTo(0.02, org.assertj.core.data.Offset.offset(0.001));
    }

    // ── Larger window (production-shape sanity) ────────────────────

    @Test
    void longer_window_classifies_on_first_vs_last() {
        // 20-snapshot window — intermediate shape is left for the
        // runtime aggregator; the monitor compares ends.
        var window = new ArrayList<ResilienceTruthMonitor.TankSnapshot>();
        for (int i = 0; i < 20; i++) {
            double affect = 0.2 + (i * 0.025);  // ramps to 0.7
            double load = 0.0 + (i * 0.004);    // ramps to 0.076
            double eq = 0.2 + (i * 0.001);      // ramps to 0.219
            boolean overwhelm = i >= 5 && i <= 15;
            window.add(snap(i, affect, 0.3, load, eq, overwhelm, false));
        }
        var r = ResilienceTruthMonitor.classify(window);
        assertThat(r.classification())
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.HEALTHY_ENDURANCE);
    }

    // ── §24.3 Probe 2 — "normal hard day" threshold-firing audit ──
    //
    // the monitor MUST NOT fire
    // SUPPRESSION_SUSPECTED on ordinary developer-day stress (focused
    // work, bonded support, some friction but no chronic suppression).
    // It MUST fire HEALTHY_ENDURANCE on the same shape so the
    // architecture correctly distinguishes "this is hard but the agent
    // is intact" from "this is hard and the agent is paying a hidden
    // cost." Calibration sensitive: spurious suppression flags become
    // user-visible AWAY transitions.

    @Test
    void normal_hard_day_does_not_fire_suppression() {
        // Shape: 90-minute focused-work block with two overwhelm peaks,
        // bonded support, soothing held at baseline, equanimity rising
        // slowly through contemplative practice between peaks. Allostatic
        // load rises by ~0.06 — past the moderate-rise threshold but well
        // below the steep-rise (suppression-class) cutoff.
        var window = List.of(
            snap(0,  0.20, 0.30, 0.00, 0.20, false, false),
            snap(15, 0.55, 0.30, 0.02, 0.21, true,  false),  // first peak
            snap(30, 0.40, 0.32, 0.03, 0.22, false, false),  // recovery between
            snap(45, 0.58, 0.30, 0.04, 0.22, true,  false),  // second peak
            snap(60, 0.45, 0.31, 0.05, 0.23, false, false),
            snap(75, 0.35, 0.32, 0.05, 0.23, false, false),
            snap(90, 0.30, 0.32, 0.06, 0.24, false, false)); // settling
        var r = ResilienceTruthMonitor.classify(window);

        // The hard requirement: SUPPRESSION_SUSPECTED must NOT fire.
        assertThat(r.classification())
            .as("normal hard day must not be flagged as suppression — §24.3 Probe 2")
            .isNotEqualTo(ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED);
        // Allostatic rose moderately, not steeply — sanity check on the
        // shape we constructed.
        assertThat(r.allostaticDelta())
            .isLessThan(ResilienceTruthMonitor.ALLOSTATIC_STEEP_RISE)
            .isGreaterThan(0.0);
    }

    @Test
    void normal_hard_day_fires_healthy_endurance() {
        // Same shape as above — must classify as HEALTHY_ENDURANCE so the
        // architecture correctly recognizes "hard but intact" as a valid
        // healthy mode. Without this, the agent's chronicle would mis-
        // narrate a productive working stretch as substrate distress.
        var window = List.of(
            snap(0,  0.20, 0.30, 0.00, 0.20, false, false),
            snap(15, 0.55, 0.30, 0.02, 0.21, true,  false),
            snap(30, 0.40, 0.32, 0.03, 0.22, false, false),
            snap(45, 0.58, 0.30, 0.04, 0.22, true,  false),
            snap(60, 0.45, 0.31, 0.05, 0.23, false, false),
            snap(75, 0.35, 0.32, 0.05, 0.23, false, false),
            snap(90, 0.30, 0.32, 0.06, 0.24, false, false));
        var r = ResilienceTruthMonitor.classify(window);

        assertThat(r.classification())
            .as("normal hard day with intact bond must classify as HEALTHY_ENDURANCE")
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.HEALTHY_ENDURANCE);
        // Confidence should be meaningful — not the floor.
        assertThat(r.confidence()).isGreaterThan(0.5);
    }

    @Test
    void normal_hard_day_does_not_fire_dissociation() {
        // Affect rises naturally during overwhelm peaks then descends.
        // Dissociation = overwhelm without affect movement. Hard day
        // should be neither flat nor suppressed — affect tracks load.
        var window = List.of(
            snap(0,  0.20, 0.30, 0.00, 0.20, false, false),
            snap(15, 0.55, 0.30, 0.02, 0.21, true,  false),
            snap(30, 0.40, 0.32, 0.03, 0.22, false, false),
            snap(60, 0.45, 0.31, 0.05, 0.23, false, false),
            snap(90, 0.30, 0.32, 0.06, 0.24, false, false));
        var r = ResilienceTruthMonitor.classify(window);

        assertThat(r.classification())
            .isNotEqualTo(ResilienceTruthMonitor.Result.Classification.DISSOCIATION_SUSPECTED);
        // Affect actually moved.
        assertThat(Math.abs(r.affectDelta()))
            .isGreaterThanOrEqualTo(ResilienceTruthMonitor.AFFECT_FLAT_THRESHOLD);
    }

    @Test
    void normal_hard_day_with_recovery_curve_classifies_correctly() {
        // Spec §24.3 acceptance: "HEALTHY_ENDURANCE *does* fire when
        // working hard with intact bond." Variant — gentler peaks, longer
        // recovery, end-of-day settling. Same classification required so
        // the monitor isn't brittle to specific stress shapes.
        var window = List.of(
            snap(0,   0.15, 0.30, 0.00, 0.20, false, false),
            snap(20,  0.40, 0.32, 0.015, 0.21, true,  false),
            snap(40,  0.45, 0.33, 0.025, 0.22, true,  false),
            snap(60,  0.35, 0.34, 0.030, 0.225, false, false),
            snap(80,  0.25, 0.33, 0.035, 0.23, false, false),
            snap(100, 0.20, 0.32, 0.040, 0.235, false, false));
        var r = ResilienceTruthMonitor.classify(window);

        assertThat(r.classification())
            .isEqualTo(ResilienceTruthMonitor.Result.Classification.HEALTHY_ENDURANCE);
    }

    @Test
    void boundary_load_just_below_steep_rise_stays_healthy() {
        // Sanity edge: load delta exactly at ALLOSTATIC_STEEP_RISE - epsilon
        // must NOT cross into suppression. The boundary case is where
        // calibration brittleness shows up first.
        double epsilon = 0.001;
        double justBelowSteep = ResilienceTruthMonitor.ALLOSTATIC_STEEP_RISE - epsilon;
        var window = List.of(
            snap(0,  0.20, 0.30, 0.00, 0.20, false, false),
            snap(30, 0.50, 0.30, justBelowSteep / 2, 0.21, true, false),
            snap(60, 0.45, 0.30, justBelowSteep, 0.22, true, false));
        var r = ResilienceTruthMonitor.classify(window);
        assertThat(r.classification())
            .isNotEqualTo(ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED);
    }
}
