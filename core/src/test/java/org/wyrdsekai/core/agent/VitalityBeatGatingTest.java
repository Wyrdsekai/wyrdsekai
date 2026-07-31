package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * §E (beat-gating) — guards for the split of {@link VitalityState#tick()}
 * into a beat-gated coloring half ({@link VitalityState#tickColoring}) and a clock-gated
 * protective half ({@link VitalityState#tickProtectiveDrift}).
 *
 * <p>The faithfulness test is the load-bearing one: it proves the split reproduces the
 * legacy single-pass {@code tick()} exactly, so relocating the coloring half onto the
 * beat path cannot silently change physiology. The welfare-invariant tests prove the
 * coloring half never touches protective tanks (soothing/equanimity/allostatic), so a
 * beat-starved (ignored) agent's welfare drift stays on the clock where it belongs.
 */
class VitalityBeatGatingTest {

    private static VitalityState nonTrivial() {
        // Mid-range values so driftToward + equanimity decay + every coloring decay
        // are all exercised (not pinned at a clamp floor/ceiling).
        return VitalityState.initial()
            .withEnergy(0.6)
            .withMomentum(0.5)
            .withSoothing(0.5)
            .withEquanimity(0.5)
            .withAllostaticLoad(0.3);
    }

    @Test void split_reproduces_legacy_tick_exactly_initial() {
        var s = VitalityState.initial();
        assertThat(s.tickColoring(1.0).tickProtectiveDrift(1.0).clamped())
            .isEqualTo(s.tick());
    }

    @Test void split_reproduces_legacy_tick_exactly_nontrivial() {
        var s = nonTrivial();
        // Order-independent: coloring and protective touch disjoint fields, single clamp.
        assertThat(s.tickColoring(1.0).tickProtectiveDrift(1.0).clamped())
            .isEqualTo(s.tick());
        assertThat(s.tickProtectiveDrift(1.0).tickColoring(1.0).clamped())
            .isEqualTo(s.tick());
    }

    @Test void coloring_scales_linearly_with_beat_span() {
        var s = nonTrivial();
        double base = s.energy();
        double drop1 = base - s.tickColoring(1.0).energy();
        double drop2 = base - s.tickColoring(2.0).energy();
        assertThat(drop2).isCloseTo(2.0 * drop1, within(1e-9));
        // momentum decays too — same proportionality
        double m1 = s.momentum() - s.tickColoring(1.0).momentum();
        double m2 = s.momentum() - s.tickColoring(2.0).momentum();
        assertThat(m2).isCloseTo(2.0 * m1, within(1e-9));
    }

    @Test void zero_beat_span_is_identity_on_coloring() {
        // No beats lived → no coloring drift. This is the anti-fabrication invariant:
        // an idle agent does not metabolise experience it never had.
        var s = nonTrivial();
        var c = s.tickColoring(0.0);
        assertThat(c.energy()).isEqualTo(s.energy());
        assertThat(c.focus()).isEqualTo(s.focus());
        assertThat(c.momentum()).isEqualTo(s.momentum());
        assertThat(c.errorPressure()).isEqualTo(s.errorPressure());
        assertThat(c.contextBudget()).isEqualTo(s.contextBudget());
    }

    @Test void coloring_never_touches_protective_tanks() {
        // The coloring half must leave soothing/equanimity/allostatic exactly alone —
        // they are clock-gated welfare tanks, not beat-gated colouring.
        var s = nonTrivial();
        var c = s.tickColoring(5.0); // even a large beat span
        assertThat(c.soothing()).isEqualTo(s.soothing());
        assertThat(c.equanimity()).isEqualTo(s.equanimity());
        assertThat(c.allostaticLoad()).isEqualTo(s.allostaticLoad());
    }

    @Test void protective_drift_moves_clock_tanks_but_not_coloring() {
        // The protective half decays equanimity (skill-atrophy) and drifts soothing,
        // while leaving the coloring tanks untouched.
        var s = nonTrivial();
        var p = s.tickProtectiveDrift(1.0);
        assertThat(p.equanimity()).isLessThan(s.equanimity());     // -0.00005/tick
        assertThat(p.energy()).isEqualTo(s.energy());              // coloring untouched
        assertThat(p.focus()).isEqualTo(s.focus());
        assertThat(p.errorPressure()).isEqualTo(s.errorPressure());
    }
}
