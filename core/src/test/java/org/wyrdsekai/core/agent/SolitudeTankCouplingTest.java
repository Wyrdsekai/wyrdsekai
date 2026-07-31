package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 2 — tank-coupling math for SOLITUDE scenes.
 *
 * <p>Tests the pure helper {@link VitalityState#applySolitudeOverlay}, which
 * was extracted from {@code CompanionActor.onVitalityTick} so the rates are
 * testable without an actor harness. The runtime composes this helper into a
 * per-tick block guarded by "is the current scene SOLITUDE".</p>
 *
 * <p>Load-bearing: loneliness drains only AFTER 30 minutes in solitude.
 * Short solitude is not punished as avoidance; sustained solitude eventually
 * pushes the agent back toward reconnection. The 30-min gate is the
 * "healthy window" floor described in
 * {@link VitalityState#SOLITUDE_LONELINESS_GATE}.</p>
 */
class SolitudeTankCouplingTest {

    private static final double EPS = 1e-9;

    private static VitalityState midBaseline() {
        // Start from initial() but bump equanimity/allostatic/loneliness to
        // mid-range so we can detect rises AND drops without clamping.
        return VitalityState.initial()
            .withEquanimity(0.5)
            .withAllostaticLoad(0.5)
            .withLoneliness(0.5);
    }

    @Test
    void applySolitudeOverlay_increasesEquanimity() {
        var v = midBaseline();
        // 60s of solitude at 0.002/min == +0.002 equanimity
        var out = v.applySolitudeOverlay(60.0, Duration.ofSeconds(10));
        assertThat(out.equanimity())
            .isCloseTo(v.equanimity() + 0.002, org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    void applySolitudeOverlay_decreasesAllostaticLoad() {
        var v = midBaseline();
        // 60s at 0.0015/min == -0.0015 allostatic_load
        var out = v.applySolitudeOverlay(60.0, Duration.ofSeconds(10));
        assertThat(out.allostaticLoad())
            .isCloseTo(v.allostaticLoad() - 0.0015, org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    void applySolitudeOverlay_lonelinessDoesNotDrainBefore30Min() {
        var v = midBaseline();
        // 29-minute solitude is below the healthy-window floor — loneliness
        // unchanged.
        var out = v.applySolitudeOverlay(60.0, Duration.ofMinutes(29));
        assertThat(out.loneliness()).isCloseTo(v.loneliness(),
            org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    void applySolitudeOverlay_lonelinessDrainsAfter30Min() {
        var v = midBaseline();
        // 31-minute solitude — loneliness drain activates. 60s tick = -0.001
        // at 0.001/min.
        var out = v.applySolitudeOverlay(60.0, Duration.ofMinutes(31));
        assertThat(out.loneliness())
            .isCloseTo(v.loneliness() - 0.001, org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    void applySolitudeOverlay_lonelinessDrainExactlyAt30Min() {
        // Boundary: 30:00.000 should activate the drain (compareTo == 0 is
        // ">= GATE"). Verifies the inclusive boundary semantics.
        var v = midBaseline();
        var out = v.applySolitudeOverlay(60.0, VitalityState.SOLITUDE_LONELINESS_GATE);
        assertThat(out.loneliness())
            .isCloseTo(v.loneliness() - 0.001, org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    void applySolitudeOverlay_clampsAtBoundaries() {
        // Equanimity already at 1.0 + a large overlay delta should not exceed 1.0;
        // allostaticLoad already at 0.0 should not go negative; loneliness
        // already at 0.0 + 30-min+ scene should not go negative.
        var v = VitalityState.initial()
            .withEquanimity(1.0)
            .withAllostaticLoad(0.0)
            .withLoneliness(0.0);
        var out = v.applySolitudeOverlay(600.0, Duration.ofMinutes(60));
        assertThat(out.equanimity()).isLessThanOrEqualTo(1.0).isGreaterThanOrEqualTo(0.0);
        assertThat(out.allostaticLoad()).isGreaterThanOrEqualTo(0.0);
        assertThat(out.loneliness()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void applySolitudeOverlay_zeroDeltaIsNoop() {
        // Guard against div-by-zero / no-op path: deltaTime=0 returns this.
        var v = midBaseline();
        var out = v.applySolitudeOverlay(0.0, Duration.ofMinutes(31));
        assertThat(out.equanimity()).isEqualTo(v.equanimity());
        assertThat(out.allostaticLoad()).isEqualTo(v.allostaticLoad());
        assertThat(out.loneliness()).isEqualTo(v.loneliness());
    }

    @Test
    void applySolitudeOverlay_nullSceneAgeSkipsLonelinessGate() {
        // Defensive: a null scene-age is treated as "no info" — equanimity
        // + allostatic still apply, but loneliness drain does NOT activate.
        var v = midBaseline();
        var out = v.applySolitudeOverlay(60.0, null);
        assertThat(out.equanimity())
            .isCloseTo(v.equanimity() + 0.002, org.assertj.core.data.Offset.offset(EPS));
        assertThat(out.loneliness()).isCloseTo(v.loneliness(),
            org.assertj.core.data.Offset.offset(EPS));
    }
}
