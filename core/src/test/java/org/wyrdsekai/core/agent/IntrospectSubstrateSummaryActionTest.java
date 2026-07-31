package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 9a-Summary: parser + policy + tool-descriptor contract for the
 * composite self-noticing read action.
 */
class IntrospectSubstrateSummaryActionTest {

    @Test
    void parser_accepts_no_arg_action() {
        var action = ActionParser.parse("""
            ```json
            {"action": "introspect_substrate_summary"}
            ```
            """);
        assertThat(action).isInstanceOf(
            ActionParser.AgentAction.IntrospectSubstrateSummary.class);
    }

    @Test
    void policy_is_tier0_readonly_self() {
        var policy = ActionPolicy.forAction("introspect_substrate_summary");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.readOnly()).isTrue();
        assertThat(policy.domain()).isEqualTo("self");
    }

    @Test
    void autonomy_tier_is_visible() {
        assertThat(ActionPolicy.autonomyTierFor("introspect_substrate_summary"))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
    }

    @Test
    void actionTypeOf_resolves() {
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.IntrospectSubstrateSummary()))
            .isEqualTo("introspect_substrate_summary");
    }

    @Test
    void tool_description_names_composite_concept() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("introspect_substrate_summary"));
        assertThat(tools).hasSize(1);
        var desc = tools.get(0).function().description();
        assertThat(desc)
            .containsIgnoringCase("composite")
            .containsIgnoringCase("repair mode")
            .containsIgnoringCase("resilience")
            .containsIgnoringCase("Sanctuary");
    }
}
