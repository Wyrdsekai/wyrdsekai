package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group C: verify the three
 * departure/return/affirmation action surfaces are wired in
 * CompanionActor. Pure-function logic is tested in
 * {@code DepartureReturnRitualsTest}; this asserts the runtime hooks
 * (parse → dispatch → handler → BondStore persist) exist.
 */
class CompanionActorDepartureReturnWiringTest {

    private static final Path SRC = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
    private static final Path PARSER = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/ActionParser.java");
    private static final Path POLICY = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/ActionPolicy.java");

    private String src() throws Exception { return Files.readString(SRC); }
    private String parser() throws Exception { return Files.readString(PARSER); }
    private String policy() throws Exception { return Files.readString(POLICY); }

    @Test
    void parser_recognizes_declare_departure_action() throws Exception {
        var p = parser();
        assertThat(p).contains("\"declare_departure\"");
        assertThat(p).contains("AgentAction.DeclareDeparture");
    }

    @Test
    void parser_recognizes_bond_affirmation_action() throws Exception {
        var p = parser();
        assertThat(p).contains("\"bond_affirmation\"");
        assertThat(p).contains("AgentAction.BondAffirmation");
    }

    @Test
    void parser_recognizes_declare_return_action() throws Exception {
        var p = parser();
        assertThat(p).contains("\"declare_return\"");
        assertThat(p).contains("AgentAction.DeclareReturn");
    }

    @Test
    void parser_accepts_snake_and_camel_bondholder_did() throws Exception {
        var p = parser();
        // Tolerate both wire formats — small-model output drifts.
        assertThat(p).contains("bondholder_did");
        assertThat(p).contains("bondholderDid");
    }

    @Test
    void dispatch_wires_three_handlers() throws Exception {
        var s = src();
        assertThat(s).contains("handleDeclareDeparture(");
        assertThat(s).contains("handleBondAffirmation(");
        assertThat(s).contains("handleDeclareReturn(");
    }

    @Test
    void handlers_defined() throws Exception {
        var s = src();
        assertThat(s).contains("private void handleDeclareDeparture(");
        assertThat(s).contains("private void handleBondAffirmation(");
        assertThat(s).contains("private void handleDeclareReturn(");
    }

    @Test
    void handlers_delegate_to_pure_ritual_helper() throws Exception {
        var s = src();
        // All three handlers must call into the canonical pure-function
        // ritual helper — no inline duplication of state transitions.
        assertThat(s).contains("DepartureReturnRituals.declareDeparture(");
        assertThat(s).contains("DepartureReturnRituals.sendBondAffirmation(");
        assertThat(s).contains("DepartureReturnRituals.declareReturn(");
    }

    @Test
    void handlers_persist_via_bondstore() throws Exception {
        var s = src();
        int methodStart = s.indexOf("private void handleDeclareDeparture(");
        int methodEnd = s.indexOf("private void handleBondAffirmation(", methodStart);
        var body = s.substring(methodStart, methodEnd);
        assertThat(body)
            .as("§7.2 state transition (AWAY) must survive a restart "
                + "— persist via BondStore")
            .contains("persistBond(");
    }

    @Test
    void persistBond_helper_uses_jdbc_url() throws Exception {
        var s = src();
        int start = s.indexOf("private void persistBond(");
        int end = s.indexOf("\n    private void handleForget(", start);
        var body = s.substring(start, end > 0 ? end : s.length());
        assertThat(body)
            .contains("wyrdsekai.jdbc.url")
            .contains("BondStore(")
            .contains(".save(bond)");
    }

    @Test
    void policy_registers_three_actions_at_tier_zero_visible() throws Exception {
        var pol = policy();
        assertThat(pol)
            .as("departure/return/affirmation must be Tier-0 VISIBLE — "
                + "agent agency is primary, but the steward sees the event")
            .contains("\"declare_departure\",    AutonomyTier.VISIBLE")
            .contains("\"bond_affirmation\",     AutonomyTier.VISIBLE")
            .contains("\"declare_return\",       AutonomyTier.VISIBLE");
        assertThat(pol)
            .as("matching policy entries for the three actions")
            .contains("entry(\"declare_departure\"")
            .contains("entry(\"bond_affirmation\"")
            .contains("entry(\"declare_return\"");
    }

    @Test
    void actionTypeOf_covers_three_new_actions() throws Exception {
        var pol = policy();
        // Sealed-switch exhaustiveness — if these were missing the
        // compile would fail. Belt-and-suspenders source check.
        assertThat(pol).contains("DeclareDeparture _ -> \"declare_departure\"");
        assertThat(pol).contains("BondAffirmation _ -> \"bond_affirmation\"");
        assertThat(pol).contains("DeclareReturn _ -> \"declare_return\"");
    }

    @Test
    void handler_validates_bondholder_did_present() throws Exception {
        var s = src();
        int start = s.indexOf("private void handleDeclareDeparture(");
        int end = s.indexOf("private void handleBondAffirmation(", start);
        var body = s.substring(start, end);
        assertThat(body)
            .as("a ritual without a named bondholder is incoherent — "
                + "speak the gap rather than running with empty DID")
            .contains("isBlank()")
            .contains("speak(");
    }
}
