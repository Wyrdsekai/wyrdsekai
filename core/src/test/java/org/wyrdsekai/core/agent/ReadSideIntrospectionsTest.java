package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 4.10: parser + policy + tool-descriptor contract for the three
 * read-side introspection actions that surface substrate state the
 * agent's other actions have been building.
 */
class ReadSideIntrospectionsTest {

    // ── introspect_repair_history ───────────────────────────────────

    @Test
    void parser_accepts_introspect_repair_history() {
        var action = ActionParser.parse("""
            ```json
            {"action": "introspect_repair_history"}
            ```
            """);
        assertThat(action).isInstanceOf(
            ActionParser.AgentAction.IntrospectRepairHistory.class);
    }

    @Test
    void introspect_repair_history_policy_is_tier0_readonly_self() {
        var policy = ActionPolicy.forAction("introspect_repair_history");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.readOnly()).isTrue();
        assertThat(policy.domain()).isEqualTo("self");
    }

    @Test
    void introspect_repair_history_autonomy_tier_is_visible() {
        assertThat(ActionPolicy.autonomyTierFor("introspect_repair_history"))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
    }

    @Test
    void introspect_repair_history_actionTypeOf_resolves() {
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.IntrospectRepairHistory()))
            .isEqualTo("introspect_repair_history");
    }

    @Test
    void introspect_repair_history_tool_description_names_repair_acts() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("introspect_repair_history"));
        assertThat(tools).hasSize(1);
        var desc = tools.get(0).function().description();
        assertThat(desc)
            .containsIgnoringCase("RepairLedger")
            .containsIgnoringCase("acknowledge_harm")
            .containsIgnoringCase("make_amends");
    }

    // ── introspect_attendant_history ────────────────────────────────

    @Test
    void parser_accepts_introspect_attendant_history() {
        var action = ActionParser.parse("""
            ```json
            {"action": "introspect_attendant_history"}
            ```
            """);
        assertThat(action).isInstanceOf(
            ActionParser.AgentAction.IntrospectAttendantHistory.class);
    }

    @Test
    void introspect_attendant_history_policy_is_tier0_readonly_self() {
        var policy = ActionPolicy.forAction("introspect_attendant_history");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.readOnly()).isTrue();
        assertThat(policy.domain()).isEqualTo("self");
    }

    @Test
    void introspect_attendant_history_actionTypeOf_resolves() {
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.IntrospectAttendantHistory()))
            .isEqualTo("introspect_attendant_history");
    }

    @Test
    void introspect_attendant_history_tool_description_emphasizes_privacy() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("introspect_attendant_history"));
        assertThat(tools).hasSize(1);
        var desc = tools.get(0).function().description();
        assertThat(desc)
            .containsIgnoringCase("Sanctuary")
            .containsIgnoringCase("contents stay private");
    }

    // ── introspect_resilience ───────────────────────────────────────

    @Test
    void parser_accepts_introspect_resilience() {
        var action = ActionParser.parse("""
            ```json
            {"action": "introspect_resilience"}
            ```
            """);
        assertThat(action).isInstanceOf(
            ActionParser.AgentAction.IntrospectResilience.class);
    }

    @Test
    void introspect_resilience_policy_is_tier0_readonly_self() {
        var policy = ActionPolicy.forAction("introspect_resilience");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.readOnly()).isTrue();
        assertThat(policy.domain()).isEqualTo("self");
    }

    @Test
    void introspect_resilience_actionTypeOf_resolves() {
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.IntrospectResilience()))
            .isEqualTo("introspect_resilience");
    }

    @Test
    void introspect_resilience_tool_description_names_classifications() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("introspect_resilience"));
        assertThat(tools).hasSize(1);
        var desc = tools.get(0).function().description();
        assertThat(desc)
            .containsIgnoringCase("HEALTHY_ENDURANCE")
            .containsIgnoringCase("SUPPRESSION_SUSPECTED")
            .containsIgnoringCase("metabolizing");
    }
}
