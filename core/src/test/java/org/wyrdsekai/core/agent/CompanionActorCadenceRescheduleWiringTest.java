package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group B integration coverage: verify that the CadenceModulator's
 * suggested next-tick delay is captured from each OODA pass and used
 * to gate the next {@code runInteriorityTick} invocation. Effect:
 * vitality ticks fire on fixed cadence (cheap), but full OODA passes
 * only run when the cadence modulator says it's time.
 */
class CompanionActorCadenceRescheduleWiringTest {

    private static final Path SRC = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    private String sourceText() throws Exception {
        return Files.readString(SRC);
    }

    private String runInteriorityTickBody(String src) {
        int methodIdx = src.indexOf("private void runInteriorityTick()");
        // The next private method after runInteriorityTick is
        // enactInteriorityWant.
        int methodEnd = src.indexOf("\n    /**", methodIdx + 100);
        return src.substring(methodIdx, methodEnd > 0 ? methodEnd : src.length());
    }

    @Test
    void cadence_state_fields_declared() throws Exception {
        var src = sourceText();
        assertThat(src)
            .as("lastInteriorityTickAt + lastInteriorityNextDelay state must "
                + "be declared so the gate can compute elapsed-since-last-tick")
            .contains("lastInteriorityTickAt")
            .contains("lastInteriorityNextDelay");
    }

    @Test
    void runInteriorityTick_gates_on_cadence_suggestion() throws Exception {
        var body = runInteriorityTickBody(sourceText());
        assertThat(body)
            .as("runInteriorityTick must compare elapsed time vs the last "
                + "cadence-modulator-suggested delay BEFORE invoking ooda.run() "
                + "— skip if not enough time has passed")
            .contains("lastInteriorityTickAt")
            .contains("lastInteriorityNextDelay")
            .contains("Duration.between");
    }

    @Test
    void runInteriorityTick_captures_next_delay_from_outcome() throws Exception {
        var body = runInteriorityTickBody(sourceText());
        assertThat(body)
            .as("after ooda.run() returns, the outcome's nextTickDelay must "
                + "be captured into lastInteriorityNextDelay so the next "
                + "vitality tick respects the cadence modulator's recommendation")
            .contains("outcome.nextTickDelay()")
            .contains("lastInteriorityNextDelay = outcome.nextTickDelay()");
    }

    @Test
    void cadence_gate_skips_before_pregate_check() throws Exception {
        var body = runInteriorityTickBody(sourceText());
        int cadenceGateIdx = body.indexOf("sinceLast.compareTo(lastInteriorityNextDelay)");
        int pregateIdx = body.indexOf("ooda.shouldRunFullPass(");
        assertThat(cadenceGateIdx).isGreaterThan(0)
            .as("cadence gate must be present");
        assertThat(cadenceGateIdx).isLessThan(pregateIdx)
            .as("cadence gate must run BEFORE the expensive shouldRunFullPass "
                + "check — cheap-skip first");
    }

    @Test
    void initial_next_delay_matches_ooda_base_interval() throws Exception {
        var src = sourceText();
        // The field declaration defaults lastInteriorityNextDelay to 30 min
        // which matches the DriveOODA.run() baseInterval default. This
        // preserves the existing tick cadence on first boot.
        assertThat(src)
            .as("initial lastInteriorityNextDelay must match the OODA baseInterval "
                + "(Duration.ofMinutes(30)) so first-boot behavior is unchanged")
            .contains("lastInteriorityNextDelay")
            .contains("Duration.ofMinutes(30)");
    }
}
