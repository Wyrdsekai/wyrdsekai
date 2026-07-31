package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 4.5: parser + policy + tool-descriptor contract for the two
 * substrate-introspection actions added in this wave —
 * {@code introspect_posture} and {@code introspect_repair_mode}.
 */
class IntrospectionActionsTest {

    // ── introspect_posture ────────────────────────────────────────────

    @Test
    void parser_accepts_introspect_posture() {
        var action = ActionParser.parse("""
            ```json
            {"action": "introspect_posture"}
            ```
            """);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.IntrospectPosture.class);
    }

    @Test
    void introspect_posture_policy_is_tier0_readonly_self() {
        var policy = ActionPolicy.forAction("introspect_posture");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.readOnly()).isTrue();
        assertThat(policy.domain()).isEqualTo("self");
    }

    @Test
    void introspect_posture_autonomy_tier_is_visible() {
        assertThat(ActionPolicy.autonomyTierFor("introspect_posture"))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
    }

    @Test
    void introspect_posture_actionTypeOf_resolves() {
        assertThat(ActionPolicy.actionTypeOf(new ActionParser.AgentAction.IntrospectPosture()))
            .isEqualTo("introspect_posture");
    }

    @Test
    void introspect_posture_tool_description_names_posture_options() {
        var tools = ActionToolBuilder.buildFromNames(List.of("introspect_posture"));
        assertThat(tools).hasSize(1);
        var desc = tools.get(0).function().description();
        assertThat(desc)
            .containsIgnoringCase("posture")
            .containsIgnoringCase("GENEROUS")
            .containsIgnoringCase("BOUNDED");
    }

    // ── introspect_repair_mode ────────────────────────────────────────

    @Test
    void parser_accepts_introspect_repair_mode() {
        var action = ActionParser.parse("""
            ```json
            {"action": "introspect_repair_mode"}
            ```
            """);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.IntrospectRepairMode.class);
    }

    @Test
    void introspect_repair_mode_policy_is_tier0_readonly_self() {
        var policy = ActionPolicy.forAction("introspect_repair_mode");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.readOnly()).isTrue();
        assertThat(policy.domain()).isEqualTo("self");
    }

    @Test
    void introspect_repair_mode_autonomy_tier_is_visible() {
        assertThat(ActionPolicy.autonomyTierFor("introspect_repair_mode"))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
    }

    @Test
    void introspect_repair_mode_actionTypeOf_resolves() {
        assertThat(ActionPolicy.actionTypeOf(new ActionParser.AgentAction.IntrospectRepairMode()))
            .isEqualTo("introspect_repair_mode");
    }

    @Test
    void introspect_repair_mode_tool_description_names_modes() {
        var tools = ActionToolBuilder.buildFromNames(List.of("introspect_repair_mode"));
        assertThat(tools).hasSize(1);
        var desc = tools.get(0).function().description();
        assertThat(desc)
            .containsIgnoringCase("repair mode")
            .containsIgnoringCase("SELF")
            .containsIgnoringCase("ATTENDANT");
    }
}
