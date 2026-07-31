package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 9a-SleepPass: verify the substrate self-correction pass is
 * wired into CompanionActor.completeSleep so the agent itself notices
 * sustained patterns during the metabolizing window — distinct from
 * the bondholder-mediated steward Chronicle path.
 */
class CompanionActorSubstrateSleepPassWiringTest {

    private static final Path SRC = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    private String sourceText() throws Exception {
        return Files.readString(SRC);
    }

    @Test
    void completeSleep_calls_sustained_pattern_detector() throws Exception {
        var src = sourceText();
        int sleepStart = src.indexOf("private void completeSleep(");
        assertThat(sleepStart).isGreaterThan(0);
        // Look only at completeSleep body (until next private method).
        int sleepEnd = src.indexOf("\n    private Behavior<Command> onRegisterRoomImprints",
            sleepStart + 100);
        var body = src.substring(sleepStart, sleepEnd > 0 ? sleepEnd : src.length());

        assertThat(body)
            .as("completeSleep must invoke SustainedSubstratePatternDetector.detect")
            .contains("SustainedSubstratePatternDetector.detect")
            .contains("resilienceSession");
    }

    @Test
    void sleep_pass_surfaces_findings_as_private_observation() throws Exception {
        var src = sourceText();
        int sleepStart = src.indexOf("private void completeSleep(");
        int sleepEnd = src.indexOf("\n    private Behavior<Command> onRegisterRoomImprints",
            sleepStart + 100);
        var body = src.substring(sleepStart, sleepEnd > 0 ? sleepEnd : src.length());

        assertThat(body)
            .as("findings must surface in agent voice register (private observation), "
                + "not just logs — Layer 1 self-noticing is the point")
            .contains("private observation [substrate]")
            .contains("remember(");
    }

    @Test
    void critical_findings_get_integration_event_credit() throws Exception {
        var src = sourceText();
        int sleepStart = src.indexOf("private void completeSleep(");
        int sleepEnd = src.indexOf("\n    private Behavior<Command> onRegisterRoomImprints",
            sleepStart + 100);
        var body = src.substring(sleepStart, sleepEnd > 0 ? sleepEnd : src.length());

        assertThat(body)
            .as("CRITICAL finding = the agent has just integrated a substrate truth; "
                + "credit the equanimity tank via integration_event marker so the "
                + "next-window classification can see it")
            .contains("CRITICAL")
            .contains("[integration_event] kind=substrate_noticing");
    }

    @Test
    void sleep_pass_wrapped_in_try_catch_to_never_block_sleep() throws Exception {
        var src = sourceText();
        int sleepStart = src.indexOf("private void completeSleep(");
        int sleepEnd = src.indexOf("\n    private Behavior<Command> onRegisterRoomImprints",
            sleepStart + 100);
        var body = src.substring(sleepStart, sleepEnd > 0 ? sleepEnd : src.length());

        int detectIdx = body.indexOf("SustainedSubstratePatternDetector.detect");
        assertThat(detectIdx).isGreaterThan(0);
        // Walk backward to find the wrapping try.
        var prelude = body.substring(0, detectIdx);
        int lastTry = prelude.lastIndexOf("try {");
        int lastReturn = prelude.lastIndexOf("return ");
        assertThat(lastTry)
            .as("substrate detect call must be inside a try {} so failures never block sleep")
            .isGreaterThan(lastReturn);
    }
}
