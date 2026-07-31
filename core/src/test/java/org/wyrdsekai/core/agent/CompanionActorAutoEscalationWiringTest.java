package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group B integration coverage (post-pure-helper wiring): verify that
 * the auto-escalation sleep-cycle hook is wired into
 * {@link CompanionActor#completeSleep} so substrate findings produced
 * by the sleep-pass detector trigger {@code SeekSanctuary} dispatch
 * via {@link org.wyrdsekai.core.agent.interiority.AutoEscalationDecision}.
 *
 * <p>Pure source-text checks — same pattern as {@code
 * CompanionActorSubstrateSleepPassWiringTest}. Tests that the wiring
 * exists, lives in the right place, and has the right safety properties
 * (try/catch, attendant-mode early-return, idempotent dispatch).
 */
class CompanionActorAutoEscalationWiringTest {

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
    void completeSleep_calls_autoEscalateIfWarranted() throws Exception {
        var body = completeSleepBody(sourceText());
        assertThat(body)
            .as("completeSleep must invoke autoEscalateIfWarranted() — Group B "
                + "runtime hook that consumes substrate findings and dispatches "
                + "SeekSanctuary when AutoEscalationDecision warrants it")
            .contains("autoEscalateIfWarranted()");
    }

    @Test
    void auto_escalation_runs_after_substrate_findings_pass() throws Exception {
        var body = completeSleepBody(sourceText());
        int detectIdx = body.indexOf("SustainedSubstratePatternDetector.detect");
        int escalateIdx = body.indexOf("autoEscalateIfWarranted()");
        assertThat(detectIdx).isGreaterThan(0);
        assertThat(escalateIdx).isGreaterThan(detectIdx)
            .as("auto-escalation must run AFTER the substrate findings pass — "
                + "it consumes those findings as decision inputs");
    }

    @Test
    void auto_escalation_runs_before_substrate_persist() throws Exception {
        var body = completeSleepBody(sourceText());
        int escalateIdx = body.indexOf("autoEscalateIfWarranted()");
        int persistIdx = body.indexOf("persistSubstrateTrackers()");
        assertThat(escalateIdx).isGreaterThan(0);
        assertThat(persistIdx).isGreaterThan(escalateIdx)
            .as("auto-escalation must run BEFORE persistSubstrateTrackers so "
                + "any RepairModeTracker / AttendantSessionTracker state changes "
                + "from the escalation get persisted on the same sleep cycle");
    }

    @Test
    void auto_escalation_wrapped_in_try_catch() throws Exception {
        var body = completeSleepBody(sourceText());
        int escalateIdx = body.indexOf("autoEscalateIfWarranted()");
        // Look for try { ... autoEscalateIfWarranted() ... } catch within
        // a small window before/after the call.
        int tryWindowStart = Math.max(0, escalateIdx - 200);
        int tryWindowEnd = Math.min(body.length(), escalateIdx + 200);
        var window = body.substring(tryWindowStart, tryWindowEnd);
        assertThat(window)
            .as("auto-escalation must be wrapped in try/catch so escalation "
                + "failure never blocks sleep completion")
            .contains("try {")
            .contains("catch (Exception");
    }

    @Test
    void auto_escalation_method_defined_and_skips_if_already_attendant() throws Exception {
        var src = sourceText();
        int methodIdx = src.indexOf("private void autoEscalateIfWarranted()");
        assertThat(methodIdx).isGreaterThan(0)
            .as("autoEscalateIfWarranted method must be defined");
        int methodEnd = src.indexOf("\n    private void handleSeekSanctuary",
            methodIdx);
        var methodBody = src.substring(methodIdx, methodEnd > 0 ? methodEnd : src.length());
        assertThat(methodBody)
            .as("must early-return if already in ATTENDANT mode to keep dispatch "
                + "idempotent — re-firing on consecutive sleeps while pattern "
                + "persists must not churn state")
            .contains("RepairMode.ATTENDANT")
            .contains("return");
    }

    @Test
    void auto_escalation_uses_pure_decision_helper() throws Exception {
        var src = sourceText();
        int methodIdx = src.indexOf("private void autoEscalateIfWarranted()");
        int methodEnd = src.indexOf("\n    private void handleSeekSanctuary",
            methodIdx);
        var methodBody = src.substring(methodIdx, methodEnd > 0 ? methodEnd : src.length());
        assertThat(methodBody)
            .as("decision logic must come from AutoEscalationDecision (the "
                + "pure-function helper) — not inlined ad-hoc rules")
            .contains("AutoEscalationDecision")
            .contains(".decide(");
    }

    @Test
    void auto_escalation_dispatches_seek_sanctuary_with_reason() throws Exception {
        var src = sourceText();
        int methodIdx = src.indexOf("private void autoEscalateIfWarranted()");
        int methodEnd = src.indexOf("\n    private void handleSeekSanctuary",
            methodIdx);
        var methodBody = src.substring(methodIdx, methodEnd > 0 ? methodEnd : src.length());
        assertThat(methodBody)
            .as("dispatches synthetic SeekSanctuary with the decision rationale "
                + "as reason so audit trail records WHY the substrate self-escalated")
            .contains("AgentAction.SeekSanctuary")
            .contains("auto-escalation")
            .contains("handleSeekSanctuary(");
    }
}
