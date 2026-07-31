package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * gritSeed and helpSeekingSeed are DERIVED from the existing six-axis
 * TemperamentSeed (not new knobs). Asserts: neutral → 0.5 (zero regression), the directional
 * derivations, the four coherent corners, and the weak-POSITIVE correlation via shared industry.
 */
class TemperamentSeedVolitionTest {

    private static TemperamentSeed seed(double sociability, double curiosity, double vigilance,
                                        double industry, double restlessness, double warmth) {
        return new TemperamentSeed(sociability, curiosity, vigilance, industry, restlessness, warmth);
    }

    @Test
    void neutralSeedIsNeutral() {
        assertEquals(0.5, TemperamentSeed.NEUTRAL.gritSeed(), 1e-9);
        assertEquals(0.5, TemperamentSeed.NEUTRAL.helpSeekingSeed(), 1e-9);
    }

    @Test
    void gritRisesWithIndustryFallsWithRestlessness() {
        double base = TemperamentSeed.NEUTRAL.gritSeed();
        double industrious = seed(0.5, 0.5, 0.5, /*industry*/0.95, 0.5, 0.5).gritSeed();
        double restless    = seed(0.5, 0.5, 0.5, 0.5, /*restlessness*/0.95, 0.5).gritSeed();
        assertTrue(industrious > base, "industry → more grit");
        assertTrue(restless < base, "restlessness → less grit");
    }

    @Test
    void helpSeekingRisesWithSociability() {
        double base = TemperamentSeed.NEUTRAL.helpSeekingSeed();
        double sociable = seed(/*sociability*/0.95, 0.5, 0.5, 0.5, 0.5, 0.5).helpSeekingSeed();
        double loner    = seed(/*sociability*/0.05, 0.5, 0.5, 0.5, 0.5, 0.5).helpSeekingSeed();
        assertTrue(sociable > base, "sociable → asks readily");
        assertTrue(loner < base, "self-reliant → would rather walk than ask");
    }

    @Test
    void fourCornersAreCoherentAndDistinct() {
        // (industry, sociability) corners — restlessness held mid so industry drives grit cleanly.
        var doggedLoneWolf  = seed(0.05, 0.5, 0.5, 0.95, 0.2, 0.5);  // hi industry, lo social
        var resourceful     = seed(0.95, 0.5, 0.5, 0.95, 0.2, 0.5);  // hi industry, hi social
        var disengagedLoner = seed(0.05, 0.5, 0.5, 0.10, 0.8, 0.5);  // lo industry, lo social
        var quickDelegator  = seed(0.95, 0.5, 0.5, 0.10, 0.8, 0.5);  // lo industry, hi social

        // grit: both high-industry types out-grit both low-industry types.
        assertTrue(doggedLoneWolf.gritSeed() > disengagedLoner.gritSeed());
        assertTrue(resourceful.gritSeed() > quickDelegator.gritSeed());
        // help-seeking: both sociable types out-ask both self-reliant types.
        assertTrue(resourceful.helpSeekingSeed() > doggedLoneWolf.helpSeekingSeed());
        assertTrue(quickDelegator.helpSeekingSeed() > disengagedLoner.helpSeekingSeed());
        // the lone wolf is genuinely high-grit AND low-help — the off-diagonal exists (space not collapsed).
        assertTrue(doggedLoneWolf.gritSeed() > 0.6);
        assertTrue(doggedLoneWolf.helpSeekingSeed() < 0.5);
    }

    @Test
    void weakPositiveCorrelationViaSharedIndustry() {
        // Holding the differentiators fixed, raising the SHARED industry axis lifts BOTH — the source
        // of the weak positive correlation Masumi expected (engaged people both grind and ask).
        var lowIndustry  = seed(0.5, 0.5, 0.5, 0.10, 0.5, 0.5);
        var highIndustry = seed(0.5, 0.5, 0.5, 0.90, 0.5, 0.5);
        assertTrue(highIndustry.gritSeed() > lowIndustry.gritSeed(), "industry lifts grit");
        assertTrue(highIndustry.helpSeekingSeed() > lowIndustry.helpSeekingSeed(),
            "industry also lifts help-seeking → the two co-move (weak positive correlation)");
    }

    @Test
    void axesStayInUnitRangeAtExtremes() {
        var maxGrit = seed(0.5, 0.5, 0.5, 1.0, 0.0, 0.5);
        var minGrit = seed(0.5, 0.5, 0.5, 0.0, 1.0, 0.5);
        assertTrue(maxGrit.gritSeed() <= 1.0 && minGrit.gritSeed() >= 0.0);
        var maxHelp = seed(1.0, 0.5, 0.5, 1.0, 0.5, 0.5);
        var minHelp = seed(0.0, 0.5, 0.5, 0.0, 0.5, 0.5);
        assertTrue(maxHelp.helpSeekingSeed() <= 1.0 && minHelp.helpSeekingSeed() >= 0.0);
    }
}
