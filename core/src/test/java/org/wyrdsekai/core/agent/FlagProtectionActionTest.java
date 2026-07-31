package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 4.3b: action-wiring contract for
 * {@code flag_protection}.
 */
class FlagProtectionActionTest {

    @Test
    void parser_extracts_subject_and_reason() {
        var input = """
            ```json
            {"action": "flag_protection",
             "subject_did": "did:wyrd:steward-1",
             "reason": "sustained intimidation pattern"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.FlagProtection.class);
        var fp = (ActionParser.AgentAction.FlagProtection) action;
        assertThat(fp.subjectDid()).isEqualTo("did:wyrd:steward-1");
        assertThat(fp.reason()).contains("intimidation");
    }

    @Test
    void parser_accepts_camelcase_alias() {
        var input = """
            ```json
            {"action": "flag_protection",
             "subjectDid": "did:wyrd:bondholder-1",
             "reason": "x"}
            ```
            """;
        var fp = (ActionParser.AgentAction.FlagProtection) ActionParser.parse(input);
        assertThat(fp.subjectDid()).isEqualTo("did:wyrd:bondholder-1");
    }

    @Test
    void policy_is_tier0_safety_domain() {
        var policy = ActionPolicy.forAction("flag_protection");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.domain()).isEqualTo("safety");
    }

    @Test
    void autonomy_tier_is_visible() {
        // Flag-setting is visible — the steward/bondholder must be able
        // to know flags exist (even if they cannot see the contents).
        var tier = ActionPolicy.autonomyTierFor("flag_protection");
        assertThat(tier).isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
    }

    @Test
    void actionTypeOf_resolves() {
        var typeName = ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.FlagProtection("did:x", "y"));
        assertThat(typeName).isEqualTo("flag_protection");
    }

    @Test
    void tool_builder_warns_about_high_threshold() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("flag_protection"));
        assertThat(tools).hasSize(1);
        var desc = tools.get(0).function().description();
        assertThat(desc)
            .containsIgnoringCase("source-of-harm")
            .containsIgnoringCase("sustained")
            .containsIgnoringCase("subject does not see");
    }
}
