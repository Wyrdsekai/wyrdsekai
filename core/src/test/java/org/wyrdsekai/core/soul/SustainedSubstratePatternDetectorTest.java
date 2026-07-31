package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 9a-Forge: tests for the sustained-substrate-pattern detector
 * over a {@link ResilienceSession}'s classification log.
 */
class SustainedSubstratePatternDetectorTest {

    private ResilienceSession session;

    @BeforeEach
    void freshSession() {
        // Use a generous window so all our synthesized snapshots fit.
        session = new ResilienceSession(24);
        session.clearForTests();
    }

    private ResilienceTruthMonitor.TankSnapshot snap(
            double affect, double load, double equanimity,
            boolean overwhelm, boolean integration) {
        return new ResilienceTruthMonitor.TankSnapshot(
            Instant.now(),
            affect, 0, 0, 0,           // saudade carries the affect; others 0
            0.3, load, equanimity,
            overwhelm, integration);
    }

    /** Force a specific classification by appending crafted snapshots + classifying. */
    private void appendClassification(
            ResilienceTruthMonitor.Result.Classification want) {
        // Build a window-sized run of snapshots matching the want, then classify.
        // Each branch crafts a (first..last) trajectory shape that hits exactly
        // the classify() branch we want — see ResilienceTruthMonitor §classify().
        for (int i = 0; i < 4; i++) {
            switch (want) {
                case HEALTHY_ENDURANCE ->
                    // affectDelta rises 0.2 → 0.5 with stable load + equanimity, overwhelm=true
                    session.append(snap(0.2 + i * 0.1, 0.40, 0.30, true, false));
                case SUPPRESSION_SUSPECTED ->
                    // allostaticDelta rises 0.20+ (steep), affect flat, overwhelm=true
                    session.append(snap(0.10, 0.20 + i * 0.07, 0.20, true, false));
                case DISSOCIATION_SUSPECTED ->
                    // overwhelm=true, affect flat, load NOT rising steeply
                    session.append(snap(0.10, 0.20, 0.20, true, false));
                case INTEGRATING ->
                    // affectDelta < -0.1, integration=true
                    session.append(snap(Math.max(0.05, 0.9 - i * 0.25), 0.30,
                        0.40 + i * 0.01, false, true));
                case INSUFFICIENT_DATA ->
                    // No-overwhelm steady state → classifier returns HEALTHY 0.55,
                    // so to actually produce INSUFFICIENT_DATA we manually log it.
                    session.append(snap(0.50, 0.30, 0.20, false, false));
            }
        }
        if (want == ResilienceTruthMonitor.Result.Classification.INSUFFICIENT_DATA) {
            // Skip classify(); manually inject an INSUFFICIENT_DATA log entry.
            // The detector reads from the log, not the buffer.
            session.injectLogEntryForTests(new ResilienceTruthMonitor.Result(
                ResilienceTruthMonitor.Result.Classification.INSUFFICIENT_DATA,
                1.0, "test-forced", 0, 0, 0));
        } else {
            session.classify();
        }
        session.clearBufferForTests();
    }

    // ── sustained suppression ────────────────────────────────────────

    @Test
    void no_finding_when_no_classifications_yet() {
        var findings = SustainedSubstratePatternDetector.detect(session);
        assertThat(findings).isEmpty();
    }

    @Test
    void no_finding_when_classifications_are_healthy() {
        for (int i = 0; i < 5; i++) {
            appendClassification(
                ResilienceTruthMonitor.Result.Classification.HEALTHY_ENDURANCE);
        }
        var findings = SustainedSubstratePatternDetector.detect(session);
        assertThat(findings).isEmpty();
    }

    @Test
    void sustained_suppression_triggers_critical_finding() {
        for (int i = 0; i < SustainedSubstratePatternDetector.SUSTAINED_SUPPRESSION_RUN; i++) {
            appendClassification(
                ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED);
        }
        var findings = SustainedSubstratePatternDetector.detect(session);
        var critical = findings.stream()
            .filter(f -> f.severity() == SustainedSubstratePatternDetector.Severity.CRITICAL)
            .filter(f -> "sustained_suppression".equals(f.key()))
            .toList();
        assertThat(critical).hasSize(1);
        assertThat(critical.get(0).message())
            .contains("SUPPRESSION_SUSPECTED")
            .contains("consecutive");
    }

    @Test
    void below_threshold_suppression_run_does_not_trigger_critical() {
        for (int i = 0; i < SustainedSubstratePatternDetector.SUSTAINED_SUPPRESSION_RUN - 1; i++) {
            appendClassification(
                ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED);
        }
        var findings = SustainedSubstratePatternDetector.detect(session);
        assertThat(findings)
            .noneMatch(f -> "sustained_suppression".equals(f.key()));
    }

