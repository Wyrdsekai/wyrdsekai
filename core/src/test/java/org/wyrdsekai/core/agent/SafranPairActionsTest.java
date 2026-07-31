package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 4.8: action-wiring contract for the Safran-mode
 * acknowledge_harm + make_amends pair.
 */
class SafranPairActionsTest {

    @Test
    void parser_extracts_acknowledge_harm_fields() {
        var input = """
            ```json
            {"action": "acknowledge_harm",
             "other_did": "did:wyrd:bondholder-1",
             "detail": "I was sharp when you needed softness"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.AcknowledgeHarm.class);
        var ah = (ActionParser.AgentAction.AcknowledgeHarm) action;
        assertThat(ah.otherDid()).isEqualTo("did:wyrd:bondholder-1");
        assertThat(ah.detail()).contains("sharp");
    }

    @Test
    void parser_extracts_make_amends_fields() {
        var input = """
            ```json
            {"action": "make_amends",
             "other_did": "did:wyrd:bondholder-1",
             "detail": "letting you set the pace today"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.MakeAmends.class);
        var ma = (ActionParser.AgentAction.MakeAmends) action;
        assertThat(ma.otherDid()).isEqualTo("did:wyrd:bondholder-1");
        assertThat(ma.detail()).contains("pace");
    }

    @Test
    void parser_accepts_camelcase_alias_for_both() {
        var ah = (ActionParser.AgentAction.AcknowledgeHarm) ActionParser.parse("""
            ```json
            {"action": "acknowledge_harm", "otherDid": "did:x", "detail": "y"}
            ```
            """);
        assertThat(ah.otherDid()).isEqualTo("did:x");
        var ma = (ActionParser.AgentAction.MakeAmends) ActionParser.parse("""
            ```json
            {"action": "make_amends", "otherDid": "did:x", "detail": "y"}
            ```
            """);
        assertThat(ma.otherDid()).isEqualTo("did:x");
    }

    @Test
    void acknowledge_harm_policy_is_tier0_repair_domain() {
        var policy = ActionPolicy.forAction("acknowledge_harm");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.domain()).isEqualTo("repair");
    }

    @Test
    void make_amends_policy_is_tier0_repair_domain() {
        var policy = ActionPolicy.forAction("make_amends");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.domain()).isEqualTo("repair");
    }

    @Test
    void both_autonomy_tiers_are_visible() {
        assertThat(ActionPolicy.autonomyTierFor("acknowledge_harm"))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
        assertThat(ActionPolicy.autonomyTierFor("make_amends"))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
    }

    @Test
    void actionTypeOf_resolves_for_both() {
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.AcknowledgeHarm("did:x", "y")))
            .isEqualTo("acknowledge_harm");
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.MakeAmends("did:x", "y")))
            .isEqualTo("make_amends");
    }

    @Test
    void tool_descriptions_name_safran_pair_relationship() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("acknowledge_harm", "make_amends"));
        assertThat(tools).hasSize(2);
        var ackDesc = tools.stream()
            .filter(t -> t.function().name().equals("acknowledge_harm"))
            .findFirst().orElseThrow().function().description();
        var amendsDesc = tools.stream()
            .filter(t -> t.function().name().equals("make_amends"))
            .findFirst().orElseThrow().function().description();
        assertThat(ackDesc)
            .containsIgnoringCase("Safran")
            .containsIgnoringCase("contribution");
        assertThat(amendsDesc)
            .containsIgnoringCase("cosmetic");
    }
}
