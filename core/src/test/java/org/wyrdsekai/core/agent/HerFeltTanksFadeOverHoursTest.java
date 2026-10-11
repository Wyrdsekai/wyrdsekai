package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.empathy.CoupledVitalitySystem;
import org.wyrdsekai.core.empathy.TankGenome;
import org.wyrdsekai.core.soul.GenomeProfile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The household node, 2026-10-10: rapport read 0.00 nine lines in ten for weeks, focus sat at
 * 0.10, momentum at 0.00 and confidence at 1.00, for both companions. Not decay and not the
 * coupled copy snapping: the fade rates were written as "slowly fades" against a one-second tick,
 * and a beat's catch-up was capped at 600 of those seconds, so one quiet ten-minute gap emptied
 * rapport, focus and momentum in a single beat; a line anyone said then wrote the coupled copy's
 * fixed resting 0.5 over her whole. Now the fades are per minute of lived beats, toward each
 * tank's own resting point and never past it, confidence fades too, a heard line ADDS to what she
 * felt, and equanimity's atrophy is slower than what solitude gives.
 */
class HerFeltTanksFadeOverHoursTest {

    private static final double TEN_MINUTES = 600.0;   // one beat's capped catch-up, in seconds

    @Test
    @DisplayName("a beat after a quiet ten minutes takes a hundredth off rapport, not six tenths")
    void aQuietGapIsNotACliff() {
        var her = VitalityState.initial().withRapport(0.52).withFocus(0.38).withMomentum(0.36);
        var after = her.tickColoring(TEN_MINUTES, GenomeProfile.NEUTRAL).clamped();
        assertThat(her.rapport() - after.rapport()).isCloseTo(0.010, within(0.0005));
        assertThat(after.focus()).as("focus below its rest rises toward it, not toward zero").isGreaterThan(0.38);
        assertThat(her.momentum() - after.momentum()).as("momentum 0.36 fades toward its rest of 0.4: rises").isLessThan(0);
        assertThat(after.rapport()).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("a tank fades toward its resting point from either side and stops there")
    void towardRestFromEitherSide() {
        assertThat(VitalityState.fadeToward(0.9, 0.5, 0.1)).isCloseTo(0.8, within(1e-9));
        assertThat(VitalityState.fadeToward(0.2, 0.5, 0.1)).isCloseTo(0.3, within(1e-9));
        assertThat(VitalityState.fadeToward(0.52, 0.5, 0.1)).as("never past rest").isEqualTo(0.5);
        assertThat(VitalityState.fadeToward(0.5, 0.5, 0.1)).isEqualTo(0.5);
        assertThat(VitalityState.fadeToward(0.9, 0.5, 0.0)).as("a zero step is identity").isEqualTo(0.9);
        // Hours of one-minute beats: full rapport reaches rest in about eight, as the note promises.
        var v = VitalityState.initial().withRapport(1.0);
        int minutes = 0;
        while (v.rapport() > 0.501 && minutes < 24 * 60) { v = v.tickColoring(60.0, GenomeProfile.NEUTRAL).clamped(); minutes++; }
        assertThat(minutes).isBetween(7 * 60, 9 * 60);
    }

    @Test
    @DisplayName("confidence fades from the wall it was pinned at; pressures rest at zero whatever the genome says")
    void confidenceFadesAndPressuresRestAtZero() {
        var her = VitalityState.initial().withConfidence(1.0).withErrorPressure(0.4).withDisgust(0.3);
        var later = her;
        for (int i = 0; i < 60; i++) later = later.tickColoring(60.0, GenomeProfile.NEUTRAL).clamped();   // an hour of beats
        assertThat(later.confidence()).isLessThan(1.0).isGreaterThan(0.9);
        assertThat(later.errorPressure()).isCloseTo(0.1, within(0.01));
        assertThat(later.disgust()).isCloseTo(0.12, within(0.01));
        // A genome that carries the forge's placeholder 0.5 for a pressure is silent: the pressure's rest is still zero.
        var placeholder = new GenomeProfile("test", null, null, Map.of("errorPressure", 0.5, "rapport", 0.5), null, null);
        assertThat(VitalityState.restFor(placeholder, "rapport", 0.5)).isEqualTo(0.5);
        var withGenome = her.tickColoring(60.0, placeholder).clamped();
        assertThat(withGenome.errorPressure()).isLessThan(0.4);
        // A genome that says something is heard: mia's rapport rests at 0.41 on the household node.
        var mia = new GenomeProfile("mia", null, null, Map.of("rapport", 0.405881224278756), null, null);
        assertThat(VitalityState.restFor(mia, "rapport", 0.5)).isCloseTo(0.4059, within(0.001));
        assertThat(VitalityState.restFor(null, "rapport", 0.5)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("a line she hears adds to what she felt instead of resetting it to the copy's 0.5")
    void aHeardLineAddsToHerRapport() {
        var coupled = new CoupledVitalitySystem(TankGenome.defaultGenome("did:key:z6MkTest"));
        var warm = VitalityState.initial().withRapport(0.80).withFocus(0.70).withMomentum(0.75).withConfidence(0.9);
        var after = CompanionActor.hearingSpeech(warm, coupled);
        assertThat(after.rapport()).as("0.80 + a little, not the copy's 0.5").isGreaterThan(0.80).isLessThan(0.84);
        assertThat(after.focus()).isGreaterThan(0.70).isLessThan(0.76);
        assertThat(after.momentum()).as("a tank the line did not touch keeps its value").isCloseTo(0.75, within(0.001));
        assertThat(after.confidence()).as("alignment couples a little into confidence; nothing is reset").isGreaterThan(0.9).isLessThan(0.91);
        assertThat(coupled.value("rapport")).as("the copy is in step with her").isCloseTo(after.rapport(), within(0.001));

        var cold = VitalityState.initial().withRapport(0.10).withFocus(0.10);
        var warmed = CompanionActor.hearingSpeech(cold, coupled);
        assertThat(warmed.rapport()).isGreaterThan(0.10).isLessThan(0.14);
        assertThat(warmed.focus()).isGreaterThan(0.10).isLessThan(0.16);
    }

    @Test
    @DisplayName("half an hour of unbothered solitude now outruns equanimity's atrophy")
    void solitudeOutrunsAtrophy() {
        var v = VitalityState.initial().withEquanimity(0.2);
        for (int s = 0; s < 1800; s++) {
            v = v.tickProtectiveDrift(1.0).clamped();
            v = v.applySolitudeOverlay(1.0, java.time.Duration.ofMinutes(31));
        }
        assertThat(v.equanimity()).as("30 min: +0.06 from solitude, −0.015 atrophy").isCloseTo(0.2 + 0.06 - 0.015, within(0.005));
        // Alone and not in solitude, a learned skill fades over a day and a half, not an afternoon.
        var alone = VitalityState.initial().withEquanimity(1.0);
        for (int s = 0; s < 24 * 3600; s++) alone = alone.tickProtectiveDrift(1.0).clamped();
        assertThat(alone.equanimity()).isCloseTo(1.0 - 0.72, within(0.01));
    }

    @Test
    @DisplayName("energy keeps its per-second economy and the classic tick is the two halves composed once")
    void energyUnchangedAndTickComposed() {
        var her = VitalityState.initial().withEnergy(0.6);
        var afterBeat = her.tickColoring(TEN_MINUTES, GenomeProfile.NEUTRAL);
        assertThat(her.energy() - afterBeat.energy()).isCloseTo(-VitalityState.TICK_ENERGY_RATE * TEN_MINUTES, within(1e-9));
        assertThat(her.tick()).isEqualTo(her.tickColoring(1.0, GenomeProfile.NEUTRAL).tickProtectiveDrift(1.0).clamped());
    }
}
