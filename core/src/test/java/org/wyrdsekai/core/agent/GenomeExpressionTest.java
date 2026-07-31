package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.GenomeProfile;

import static org.assertj.core.api.Assertions.*;

/**
 * The genome is the thing that makes companions distinct individuals: it is consumed by the
 * live heartbeat ({@link VitalityState#accumulate}, {@link VitalityState#tickColoring}) so two
 * agents with different temperaments diverge from the SAME stimulus — in both their social and
 * their solo-activity pressures. This pins two properties:
 *
 * <ol>
 *   <li><b>Zero regression at NEUTRAL</b> — the default genome reproduces the pre-genome
 *       hand-tuned dynamics exactly (so welfare floors and existing soaks are untouched).</li>
 *   <li><b>Real divergence by archetype</b> — a diplomat's loneliness builds faster than a
 *       scholar's; an explorer's restlessness outpaces a steward's; warmth lingers for a
 *       diplomat, focus wanders sooner for an explorer.</li>
 * </ol>
 */
class GenomeExpressionTest {

    /** Same isolating, idle, conflicted context for every agent — divergence must come from
     *  temperament alone, not from different conditions. */
    private static AccumulationContext isolatedIdleContext() {
        return new AccumulationContext(
            Duration.ofMinutes(10),    // timeSinceLastInteraction (≥5 → loneliness)
            Duration.ofHours(3),       // timeSinceLastGoalDone (≥2h → stagnation)
            Duration.ofHours(3),       // timeSinceLastToolOutput (≥2h → stagnation)
            Duration.ofMinutes(10),    // timeSinceLastInferenceActivity (>5s → restlessness)
            0,                         // consecutiveBondholderInitiatedActions
            false,                     // inEmotionalContext
            false,                     // isWithBondholder
            true,                      // isOnOwnTime
            false,                     // inConflictedRoom
            0,                         // unreadArtifactCount
            false,                     // hostileEnvironment
            0.0,                       // peakDriveActivity (→ restlessness stillness)
            Map.of(),                  // bondholder absence
            Map.of(),                  // obligation debts
            0.0                        // amaeAnticipationDeficit
        );
    }

    // ── 1. Zero regression: NEUTRAL == the legacy no-genome path ───────────────

    @Test void neutralGenomeReproducesLegacyAccumulateExactly() {
        var ctx = isolatedIdleContext();
        var v0 = VitalityState.initial();
        var legacy = v0.accumulate(false, ctx, 600.0);                          // 3-arg path
        var neutral = v0.accumulate(false, ctx, 600.0, GenomeProfile.NEUTRAL);  // genome path
        assertThat(neutral.loneliness()).isEqualTo(legacy.loneliness());
        assertThat(neutral.restlessness()).isEqualTo(legacy.restlessness());
        assertThat(neutral.stagnation()).isEqualTo(legacy.stagnation());
    }

    @Test void neutralGenomeReproducesLegacyTickColoringExactly() {
        var v0 = VitalityState.initial().withRapport(0.6).withFocus(0.6);
        var legacy = v0.tickColoring(1.0);
        var neutral = v0.tickColoring(1.0, GenomeProfile.NEUTRAL);
        assertThat(neutral.rapport()).isEqualTo(legacy.rapport());
        assertThat(neutral.focus()).isEqualTo(legacy.focus());
        assertThat(neutral.alignment()).isEqualTo(legacy.alignment());
    }

    @Test void nullGenomeIsTreatedAsNeutral() {
        var ctx = isolatedIdleContext();
        var v0 = VitalityState.initial();
        assertThat(v0.accumulate(false, ctx, 600.0, null).loneliness())
            .isEqualTo(v0.accumulate(false, ctx, 600.0).loneliness());
    }

    // ── 2. Social divergence: who gets lonely faster ───────────────────────────

