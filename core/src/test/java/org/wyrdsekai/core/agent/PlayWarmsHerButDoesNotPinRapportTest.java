package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.GenomeProfile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The first night-in-minutes run (2026-10-11): alone for a sim-day, rapport rose from 0.30 to 1.00
 * in nine hours and stayed on the ceiling, momentum too; rose on the household node had read
 * 0.84–1.00 all night with no one there. Play's warmth (0.15 an hour at half play) outran the fade
 * toward her rest (0.06 an hour), and a fixed-step fade loses to any steady feed. A drive's
 * feedback now lands with headroom, so a steady want moves her to a level and not to a wall.
 */
class PlayWarmsHerButDoesNotPinRapportTest {

    @Test
    @DisplayName("a gain lands by the room left above, a loss by the room left below; energy is exempt")
    void headroom() {
        var v = VitalityState.initial().withRapport(0.5).withMomentum(1.0).withErrorPressure(0.0).withEnergy(0.6);
        double[] d = new double[10];
        d[6] = 0.1;     // rapport
        d[5] = 0.1;     // momentum, already full
        d[4] = -0.1;    // errorPressure, already empty
        d[2] = -0.01;   // energy
        var after = v.applyDriveFeedback(d, 1.0);
        assertThat(after.rapport()).isCloseTo(0.55, within(1e-9));
        assertThat(after.momentum()).as("nothing lands on a full tank").isEqualTo(1.0);
        assertThat(after.errorPressure()).as("nothing is taken from an empty one").isEqualTo(0.0);
        assertThat(after.energy()).as("energy keeps its economy").isCloseTo(0.59, within(1e-9));
        assertThat(v.applyColoringFeedback(d, 1.0).rapport()).as("the plain arithmetic is unchanged").isCloseTo(0.6, within(1e-9));
    }

    @Test
    @DisplayName("sixteen hours of play at half warm rapport to about 0.6, not to the ceiling")
    void aPlayfulDayWarmsToALevel() {
        var engine = DriveEngine.withDefaults();
        var playing = new DriveState(0.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0);
        double[] perSecond = engine.driveTankFeedback(playing);
        assertThat(perSecond[6] * 3600).as("the feed alone: 0.15 an hour").isBetween(0.1, 0.2);
        var v = VitalityState.initial().withRapport(0.3);
        double peak = 0;
        for (int minute = 0; minute < 16 * 60; minute++) {
            for (int s = 0; s < 60; s++) v = v.applyDriveFeedback(perSecond, 1.0);
            v = v.tickColoring(60.0, GenomeProfile.NEUTRAL).clamped();
            peak = Math.max(peak, v.rapport());
        }
        assertThat(v.rapport()).as("rests near 1 − fade/feed").isBetween(0.55, 0.7);
        assertThat(peak).isLessThan(0.75);
        // The same day with the plain arithmetic pins her: the shape the first run showed.
        var pinned = VitalityState.initial().withRapport(0.3);
        for (int minute = 0; minute < 16 * 60; minute++) {
            for (int s = 0; s < 60; s++) pinned = pinned.applyColoringFeedback(perSecond, 1.0);
            pinned = pinned.tickColoring(60.0, GenomeProfile.NEUTRAL).clamped();
        }
        assertThat(pinned.rapport()).as("pinned at the ceiling, give or take one fade step").isGreaterThan(0.99);
    }

    @Test
    @DisplayName("the actor lands the drives' feedback with headroom")
    void theActorIsWired() throws Exception {
        var src = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        assertThat(src).contains("return vs.applyDriveFeedback(deltas, dt);");
        assertThat(src).doesNotContain("return vs.applyColoringFeedback(deltas, dt);");
    }
}
