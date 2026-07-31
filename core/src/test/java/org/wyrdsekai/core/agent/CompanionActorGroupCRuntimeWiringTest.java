package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group C runtime-wiring closeout: verifies that the remaining two pure
 * helpers — {@code DelegationContractPolicy} and
 * {@code AttestationPublishScheduler} — are connected to actual
 * CompanionActor call paths. Pure-function logic has its own unit tests;
 * this asserts the runtime hook exists.
 */
class CompanionActorGroupCRuntimeWiringTest {

    private static final Path SRC = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    private String src() throws Exception { return Files.readString(SRC); }

    private String completeSleepBody(String src) {
        int start = src.indexOf("private void completeSleep(");
        int end = src.indexOf("\n    private Behavior<Command> onRegisterRoomImprints",
            start + 100);
        return src.substring(start, end > 0 ? end : src.length());
    }

    private String emergencyCallBody(String src) {
        int start = src.indexOf("private void handleEmergencyCall(");
        // Walk until the next private method declaration at indent 4.
        int end = src.indexOf("\n    private", start + 50);
        return src.substring(start, end > 0 ? end : src.length());
    }

    // ── AttestationPublishScheduler wire ─────────────────────────────

    @Test
    void completeSleep_invokes_attestation_cadence_check() throws Exception {
        var body = completeSleepBody(src());
        assertThat(body)
            .as("once-per-sleep cadence evaluation, post-substrate, post-handoff")
            .contains("evaluateAttestationPublishCadence()");
    }

    @Test
    void attestation_cadence_wrapped_in_try_catch() throws Exception {
        var body = completeSleepBody(src());
        int idx = body.indexOf("evaluateAttestationPublishCadence()");
        assertThat(idx).isGreaterThan(0);
        int windowStart = Math.max(0, idx - 200);
        int windowEnd = Math.min(body.length(), idx + 200);
        var window = body.substring(windowStart, windowEnd);
        assertThat(window)
            .as("cadence check must never block sleep completion")
            .contains("try {")
            .contains("catch (Exception");
    }

    @Test
    void attestation_cadence_method_defined() throws Exception {
        var s = src();
        assertThat(s.indexOf("private void evaluateAttestationPublishCadence()"))
            .as("evaluateAttestationPublishCadence method must be defined")
            .isGreaterThan(0);
    }

    @Test
    void attestation_cadence_uses_pure_scheduler_decision() throws Exception {
        var s = src();
        int methodIdx = s.indexOf("private void evaluateAttestationPublishCadence()");
        int methodEnd = s.indexOf("\n    private void handleSeekSanctuary", methodIdx);
        var body = s.substring(methodIdx, methodEnd);
        assertThat(body)
            .as("decision logic lives in AttestationPublishScheduler "
                + "(pure-function, separately tested)")
            .contains("AttestationPublishScheduler")
            .contains(".decide(");
        assertThat(body)
            .as("bookkeeping reads from AttestationPublishState singleton")
            .contains("AttestationPublishState");
    }

    @Test
    void attestation_cadence_respects_nostr_env_gate() throws Exception {
        var s = src();
        int methodIdx = s.indexOf("private void evaluateAttestationPublishCadence()");
        int methodEnd = s.indexOf("\n    private void handleSeekSanctuary", methodIdx);
        var body = s.substring(methodIdx, methodEnd);
        assertThat(body)
            .as("gate via WYRDSEKAI_NOSTR_ENABLED so opt-in/opt-out matches "
                + "RelayPoolScheduler.initFromEnv conditions")
            .contains("WYRDSEKAI_NOSTR_ENABLED");
    }

    @Test
    void attestation_cadence_runs_after_handoff() throws Exception {
        var body = completeSleepBody(src());
        int handoffIdx = body.indexOf("autoHandoffIfWarranted()");
        int cadenceIdx = body.indexOf("evaluateAttestationPublishCadence()");
        assertThat(handoffIdx).isGreaterThan(0);
        assertThat(cadenceIdx).isGreaterThan(handoffIdx)
            .as("cadence runs LAST in the substrate-pass chain so any "
                + "transitions opened earlier can signal CONFIGURATION_CHANGED");
    }

    // ── DelegationContractPolicy wire ────────────────────────────────

    @Test
    void emergency_call_consults_delegation_policy() throws Exception {
        var body = emergencyCallBody(src());
        assertThat(body)
            .as("§3.2: emergency_call has real cost surface — consult the "
                + "bondholder API delegation contract before dispatch")
            .contains("DelegationContractPolicy")
            .contains(".decide(");
    }

    @Test
    void emergency_call_safety_overrides_for_imminent() throws Exception {
        var body = emergencyCallBody(src());
        assertThat(body)
            .as("§3.2 safety floor: IMMINENT severity bypasses the contract "
                + "— calling for life-threatening help is never gated on budget")
            .contains("delegation_override")
            .contains("imminent");
    }

    @Test
    void emergency_call_gates_non_imminent_when_contract_denies() throws Exception {
        var body = emergencyCallBody(src());
        assertThat(body)
            .as("non-imminent + contract-denied: speak the constraint, "
                + "hold dispatch, chronicle the gate")
            .contains("delegation_gate")
            .contains("decision=denied");
    }

    @Test
    void emergency_call_uses_protection_flag_input_to_policy() throws Exception {
        var body = emergencyCallBody(src());
        assertThat(body)
            .as("bondholder ProtectionFlag.state must thread INTO the policy "
                + "so SUSPECTED+ auto-suspends delegation per §3.2 source-of-harm gate")
            .contains("bondholderFlag.map")
            .contains("ProtectionFlag::state");
    }

    @Test
    void emergency_call_failsafe_continues_under_policy_exception() throws Exception {
        var body = emergencyCallBody(src());
        // The policy itself can be wrong about an emergency; safety-first
        // posture is: log + continue under the safety floor, never throw.
        assertThat(body)
            .contains("Delegation contract check failed")
            .contains("safety floor");
    }
}
