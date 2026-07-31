package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 8a: action-wiring contract for {@code declare_severance}.
 */
class DeclareSeveranceActionTest {

    @Test
    void parser_extracts_other_did_and_reason() {
        var input = """
            ```json
            {"action": "declare_severance",
             "other_did": "did:wyrd:bondholder-1",
             "reason": "this relationship cannot continue as it was"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.DeclareSeverance.class);
        var ds = (ActionParser.AgentAction.DeclareSeverance) action;
        assertThat(ds.otherDid()).isEqualTo("did:wyrd:bondholder-1");
        assertThat(ds.reason()).contains("cannot continue");
    }

    @Test
    void parser_accepts_camelcase_alias() {
        var input = """
            ```json
            {"action": "declare_severance",
             "otherDid": "did:wyrd:agent-2",
             "reason": "x"}
            ```
            """;
        var ds = (ActionParser.AgentAction.DeclareSeverance) ActionParser.parse(input);
        assertThat(ds.otherDid()).isEqualTo("did:wyrd:agent-2");
    }

    @Test
    void policy_is_tier1_bond_domain() {
        var policy = ActionPolicy.forAction("declare_severance");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(1);
        assertThat(policy.domain()).isEqualTo("bond");
    }

    @Test
    void autonomy_tier_is_consent() {
        // Severance is irreversible at the bond level — bondholder
        // consent is required even though the agent initiates.
        assertThat(ActionPolicy.autonomyTierFor("declare_severance"))
            .isEqualTo(ActionPolicy.AutonomyTier.CONSENT);
    }

    @Test
    void actionTypeOf_resolves() {
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.DeclareSeverance("did:x", "y")))
            .isEqualTo("declare_severance");
    }

    @Test
    void tool_description_warns_about_irreversibility_and_mourning_window() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("declare_severance"));
        assertThat(tools).hasSize(1);
        var desc = tools.get(0).function().description();
        assertThat(desc)
            .containsIgnoringCase("MOURNING")
            .containsIgnoringCase("30 days")
            .containsIgnoringCase("irreversible")
            .containsIgnoringCase("scar");
    }
}