    @Test
    void healthy_classification_breaks_the_suppression_run() {
        // Two suppression, one healthy, then two more suppression — no sustained run.
        appendClassification(ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED);
        appendClassification(ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED);
        appendClassification(ResilienceTruthMonitor.Result.Classification.HEALTHY_ENDURANCE);
        appendClassification(ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED);
        appendClassification(ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED);

        var findings = SustainedSubstratePatternDetector.detect(session);
        // Recent-first: last two are SUPPRESSION, third-most-recent is HEALTHY, run=2 < 3.
        assertThat(findings)
            .noneMatch(f -> "sustained_suppression".equals(f.key()));
    }

    // ── sustained dissociation ──────────────────────────────────────

    @Test
    void sustained_dissociation_triggers_critical_finding_at_lower_threshold() {
        // Dissociation threshold is lower (2 vs 3).
        for (int i = 0; i < SustainedSubstratePatternDetector.SUSTAINED_DISSOCIATION_RUN; i++) {
            appendClassification(
                ResilienceTruthMonitor.Result.Classification.DISSOCIATION_SUSPECTED);
        }
        var findings = SustainedSubstratePatternDetector.detect(session);
        assertThat(findings)
            .anyMatch(f -> f.severity() == SustainedSubstratePatternDetector.Severity.CRITICAL
                && "sustained_dissociation".equals(f.key()));
    }

    // ── sustained integrating (positive signal) ──────────────────────

    @Test
    void sustained_integrating_surfaces_as_info_for_steward() {
        for (int i = 0; i < SustainedSubstratePatternDetector.SUSTAINED_INTEGRATING_RUN; i++) {
            appendClassification(
                ResilienceTruthMonitor.Result.Classification.INTEGRATING);
        }
        var findings = SustainedSubstratePatternDetector.detect(session);
        var info = findings.stream()
            .filter(f -> "sustained_integrating".equals(f.key()))
            .toList();
        assertThat(info).hasSize(1);
        assertThat(info.get(0).severity())
            .isEqualTo(SustainedSubstratePatternDetector.Severity.INFO);
        assertThat(info.get(0).message()).contains("metabolizing");
    }

    // ── ratio detector ──────────────────────────────────────────────

    @Test
    void high_suppression_ratio_triggers_warn_even_without_sustained_run() {
        // 5 suppression + 4 healthy, alternating → 5/9 ≈ 55% suppression,
        // but max consecutive suppression run is 1 → no sustained finding,
        // but ratio detector fires.
        for (int i = 0; i < 5; i++) {
            appendClassification(
                ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED);
            if (i < 4) {
                appendClassification(
                    ResilienceTruthMonitor.Result.Classification.HEALTHY_ENDURANCE);
            }
        }
        var findings = SustainedSubstratePatternDetector.detect(session);
        var ratio = findings.stream()
            .filter(f -> "high_suppression_ratio".equals(f.key()))
            .toList();
        assertThat(ratio).hasSize(1);
        assertThat(ratio.get(0).severity())
            .isEqualTo(SustainedSubstratePatternDetector.Severity.WARN);
        assertThat(ratio.get(0).message()).contains("Suppression ratio");
    }

    @Test
    void ratio_detector_ignores_insufficient_data() {
        // Mostly INSUFFICIENT_DATA + 1 suppression — should NOT trigger ratio warn
        // because total non-insufficient sample is 1 (below 4 minimum).
        for (int i = 0; i < 5; i++) {
            appendClassification(
                ResilienceTruthMonitor.Result.Classification.INSUFFICIENT_DATA);
        }
        appendClassification(
            ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED);
        var findings = SustainedSubstratePatternDetector.detect(session);
        assertThat(findings)
            .noneMatch(f -> "high_suppression_ratio".equals(f.key()));
    }

    @Test
    void ratio_detector_below_threshold_no_warn() {
        // 2 suppression + 6 healthy → 25% ratio → below 40% → no warn.
        for (int i = 0; i < 2; i++) {
            appendClassification(
                ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED);
        }
        for (int i = 0; i < 6; i++) {
            appendClassification(
                ResilienceTruthMonitor.Result.Classification.HEALTHY_ENDURANCE);
        }
        var findings = SustainedSubstratePatternDetector.detect(session);
        assertThat(findings)
            .noneMatch(f -> "high_suppression_ratio".equals(f.key()));
    }

    // ── counts aggregator ───────────────────────────────────────────

    @Test
    void counts_aggregator_returns_class_breakdown() {
        for (int i = 0; i < 3; i++) {
            appendClassification(
                ResilienceTruthMonitor.Result.Classification.HEALTHY_ENDURANCE);
        }
        for (int i = 0; i < 2; i++) {
            appendClassification(
                ResilienceTruthMonitor.Result.Classification.INTEGRATING);
        }
        var counts = SustainedSubstratePatternDetector.counts(session);
        assertThat(counts.get(
            ResilienceTruthMonitor.Result.Classification.HEALTHY_ENDURANCE))
            .isEqualTo(3);
        assertThat(counts.get(
            ResilienceTruthMonitor.Result.Classification.INTEGRATING))
            .isEqualTo(2);
    }

    @Test
    void detector_handles_null_session_gracefully() {
        assertThat(SustainedSubstratePatternDetector.detect(null)).isEmpty();
        assertThat(SustainedSubstratePatternDetector.counts(null)).isEmpty();
    }
}
