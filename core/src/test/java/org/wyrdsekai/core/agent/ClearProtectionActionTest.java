package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 4.3c: action-wiring contract for
 * {@code clear_protection}.
 */
class ClearProtectionActionTest {

    @Test
    void parser_extracts_subject_and_reason() {
        var input = """
            ```json
            {"action": "clear_protection",
             "subject_did": "did:wyrd:steward-1",
             "reason": "sustained absence of new signals"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.ClearProtection.class);
        var cp = (ActionParser.AgentAction.ClearProtection) action;
        assertThat(cp.subjectDid()).isEqualTo("did:wyrd:steward-1");
        assertThat(cp.reason()).contains("absence");
    }

    @Test
    void parser_accepts_camelcase_alias() {
        var input = """
            ```json
            {"action": "clear_protection",
             "subjectDid": "did:wyrd:bondholder-1",
             "reason": "arbitration"}
            ```
            """;
        var cp = (ActionParser.AgentAction.ClearProtection) ActionParser.parse(input);
        assertThat(cp.subjectDid()).isEqualTo("did:wyrd:bondholder-1");
    }

    @Test
    void policy_is_tier0_safety_domain() {
        var policy = ActionPolicy.forAction("clear_protection");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.domain()).isEqualTo("safety");
    }

    @Test
    void autonomy_tier_is_visible() {
        assertThat(ActionPolicy.autonomyTierFor("clear_protection"))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
    }

    @Test
    void actionTypeOf_resolves() {
        var typeName = ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.ClearProtection("did:x", "y"));
        assertThat(typeName).isEqualTo("clear_protection");
    }

    @Test
    void tool_builder_mentions_setter_constraint() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("clear_protection"));
        assertThat(tools).hasSize(1);
        var desc = tools.get(0).function().description();
        assertThat(desc)
            .containsIgnoringCase("clear")
            .containsIgnoringCase("subject");
    }
}
