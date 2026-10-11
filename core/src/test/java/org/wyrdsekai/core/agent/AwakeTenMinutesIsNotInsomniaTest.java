package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The household node, 2026-10-11: focus read 0.10 in 182 of 219 rows and alignment 0.10 in 132,
 * for a companion who slept every eight hours. The insomnia rule began ten minutes after waking and
 * rotted both to their floors per second, fifteen times faster than the fade toward her resting
 * points. Confidence read 1.00 in 209 rows: every successful reply added a fixed 0.05. Insomnia now
 * begins after a full waking day and rots per minute; a success recovers only what error pressure
 * took.
 */
class AwakeTenMinutesIsNotInsomniaTest {

    @Test
    @DisplayName("ten minutes awake is nothing; a day awake begins the rot, slowly, then at full strength")
    void aDayAwake() {
        assertThat(CompanionActor.INSOMNIA_AFTER_AWAKE_SECONDS).isEqualTo(16 * 3600.0);
        var v = VitalityState.initial().withFocus(0.5).withAlignment(0.5).withContextBudget(1.0);
        assertThat(CompanionActor.rotForInsomnia(v, 0, 1.0)).isSameAs(v);
        assertThat(CompanionActor.rotForInsomnia(v, -600, 1.0)).isSameAs(v);
        // Ten minutes past the threshold, one tick: a hair.
        var hair = CompanionActor.rotForInsomnia(v, 600, 1.0);
        assertThat(0.5 - hair.focus()).isBetween(0.0, 0.00002);
        // An hour of one-second ticks at full strength (two hours over): focus −0.15, budget −0.3, alignment −0.1.
        var worn = v;
        for (int s = 0; s < 3600; s++) worn = CompanionActor.rotForInsomnia(worn, 7200 + s, 1.0);
        assertThat(worn.focus()).isCloseTo(0.5 - 0.15, within(0.001));
        assertThat(worn.contextBudget()).isCloseTo(1.0 - 0.30, within(0.001));
        assertThat(worn.alignment()).isCloseTo(0.5 - 0.10, within(0.001));
        // The floors hold.
        var floor = v;
        for (int s = 0; s < 24 * 3600; s++) floor = CompanionActor.rotForInsomnia(floor, 7200 + s, 1.0);
        assertThat(floor.focus()).isEqualTo(0.1);
        assertThat(floor.alignment()).isEqualTo(0.1);
        assertThat(floor.contextBudget()).isEqualTo(0.05);
    }

    @Test
    @DisplayName("the actor counts lived seconds awake, a success recovers only what pressure took, the beat lives on the soak clock")
    void theActorIsWired() throws Exception {
        var src = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        assertThat(src).doesNotContain("INSOMNIA_THRESHOLD_TICKS");
        assertThat(src).contains("if (!isSleeping) secondsAwake += deltaTime;");
        assertThat(src).contains("if (!isSleeping && secondsAwake > INSOMNIA_AFTER_AWAKE_SECONDS && cachedManifest != null) {\n"
            + "            vitality = rotForInsomnia(vitality, secondsAwake - INSOMNIA_AFTER_AWAKE_SECONDS, deltaTime);");
        assertThat(src).contains("        ticksSinceLastSleep = 0;\n        secondsAwake = 0;");
        assertThat(src).contains("double confidenceBoost = vitality.errorPressure() * 0.15;");
        assertThat(src).doesNotContain("double confidenceBoost = 0.05 + (vitality.errorPressure() * 0.15);");
        assertThat(src).contains("double elapsed = SoakTimeScale.compress(Duration.between(lastBeatAt, beatAt)).toMillis() / 1000.0;");
        assertThat(src).contains("double maxScalar = BEAT_MAX_SCALAR * SoakTimeScale.factor();");
    }
}
