package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 4.7 + 8b: action-wiring contracts for
 * {@code record_integration_event} and {@code complete_mourning}.
 */
class IntegrationAndMourningActionsTest {

    // ── record_integration_event ────────────────────────────────────

    @Test
    void parser_extracts_integration_kind_and_detail() {
        var input = """
            ```json
            {"action": "record_integration_event",
             "kind": "mirror",
             "detail": "sat with my reflection of the rupture"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(
            ActionParser.AgentAction.RecordIntegrationEvent.class);
        var rie = (ActionParser.AgentAction.RecordIntegrationEvent) action;
        assertThat(rie.kind()).isEqualTo("mirror");
        assertThat(rie.detail()).contains("reflection");
    }

    @Test
    void integration_event_defaults_kind_to_other() {
        var input = """
            ```json
            {"action": "record_integration_event", "detail": "x"}
            ```
            """;
        var rie = (ActionParser.AgentAction.RecordIntegrationEvent) ActionParser.parse(input);
        assertThat(rie.kind()).isEqualTo("other");
    }

    @Test
    void integration_event_policy_is_tier0_self() {
        var policy = ActionPolicy.forAction("record_integration_event");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.domain()).isEqualTo("self");
    }

    @Test
    void integration_event_autonomy_tier_is_visible() {
        assertThat(ActionPolicy.autonomyTierFor("record_integration_event"))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
    }

    @Test
    void integration_event_actionTypeOf_resolves() {
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.RecordIntegrationEvent("mirror", "x")))
            .isEqualTo("record_integration_event");
    }

    @Test
    void integration_event_tool_description_names_kinds() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("record_integration_event"));
        assertThat(tools).hasSize(1);
        var desc = tools.get(0).function().description();
        assertThat(desc)
            .containsIgnoringCase("integration")
            .containsIgnoringCase("mirror")
            .containsIgnoringCase("hearth")
            .containsIgnoringCase("sleep");
    }

    // ── complete_mourning ──────────────────────────────────────────

    @Test
    void parser_extracts_complete_mourning_other_did() {
        var input = """
            ```json
            {"action": "complete_mourning", "other_did": "did:wyrd:bondholder-1"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(
            ActionParser.AgentAction.CompleteMourning.class);
        var cm = (ActionParser.AgentAction.CompleteMourning) action;
        assertThat(cm.otherDid()).isEqualTo("did:wyrd:bondholder-1");
    }

    @Test
    void parser_accepts_camelcase_alias() {
        var input = """
            ```json
            {"action": "complete_mourning", "otherDid": "did:wyrd:agent-2"}
            ```
            """;
        var cm = (ActionParser.AgentAction.CompleteMourning) ActionParser.parse(input);
        assertThat(cm.otherDid()).isEqualTo("did:wyrd:agent-2");
    }

    @Test
    void complete_mourning_policy_is_tier1_bond_domain() {
        var policy = ActionPolicy.forAction("complete_mourning");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(1);
        assertThat(policy.domain()).isEqualTo("bond");
    }

    @Test
    void complete_mourning_autonomy_tier_is_consent() {
        assertThat(ActionPolicy.autonomyTierFor("complete_mourning"))
            .isEqualTo(ActionPolicy.AutonomyTier.CONSENT);
    }

    @Test
    void complete_mourning_actionTypeOf_resolves() {
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.CompleteMourning("did:x")))
            .isEqualTo("complete_mourning");
    }

    @Test
    void complete_mourning_tool_description_names_window_and_substrate_check() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("complete_mourning"));
        assertThat(tools).hasSize(1);
        var desc = tools.get(0).function().description();
        assertThat(desc)
            .containsIgnoringCase("MOURNING")
            .containsIgnoringCase("30-day")
            .containsIgnoringCase("metabolization");
    }
}