    @Test void diplomatGetsLonelierThanScholarFromTheSameIsolation() {
        var ctx = isolatedIdleContext();
        var v0 = VitalityState.initial();
        double diplomat = v0.accumulate(false, ctx, 600.0,
            GenomeProfile.forArchetype("diplomat")).loneliness();
        double scholar = v0.accumulate(false, ctx, 600.0,
            GenomeProfile.forArchetype("scholar")).loneliness();
        // diplomat sensitivity 1.6 vs scholar 0.7 → diplomat lonelier, scholar content alone.
        assertThat(diplomat).isGreaterThan(scholar);
        assertThat(diplomat / scholar).isCloseTo(1.6 / 0.7, withinPercentage(1));
    }

    // ── 3. Activity divergence (NOT just social): restlessness / stagnation ────

    @Test void explorerGetsRestlessFasterThanStewardWhenIdle() {
        var ctx = isolatedIdleContext();
        var v0 = VitalityState.initial();
        double explorer = v0.accumulate(false, ctx, 600.0,
            GenomeProfile.forArchetype("explorer")).restlessness();
        double steward = v0.accumulate(false, ctx, 600.0,
            GenomeProfile.forArchetype("steward")).restlessness();
        // explorer restlessness 1.7 vs steward 0.6 → the explorer needs to move, the steward waits.
        assertThat(explorer).isGreaterThan(steward);
    }

    @Test void artisanFeelsStagnationFasterThanNeutralWhenNothingIsMade() {
        var ctx = isolatedIdleContext();
        var v0 = VitalityState.initial();
        double artisan = v0.accumulate(false, ctx, 600.0,
            GenomeProfile.forArchetype("artisan")).stagnation();
        double neutral = v0.accumulate(false, ctx, 600.0, GenomeProfile.NEUTRAL).stagnation();
        assertThat(artisan).isGreaterThan(neutral); // stagnation sensitivity 1.4
    }

    // ── 4. Decay divergence: warmth lingers / focus wanders ────────────────────

    @Test void diplomatWarmthLingersLongerThanExplorerFocus() {
        var warm = VitalityState.initial().withRapport(0.8);
        double diplomatRapportDrop = warm.rapport()
            - warm.tickColoring(1.0, GenomeProfile.forArchetype("diplomat")).rapport();
        double neutralRapportDrop = warm.rapport()
            - warm.tickColoring(1.0, GenomeProfile.NEUTRAL).rapport();
        // diplomat rapport decay 0.5× → warmth fades slower.
        assertThat(diplomatRapportDrop).isLessThan(neutralRapportDrop);
    }

    @Test void explorerFocusWandersFasterThanNeutral() {
        var sharp = VitalityState.initial().withFocus(0.8);
        double explorerFocusDrop = sharp.focus()
            - sharp.tickColoring(1.0, GenomeProfile.forArchetype("explorer")).focus();
        double neutralFocusDrop = sharp.focus()
            - sharp.tickColoring(1.0, GenomeProfile.NEUTRAL).focus();
        assertThat(explorerFocusDrop).isGreaterThan(neutralFocusDrop); // focus decay 1.3×
    }

    // ── 5. Factory sanity ──────────────────────────────────────────────────────

    @Test void unknownArchetypeFallsBackToDefaults() {
        assertThat(GenomeProfile.forArchetype("nope").sensitivityFor("loneliness")).isEqualTo(1.0);
        assertThat(GenomeProfile.forArchetype(null).sensitivityFor("loneliness")).isEqualTo(1.0);
    }

    @Test void decayFactorIsClampedToASaneBand() {
        // No genome can freeze (0×) or detonate (≫) a tank.
        var g = GenomeProfile.forArchetype("diplomat");
        assertThat(g.decayFactorFor("rapport")).isBetween(0.25, 4.0);
        assertThat(g.decayFactorFor("never-set-tank")).isEqualTo(1.0);
    }

    @Test void birthOverridesAreNonEmptyForArchetypesAndEmptyForDefault() {
        assertThat(GenomeProfile.birthTankOverrides("diplomat")).containsKey("rapport");
        assertThat(GenomeProfile.birthTankOverrides("guardian")).containsKey("confidence");
        assertThat(GenomeProfile.birthTankOverrides(null)).isEmpty();
        assertThat(GenomeProfile.birthTankOverrides("nope")).isEmpty();
    }
}
