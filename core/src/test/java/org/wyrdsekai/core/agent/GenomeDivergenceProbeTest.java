package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.TemperamentSeed;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same-stimulus divergence probe (individuality arc Phase E), made deterministic
 * and GPU-free. It is the instrument the n=1 group soaks lacked: born particulars are
 * subjected to ONE identical world condition, and we measure whether they react
 * differently — and whether that difference is <i>meaningful</i> (closer in temperament
 * ⇒ closer in response). This operationalizes "distinct individual" without claiming
 * inner experience: the felt-state response is a pure function of the genome.
 */
class GenomeDivergenceProbeTest {

    /** One identical isolating, idle context applied to every particular. */
    private static AccumulationContext sameStimulusForEveryone() {
        return new AccumulationContext(
            Duration.ofMinutes(10),    // timeSinceLastInteraction (≥5 → loneliness)
            Duration.ofHours(3),       // timeSinceLastGoalDone (≥2h → stagnation)
            Duration.ofHours(3),       // timeSinceLastToolOutput
            Duration.ofMinutes(10),    // timeSinceLastInferenceActivity (→ restlessness)
            0, false, false, true, false, 0, false, 0.0,
            Map.of(), Map.of(), 0.0);
    }

    /** A particular's felt-state response to the shared stimulus: the tanks three
     *  different axes drive (loneliness←sociability, restlessness←restlessness,
     *  stagnation←curiosity), so the response vector reflects the whole temperament. */
    private static double[] respond(TemperamentSeed seed) {
        var genome = GenomeProfile.fromTemperament(seed, seed.label());
        var after = VitalityState.initial()
            // Six hours of the same stimulus, not ten minutes. The tanks moved from
            // linear rates to exponential approaches toward per-temperament set points
            // (2026-08-19), so a single tick now moves everyone a little and divergence
            // shows over the window the curve actually lives on. The property under test
            // is unchanged: identical stimulus, different particulars, different response.
            .accumulate(false, sameStimulusForEveryone(), 6 * 3600.0, genome);
        return new double[]{ after.loneliness(), after.restlessness(), after.stagnation() };
    }

    private static double dist(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) { double d = a[i] - b[i]; s += d * d; }
        return Math.sqrt(s);
    }

    // ── 1. Born particulars react differently to the SAME stimulus ─────────────

    @Test void freelyBornParticularsDivergeUnderOneIdenticalStimulus() {
        var rng = new Random(20260606L);
        var responses = new ArrayList<double[]>();
        for (int i = 0; i < 8; i++) responses.add(respond(TemperamentSeed.random(rng)));

        // The whole felt-state response separates the particulars: the widest pair reacts
        // visibly differently to the identical isolation — that separation IS the individuality.
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE, maxPair = 0;
        for (var r : responses) { lo = Math.min(lo, r[0]); hi = Math.max(hi, r[0]); }
        for (int i = 0; i < responses.size(); i++) {
            for (int j = i + 1; j < responses.size(); j++) {
                double d = dist(responses.get(i), responses.get(j));
                maxPair = Math.max(maxPair, d);
                // No two of them respond identically across the three tanks.
                assertThat(d).as("particulars %d vs %d", i, j).isGreaterThan(0.0);
            }
        }
        assertThat(maxPair).as("widest response separation").isGreaterThan(0.15);
        // And even one tank in isolation (loneliness from sociability) spreads measurably:
        // some are content alone, some ache.
        assertThat(hi - lo).as("loneliness spread across particulars").isGreaterThan(0.10);
    }

    // ── 2. The divergence is MEANINGFUL: temperament distance tracks response ──

    @Test void closerInTemperamentMeansCloserInResponse() {
        var a = new TemperamentSeed(0.30, 0.30, 0.50, 0.50, 0.30, 0.50);
        var near = new TemperamentSeed(0.34, 0.34, 0.50, 0.50, 0.34, 0.50); // a near twin
        var far  = new TemperamentSeed(0.85, 0.85, 0.50, 0.50, 0.85, 0.50); // its opposite

        // Sanity: the geometry is what we claim.
        assertThat(a.distanceTo(near)).isLessThan(a.distanceTo(far));

        // The response geometry follows: the twin reacts more like A than the opposite does.
        double rNear = dist(respond(a), respond(near));
        double rFar = dist(respond(a), respond(far));
        assertThat(rNear).as("twin response distance").isLessThan(rFar);
    }

    // ── 3. Zero regression: a neutral genome reacts like the legacy path ───────

    @Test void neutralGenomeMatchesTheLegacyResponse() {
        var ctx = sameStimulusForEveryone();
        var legacy = VitalityState.initial().accumulate(false, ctx, 600.0);
        var neutral = VitalityState.initial()
            .accumulate(false, ctx, 600.0, GenomeProfile.NEUTRAL);
        assertThat(neutral.loneliness()).isEqualTo(legacy.loneliness());
        assertThat(neutral.restlessness()).isEqualTo(legacy.restlessness());
        assertThat(neutral.stagnation()).isEqualTo(legacy.stagnation());
    }
}
