package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 7a-action: parser + policy + tool-descriptor contract for
 * introspect_bondholder_floor — a relationship-scoped read of
 * RelationalFloorView.
 */
class IntrospectBondholderFloorActionTest {

    @Test
    void parser_accepts_with_other_did() {
        var action = ActionParser.parse("""
            ```json
            {"action": "introspect_bondholder_floor", "other_did": "did:bondholder:beta"}
            ```
            """);
        assertThat(action).isInstanceOf(
            ActionParser.AgentAction.IntrospectBondholderFloor.class);
        var ibf = (ActionParser.AgentAction.IntrospectBondholderFloor) action;
        assertThat(ibf.otherDid()).isEqualTo("did:bondholder:beta");
    }

    @Test
    void parser_accepts_camel_case_alias() {
        var action = ActionParser.parse("""
            ```json
            {"action": "introspect_bondholder_floor", "otherDid": "did:bondholder:beta"}
            ```
            """);
        var ibf = (ActionParser.AgentAction.IntrospectBondholderFloor) action;
        assertThat(ibf.otherDid()).isEqualTo("did:bondholder:beta");
    }

    @Test
    void parser_tolerates_missing_other_did_with_empty_string() {
        var action = ActionParser.parse("""
            ```json
            {"action": "introspect_bondholder_floor"}
            ```
            """);
        // Validation lives in the handler (truthful refusal), not the parser.
        var ibf = (ActionParser.AgentAction.IntrospectBondholderFloor) action;
        assertThat(ibf.otherDid()).isEmpty();
    }

    @Test
    void policy_is_tier0_readonly_bond() {
        var policy = ActionPolicy.forAction("introspect_bondholder_floor");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.readOnly()).isTrue();
        assertThat(policy.domain()).isEqualTo("bond");
    }

    @Test
    void autonomy_tier_is_visible() {
        assertThat(ActionPolicy.autonomyTierFor("introspect_bondholder_floor"))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
    }

    @Test
    void actionTypeOf_resolves() {
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.IntrospectBondholderFloor("did:x")))
            .isEqualTo("introspect_bondholder_floor");
    }

    @Test
    void tool_description_names_floor_view_concepts() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("introspect_bondholder_floor"));
        assertThat(tools).hasSize(1);
        var desc = tools.get(0).function().description();
        assertThat(desc)
            .containsIgnoringCase("RelationalFloorView")
            .containsIgnoringCase("acknowledged harms")
            .containsIgnoringCase("Sanctuary")
            .containsIgnoringCase("mourning");
    }
}
