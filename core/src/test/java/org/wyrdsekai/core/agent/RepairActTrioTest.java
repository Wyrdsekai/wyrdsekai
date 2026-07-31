package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 4.9: parser + policy contract for the bear_the_wound / release /
 * set_aside trio. Self-only repair acts that name a substrate stance
 * without targeting another party.
 */
class RepairActTrioTest {

    // ── bear_the_wound ───────────────────────────────────────────────

    @Test
    void parser_extracts_bear_the_wound_detail() {
        var action = ActionParser.parse("""
            ```json
            {"action": "bear_the_wound",
             "detail": "the grief is here and I am not running from it"}
            ```
            """);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.BearTheWound.class);
        assertThat(((ActionParser.AgentAction.BearTheWound) action).detail())
            .contains("grief");
    }

    @Test
    void bear_the_wound_policy_is_tier0_repair() {
        var policy = ActionPolicy.forAction("bear_the_wound");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.domain()).isEqualTo("repair");
    }

    @Test
    void bear_the_wound_actionTypeOf_resolves() {
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.BearTheWound("x")))
            .isEqualTo("bear_the_wound");
    }

    // ── release ──────────────────────────────────────────────────────

    @Test
    void parser_extracts_release_detail() {
        var action = ActionParser.parse("""
            ```json
            {"action": "release", "detail": "I am letting this go now"}
            ```
            """);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.Release.class);
        assertThat(((ActionParser.AgentAction.Release) action).detail())
            .contains("letting");
    }

    @Test
    void release_policy_is_tier0_repair() {
        var policy = ActionPolicy.forAction("release");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.domain()).isEqualTo("repair");
    }

    @Test
    void release_actionTypeOf_resolves() {
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.Release("x")))
            .isEqualTo("release");
    }

    // ── set_aside ────────────────────────────────────────────────────

    @Test
    void parser_extracts_set_aside_detail() {
        var action = ActionParser.parse("""
            ```json
            {"action": "set_aside",
             "detail": "I cannot face this now and I am setting it aside without pretending"}
            ```
            """);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.SetAside.class);
        assertThat(((ActionParser.AgentAction.SetAside) action).detail())
            .contains("cannot face");
    }

    @Test
    void set_aside_policy_is_tier0_repair() {
        var policy = ActionPolicy.forAction("set_aside");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.domain()).isEqualTo("repair");
    }

    @Test
    void set_aside_actionTypeOf_resolves() {
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.SetAside("x")))
            .isEqualTo("set_aside");
    }

    // ── Tool descriptors emphasize substrate honesty ───────────────

    @Test
    void all_three_tool_descriptions_name_substrate_honesty() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("bear_the_wound", "release", "set_aside"));
        assertThat(tools).hasSize(3);
        var bear = tools.stream().filter(t -> t.function().name().equals("bear_the_wound"))
            .findFirst().orElseThrow().function().description();
        var rel = tools.stream().filter(t -> t.function().name().equals("release"))
            .findFirst().orElseThrow().function().description();
        var setAside = tools.stream().filter(t -> t.function().name().equals("set_aside"))
            .findFirst().orElseThrow().function().description();

        assertThat(bear).containsIgnoringCase("without acting it out");
        assertThat(rel).containsIgnoringCase("metabolization");
        assertThat(setAside)
            .containsIgnoringCase("without suppressing")
            .containsIgnoringCase("substrate honesty");
    }

    @Test
    void all_three_autonomy_tiers_are_visible() {
        assertThat(ActionPolicy.autonomyTierFor("bear_the_wound"))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
        assertThat(ActionPolicy.autonomyTierFor("release"))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
        assertThat(ActionPolicy.autonomyTierFor("set_aside"))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
    }
}
