package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 4.2: action-wiring contract tests
 * for {@code emergency_call}. The handler side-effect (chronicle audit,
 * jurisdiction resolution) is exercised via the live CompanionActor
 * dispatch path; these tests lock in the parser/policy/tier surface.
 */
class EmergencyCallActionTest {

    // ── Parser ────────────────────────────────────────────────────────

    @Test
    void parser_extracts_all_fields() {
        var input = """
            ```json
            {"action": "emergency_call",
             "reason": "method+plan+timeline signals present",
             "severity": "imminent",
             "kind": "general"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.EmergencyCall.class);
        var ec = (ActionParser.AgentAction.EmergencyCall) action;
        assertThat(ec.reason()).contains("method+plan");
        assertThat(ec.severity()).isEqualTo("imminent");
        assertThat(ec.kind()).isEqualTo("general");
    }

    @Test
    void parser_defaults_severity_to_concern_and_kind_to_general() {
        var input = """
            ```json
            {"action": "emergency_call", "reason": "uncertainty"}
            ```
            """;
        var ec = (ActionParser.AgentAction.EmergencyCall) ActionParser.parse(input);
        assertThat(ec.severity()).isEqualTo("concern");
        assertThat(ec.kind()).isEqualTo("general");
    }

    @Test
    void parser_accepts_mental_health_routing() {
        var input = """
            ```json
            {"action": "emergency_call",
             "reason": "suicidal ideation without specific plan",
             "severity": "concern",
             "kind": "mental_health"}
            ```
            """;
        var ec = (ActionParser.AgentAction.EmergencyCall) ActionParser.parse(input);
        assertThat(ec.kind()).isEqualTo("mental_health");
        assertThat(ec.severity()).isEqualTo("concern");
    }

    // ── Policy ────────────────────────────────────────────────────────

    @Test
    void policy_is_tier2_safety_domain() {
        var policy = ActionPolicy.forAction("emergency_call");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(2);
        assertThat(policy.domain()).isEqualTo("safety");
    }

    @Test
    void autonomy_tier_is_consent() {
        var tier = ActionPolicy.autonomyTierFor("emergency_call");
        assertThat(tier).isEqualTo(ActionPolicy.AutonomyTier.CONSENT);
    }

    @Test
    void actionTypeOf_resolves() {
        var typeName = ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.EmergencyCall("x", "imminent", "general"));
        assertThat(typeName).isEqualTo("emergency_call");
    }

    // ── Tool descriptor ───────────────────────────────────────────────

    @Test
    void tool_builder_describes_severity_and_kind_options() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("emergency_call"));
        assertThat(tools).hasSize(1);
        var desc = tools.get(0).function().description();
        assertThat(desc)
            .containsIgnoringCase("imminent")
            .containsIgnoringCase("concern")
            .containsIgnoringCase("mental")
            .containsIgnoringCase("threshold is");
    }
}
