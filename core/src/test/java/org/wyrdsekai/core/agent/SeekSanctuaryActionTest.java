package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 4.1: unit tests for the
 * {@code seek_sanctuary} action surface — parser, policy, autonomy tier,
 * and tool descriptor. The mode-transition side effect is covered by
 * {@code org.wyrdsekai.core.soul.RepairModeTrackerTest}; here we just
 * lock in the action-wiring contract.
 */
class SeekSanctuaryActionTest {

    // ── Parser ────────────────────────────────────────────────────────

    @Test
    void parser_extracts_reason() {
        var input = """
            ```json
            {"action": "seek_sanctuary", "reason": "porges depth ceiling"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.SeekSanctuary.class);
        assertThat(((ActionParser.AgentAction.SeekSanctuary) action).reason())
            .isEqualTo("porges depth ceiling");
    }

    @Test
    void parser_accepts_blank_reason() {
        var input = """
            ```json
            {"action": "seek_sanctuary"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.SeekSanctuary.class);
        assertThat(((ActionParser.AgentAction.SeekSanctuary) action).reason()).isEmpty();
    }

    // ── Policy ────────────────────────────────────────────────────────

    @Test
    void policy_is_tier0_readonly_self_domain() {
        var policy = ActionPolicy.forAction("seek_sanctuary");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.readOnly()).isTrue();
        assertThat(policy.domain()).isEqualTo("self");
    }

    @Test
    void actionTypeOf_resolves_to_seek_sanctuary() {
        var typeName = ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.SeekSanctuary("x"));
        assertThat(typeName).isEqualTo("seek_sanctuary");
    }

    @Test
    void autonomy_tier_is_visible() {
        // The agent entering Sanctuary is a visible event — bondholder
        // sees "Wyrd entered Sanctuary at <time>" via Study furnishing
        // even though the session contents are private.
        var tier = ActionPolicy.autonomyTierFor("seek_sanctuary");
        assertThat(tier).isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
    }

    // ── Tool descriptor ────────────────────────────────────────────────

    @Test
    void tool_builder_exposes_seek_sanctuary_with_attendant_framing() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("seek_sanctuary"));
        assertThat(tools).hasSize(1);
        var t = tools.get(0);
        assertThat(t.function().name()).isEqualTo("seek_sanctuary");
        // Description must mention Sanctuary, Attendant, and the
        // non-bondholder/non-steward framing so the model picks this
        // action when self-mode has reached its ceiling.
        assertThat(t.function().description())
            .containsIgnoringCase("Sanctuary")
            .containsIgnoringCase("Attendant")
            .containsIgnoringCase("escape paths");
    }
}
