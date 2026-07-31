package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group C ( handoff threshold engine):
 * verify the auto-handoff pass is wired into CompanionActor.completeSleep
 * and delegates to HandoffThresholdEngine for the §7.1.1-§7.1.4 mode
 * transitions.
 *
 * <p>Source-text checks — pure-function decision logic has its own
 * unit tests in {@code HandoffThresholdEngineTest}; this only confirms
 * the runtime hook fires once-per-sleep and is wrapped defensively.
 */
class CompanionActorAutoHandoffWiringTest {

    private static final Path SRC = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    private String sourceText() throws Exception {
        return Files.readString(SRC);
    }

    private String completeSleepBody(String src) {
        int sleepStart = src.indexOf("private void completeSleep(");
        int sleepEnd = src.indexOf("\n    private Behavior<Command> onRegisterRoomImprints",
            sleepStart + 100);
        return src.substring(sleepStart, sleepEnd > 0 ? sleepEnd : src.length());
    }

    @Test
    void completeSleep_calls_autoHandoffIfWarranted() throws Exception {
        var body = completeSleepBody(sourceText());
        assertThat(body)
            .as("completeSleep must invoke autoHandoffIfWarranted() — the §7.1 "
                + "general mode-failing handoff pass, distinct from substrate-"
                + "finding-driven auto-escalation")
            .contains("autoHandoffIfWarranted()");
    }

    @Test
    void auto_handoff_wrapped_in_try_catch() throws Exception {
        var body = completeSleepBody(sourceText());
        int idx = body.indexOf("autoHandoffIfWarranted()");
        assertThat(idx).isGreaterThan(0);
        int windowStart = Math.max(0, idx - 200);
        int windowEnd = Math.min(body.length(), idx + 200);
        var window = body.substring(windowStart, windowEnd);
        assertThat(window)
            .as("handoff pass must never block sleep completion on failure")
            .contains("try {")
            .contains("catch (Exception");
    }

    @Test
    void auto_handoff_method_defined() throws Exception {
        var src = sourceText();
        assertThat(src.indexOf("private void autoHandoffIfWarranted()"))
            .as("autoHandoffIfWarranted method must be defined")
            .isGreaterThan(0);
    }

    @Test
    void auto_handoff_returns_early_when_not_in_repair() throws Exception {
        var src = sourceText();
        int methodIdx = src.indexOf("private void autoHandoffIfWarranted()");
        int methodEnd = src.indexOf("\n    private void handleSeekSanctuary",
            methodIdx);
        var methodBody = src.substring(methodIdx,
            methodEnd > 0 ? methodEnd : src.length());
        assertThat(methodBody)
            .as("handoff is a no-op when currentMode == NONE")
            .contains("RepairMode.NONE")
            .contains("return");
    }

    @Test
    void auto_handoff_delegates_to_threshold_engine() throws Exception {
        var src = sourceText();
        int methodIdx = src.indexOf("private void autoHandoffIfWarranted()");
        int methodEnd = src.indexOf("\n    private void handleSeekSanctuary",
            methodIdx);
        var methodBody = src.substring(methodIdx,
            methodEnd > 0 ? methodEnd : src.length());
        assertThat(methodBody)
            .as("§7.1 decision logic lives in HandoffThresholdEngine "
                + "(pure-function, separately tested)")
            .contains("HandoffThresholdEngine")
            .contains(".decide(");
    }

    @Test
    void auto_handoff_invokes_repair_mode_transition() throws Exception {
        var src = sourceText();
        int methodIdx = src.indexOf("private void autoHandoffIfWarranted()");
        int methodEnd = src.indexOf("\n    private void handleSeekSanctuary",
            methodIdx);
        var methodBody = src.substring(methodIdx,
            methodEnd > 0 ? methodEnd : src.length());
        assertThat(methodBody)
            .as("decision result feeds RepairModeTracker.transition for "
                + "actual mode change + Handoff record persistence")
            .contains(".transition(");
    }

    @Test
    void auto_handoff_chronicles_the_transition() throws Exception {
        var src = sourceText();
        int methodIdx = src.indexOf("private void autoHandoffIfWarranted()");
        int methodEnd = src.indexOf("\n    private void handleSeekSanctuary",
            methodIdx);
        var methodBody = src.substring(methodIdx,
            methodEnd > 0 ? methodEnd : src.length());
        assertThat(methodBody)
            .as("§7.1.5: steward + agent legibility — handoff must be "
                + "chronicled (via remember()) so it is recoverable")
            .contains("remember(")
            .contains("chronicleEntry()");
    }

    @Test
    void auto_handoff_runs_after_auto_escalate() throws Exception {
        var body = completeSleepBody(sourceText());
        int escalateIdx = body.indexOf("autoEscalateIfWarranted()");
        int handoffIdx = body.indexOf("autoHandoffIfWarranted()");
        assertThat(escalateIdx).isGreaterThan(0);
        assertThat(handoffIdx).isGreaterThan(escalateIdx)
            .as("auto-escalation runs FIRST (substrate-finding-driven, may "
                + "open ATTENDANT). Auto-handoff runs SECOND (general §7.1 "
                + "pass over the resulting mode). Order matters: a single "
                + "sleep can both open ATTENDANT and then advance from it "
                + "if conditions warrant");
    }
}
