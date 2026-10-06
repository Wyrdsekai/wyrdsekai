package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A third of their own-time turns were speaking turns that found nothing fresh to say and ended in
 * silence (household node, 2026-10-04). Such a slot is now a turn of her own time to act in, unless
 * she is too tired; inside it, silence is still hers to choose.
 */
class NothingToSayIsNotNothingToDoTest {

    @Test
    void a_silent_seeking_slot_becomes_a_turn_to_act_in_above_the_tired_line() throws Exception {
        var base = Files.exists(Path.of("..", "core")) ? Path.of("..") : Path.of(".");
        var src = Files.readString(base.resolve("core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        int branch = src.indexOf("if (prompt == null && vitality.energy() >= OWN_TIME_ACT_MIN_ENERGY) {");
        assertThat(branch).isPositive();
        int act = src.indexOf("triggerAutonomousInference(buildIdleCuriosityPrompt());", branch);
        int quiet = src.indexOf("nothing fresh to speak about — staying quiet", branch);
        assertThat(act).as("the slot is a turn to act in").isGreaterThan(branch);
        assertThat(quiet).as("too tired: quiet, as before").isGreaterThan(act);
        assertThat(src).contains("private static final double OWN_TIME_ACT_MIN_ENERGY = 0.2;");
    }
}
