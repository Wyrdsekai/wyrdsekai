package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the prediction-error → SURPRISE/STARTLE grounding (2026-06-03).
 *
 * <p>Before this, the SURPRISE and STARTLE drives were configured tanks (decay,
 * relief, cross-mod) that NOTHING ever spiked — dead slots. {@link
 * OracleDriveIntegration#applyPredictionError} carried the live expectation-violation
 * signal but routed it only into seeking/play/grief/frustration, never the surprise
 * drive it was literally named for. These tests fail the build if that wire is
 * removed: surprise is the graded violation, startle only the large/abrupt one.
 */
class OracleDriveIntegrationTest {

    private final OracleDriveIntegration integ = new OracleDriveIntegration();

    @Test
    void anyExpectationViolation_spikesSurprise() {
        // A moderate prediction error (predicted 0.5, actual 1.0 → |delta|=0.5).
        var after = integ.applyPredictionError(0.5, 1.0, "perception", DriveState.initial());
        assertThat(after.surprise())
            .as("a moderate expectation violation must feed the SURPRISE drive")
            .isGreaterThan(0.0);
        // Not large enough to jolt.
        assertThat(after.startle())
            .as("a moderate violation does not trigger the reflexive STARTLE")
            .isEqualTo(0.0);
    }

    @Test
    void largeAbruptViolation_alsoSpikesStartle() {
        // A big surprise (predicted 0.05, actual 1.0 → |delta|=0.95 > 0.6 threshold).
        var after = integ.applyPredictionError(0.05, 1.0, "perception", DriveState.initial());
        assertThat(after.surprise()).isGreaterThan(0.0);
        assertThat(after.startle())
            .as("a large, abrupt violation triggers STARTLE on top of SURPRISE")
            .isGreaterThan(0.0);
    }

    @Test
    void expectedOutcome_movesNeitherDrive() {
        // |delta| < 0.1 → habituation, no change.
        var after = integ.applyPredictionError(0.95, 1.0, "perception", DriveState.initial());
        assertThat(after.surprise()).isEqualTo(0.0);
        assertThat(after.startle()).isEqualTo(0.0);
    }

    @Test
    void surpriseSurfacesInFeltState() {
        // The whole point of grounding: a surprised agent FEELS it in the prose
        // the drive brain reads (describe() previously capped its loop at 8 drives).
        var after = integ.applyPredictionError(0.0, 1.0, "perception", DriveState.initial());
        assertThat(after.describe().toLowerCase())
            .as("surprise above threshold should surface in the felt-state description")
            .contains("expectation");
    }
}
