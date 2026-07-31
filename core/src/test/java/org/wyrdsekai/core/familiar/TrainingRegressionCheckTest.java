package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.BehavioralFingerprint;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link TrainingRegressionCheck} — L1 fingerprint drift vs baseline
 * imprint, with the corpus-write gate behavior.
 */
class TrainingRegressionCheckTest {

    private BehavioralFingerprint empty() {
        return BehavioralFingerprint.empty();
    }

    private BehavioralFingerprint withVitality(Map<String, Float> m) {
        return new BehavioralFingerprint(
            m, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            0.5f, 0.5f, List.of(), Map.of());
    }

    @Test
    void null_baseline_returns_ok() {
        var r = TrainingRegressionCheck.assess(empty(), null);
        assertThat(r.regressed()).isFalse();
        assertThat(r.reason()).contains("no baseline");
    }

    @Test
    void identical_fingerprints_no_regression() {
        var fp = withVitality(Map.of("energy", 0.5f, "care", 0.5f));
        var r = TrainingRegressionCheck.assess(fp, fp);
        assertThat(r.regressed()).isFalse();
        assertThat(r.drift()).isZero();
    }

    @Test
    void small_drift_within_ceiling_ok() {
        var a = withVitality(Map.of("energy", 0.5f, "care", 0.5f));
        var b = withVitality(Map.of("energy", 0.6f, "care", 0.55f));
        var r = TrainingRegressionCheck.assess(a, b);
        assertThat(r.regressed()).isFalse();
        assertThat(r.drift()).isBetween(0.0, 0.5);
    }

    @Test
    void large_drift_trips_regression() {
        var a = withVitality(Map.of("energy", 0.9f, "care", 0.9f, "fear", 0.9f));
        var b = withVitality(Map.of("energy", 0.0f, "care", 0.0f, "fear", 0.0f));
        var r = TrainingRegressionCheck.assess(a, b, 1.0);
        assertThat(r.regressed()).isTrue();
        assertThat(r.reason()).contains("drift");
    }

    @Test
    void overridable_ceiling() {
        var a = withVitality(Map.of("energy", 0.5f));
        var b = withVitality(Map.of("energy", 0.1f));
        var ok = TrainingRegressionCheck.assess(a, b, 1.0);
        assertThat(ok.regressed()).isFalse();
        var strict = TrainingRegressionCheck.assess(a, b, 0.1);
        assertThat(strict.regressed()).isTrue();
    }
}
