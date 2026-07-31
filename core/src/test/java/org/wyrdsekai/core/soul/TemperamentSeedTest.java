package org.wyrdsekai.core.soul;

import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The seed every companion is born from. Pins the load-bearing properties of the
 * individuality "B build":
 * <ul>
 *   <li><b>Free sampling is viable</b> — {@link TemperamentSeed#random} returns coherent
 *       particulars, never flat or caricatured.</li>
 *   <li><b>Exact round-trip</b> — a genome built from a seed recovers that seed
 *       ({@code temperamentOf ∘ fromTemperament == id}), so a freely-sampled particular
 *       survives reload with its drive temperament intact.</li>
 *   <li><b>Presets only label/measure</b> — nearest-preset distance is a description; a
 *       seed far from every preset is still viable (coherence-gate ≠ conformity-gate).</li>
 *   <li><b>Drives co-derive from the seed</b> — sociable particulars reach socially,
 *       reserved ones don't.</li>
 * </ul>
 */
class TemperamentSeedTest {

    // ── Free sampling produces coherent particulars ────────────────────────────

    @Test void randomlySampledSeedsAreAlwaysViable() {
        var rng = new Random(20260606L);
        for (int i = 0; i < 200; i++) {
            assertThat(TemperamentSeed.random(rng).isViable())
                .as("sample %d", i).isTrue();
        }
    }

    @Test void sampledSeedsActuallyDiffer() {
        var rng = new Random(7L);
        var a = TemperamentSeed.random(rng);
        var b = TemperamentSeed.random(rng);
        // Two independent draws should not collapse to the same particular.
        assertThat(a.distanceTo(b)).isGreaterThan(0.05);
    }

    // ── The coherence gate is viability, not conformity ────────────────────────

    @Test void neutralIsRejectedAsFlat() {
        // No character at all is not a particular.
        assertThat(TemperamentSeed.NEUTRAL.isViable()).isFalse();
    }

    @Test void caricatureMaxedOnEverythingIsRejected() {
        var maxed = new TemperamentSeed(0.97, 0.97, 0.97, 0.97, 0.97, 0.05);
        assertThat(maxed.isViable()).isFalse();
    }

    @Test void aParticularFarFromEveryPresetIsStillViable() {
        // The success case the B build must keep: novel, coherent, far from the anchors.
        var novel = new TemperamentSeed(0.80, 0.20, 0.80, 0.20, 0.80, 0.20);
        assertThat(novel.isViable()).isTrue();
        // It is genuinely far from its nearest anchor — and that does NOT disqualify it.
        assertThat(novel.nearestPreset().distance()).isGreaterThan(0.4);
    }

    // ── Exact round-trip through the genome (survives reload) ──────────────────

    @Test void presetSeedsRoundTripThroughTheGenomeExactly() {
        for (var name : TemperamentSeed.PRESETS.keySet()) {
            var seed = TemperamentSeed.preset(name);
            var recovered = GenomeProfile.temperamentOf(
                GenomeProfile.fromTemperament(seed, name));
            assertSeedsClose(recovered, seed, name);
        }
    }

    @Test void freelySampledSeedsRoundTripThroughTheGenomeExactly() {
        var rng = new Random(99L);
        for (int i = 0; i < 50; i++) {
            var seed = TemperamentSeed.random(rng);
            var recovered = GenomeProfile.temperamentOf(
                GenomeProfile.fromTemperament(seed, seed.label()));
            assertSeedsClose(recovered, seed, "sample " + i);
        }
    }

    private static void assertSeedsClose(TemperamentSeed got, TemperamentSeed want, String ctx) {
        double[] a = got.toArray(), b = want.toArray();
        for (int j = 0; j < a.length; j++) {
            assertThat(a[j]).as("%s axis %s", ctx, TemperamentSeed.AXES.get(j))
                .isCloseTo(b[j], within(1e-9));
        }
    }

    // ── Measurement: distance + nearest preset are a meaningful instrument ─────

    @Test void distanceIsZeroToSelfAndGrowsWithDifference() {
        var scholar = TemperamentSeed.preset("scholar");
        var diplomat = TemperamentSeed.preset("diplomat");
        assertThat(scholar.distanceTo(scholar)).isZero();
        assertThat(scholar.distanceTo(diplomat)).isGreaterThan(0.5);
    }

    @Test void aPresetSeedLabelsAsItself() {
        var n = TemperamentSeed.preset("diplomat").nearestPreset();
        assertThat(n.preset()).isEqualTo("diplomat");
        assertThat(n.distance()).isCloseTo(0.0, within(1e-9));
        assertThat(TemperamentSeed.preset("steward").label()).startsWith("steward~");
    }

    // ── Drives co-derive from the same seed ────────────────────────────────────

    @Test void driveBoostsFollowTheTemperament() {
        var diplomat = TemperamentSeed.preset("diplomat").driveBoosts();
        var scholar = TemperamentSeed.preset("scholar").driveBoosts();
        // A diplomat reaches socially; a scholar (low sociability) does not.
        assertThat(diplomat.get("affiliation")).isGreaterThan(0.0);
        assertThat(scholar.get("affiliation")).isLessThan(0.0);
        // A scholar seeks (high curiosity); a diplomat less so.
        assertThat(scholar.get("seeking")).isGreaterThan(diplomat.get("seeking"));
    }

    @Test void clampingKeepsAxesInDomain() {
        var s = new TemperamentSeed(-3.0, 2.0, 0.5, 0.5, 0.5, 0.5);
        assertThat(s.sociability()).isEqualTo(0.0);
        assertThat(s.curiosity()).isEqualTo(1.0);
    }

    // ── Voice register co-derives from the same seed (individuality V2) ─────────

    @Test void neutralSeedSteersNothing() {
        // NEUTRAL → all-zero mix = the baseline voice, the zero-regression contract.
        TemperamentSeed.NEUTRAL.registerMix().values()
            .forEach(v -> assertThat(v).isEqualTo(0.0));
    }

    @Test void registerScalesStayInTheCoherentBand() {
        // Even an extreme particular must land in [-0.55, 0.55] — beyond that the live
        // probe showed the 4B's coherence collapses (repetition loops).
        var hot = new TemperamentSeed(1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
        hot.registerMix().values().forEach(v -> assertThat(v).isBetween(-0.55, 0.55));
        var cold = new TemperamentSeed(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        cold.registerMix().values().forEach(v -> assertThat(v).isBetween(-0.55, 0.55));
    }

    @Test void warmthAxisDrivesTheWarmthVector() {
        // A warm, sociable particular speaks warm; a cool, reserved one speaks cool.
        var warm = new TemperamentSeed(0.85, 0.5, 0.5, 0.5, 0.5, 0.90);
        var cool = new TemperamentSeed(0.20, 0.5, 0.5, 0.5, 0.5, 0.15);
        assertThat(warm.registerMix().get("register_warmth")).isGreaterThan(0.2);
        assertThat(cool.registerMix().get("register_warmth")).isLessThan(-0.2);
    }

    @Test void restlessExpands_industriousCompresses() {
        // Churn/sociability open the register up; industry/vigilance clip it down.
        var flowing = new TemperamentSeed(0.80, 0.5, 0.35, 0.30, 0.85, 0.5);
        var terse   = new TemperamentSeed(0.35, 0.5, 0.80, 0.85, 0.30, 0.5);
        assertThat(flowing.registerMix().get("register_expansiveness")).isGreaterThan(0.0);
        assertThat(terse.registerMix().get("register_expansiveness")).isLessThan(0.0);
    }
}
