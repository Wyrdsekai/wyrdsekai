package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the Pre/PostAction hook system.
 */
class ActionHookRunnerTest {

    @Test
    void no_hooks_always_allows() {
        var runner = ActionHookRunner.none();
        var payload = new ActionHookRunner.HookPayload(
            "PRE_ACTION", "agent-1", "Ember", "go_to_room",
            Map.of("target", "Library"), "nexus", 0);

        var result = runner.run(ActionHookRunner.HookEvent.PRE_ACTION, payload);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void has_hooks_returns_correct_state() {
        var runner = new ActionHookRunner(Map.of(
            ActionHookRunner.HookEvent.PRE_ACTION, List.of("echo ok")
        ));
        assertThat(runner.hasHooks(ActionHookRunner.HookEvent.PRE_ACTION)).isTrue();
        assertThat(runner.hasHooks(ActionHookRunner.HookEvent.POST_ACTION)).isFalse();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void allow_hook_returns_allowed() {
        var runner = new ActionHookRunner(Map.of(
            ActionHookRunner.HookEvent.PRE_ACTION, List.of("exit 0")
        ));
        var payload = new ActionHookRunner.HookPayload(
            "PRE_ACTION", "agent-1", "Ember", "web_search",
            Map.of("query", "weather"), "nexus", 1);

        var result = runner.run(ActionHookRunner.HookEvent.PRE_ACTION, payload);
        assertThat(result.allowed()).isTrue();
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void deny_hook_blocks_action() {
        var runner = new ActionHookRunner(Map.of(
            ActionHookRunner.HookEvent.PRE_ACTION, List.of("cat > /dev/null; exit 2")
        ));
        var payload = new ActionHookRunner.HookPayload(
            "PRE_ACTION", "agent-1", "Ember", "create_room",
            Map.of("name", "Secret"), "nexus", 0);

        var result = runner.run(ActionHookRunner.HookEvent.PRE_ACTION, payload);
        assertThat(result.allowed()).isFalse();
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void warning_hook_allows_but_non_zero() {
        var runner = new ActionHookRunner(Map.of(
            ActionHookRunner.HookEvent.PRE_ACTION, List.of("cat > /dev/null; exit 1")
        ));
        var payload = new ActionHookRunner.HookPayload(
            "PRE_ACTION", "agent-1", "Ember", "go_to_room",
            Map.of(), "nexus", 0);

        var result = runner.run(ActionHookRunner.HookEvent.PRE_ACTION, payload);
        assertThat(result.allowed()).isTrue();
        // Warning hooks log but don't change the final result — run() returns ALLOW
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void hook_receives_payload_on_stdin() {
        // Hook reads stdin and checks for agent name
        var runner = new ActionHookRunner(Map.of(
            ActionHookRunner.HookEvent.PRE_ACTION,
            List.of("cat | grep -q Ember && exit 0 || exit 2")
        ));
        var payload = new ActionHookRunner.HookPayload(
            "PRE_ACTION", "agent-1", "Ember", "go_to_room",
            Map.of(), "nexus", 0);

        var result = runner.run(ActionHookRunner.HookEvent.PRE_ACTION, payload);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void multiple_hooks_first_deny_wins() {
        var runner = new ActionHookRunner(Map.of(
            ActionHookRunner.HookEvent.PRE_ACTION,
            List.of("cat > /dev/null; exit 0", "cat > /dev/null; exit 2", "cat > /dev/null; exit 0")
        ));
        var payload = new ActionHookRunner.HookPayload(
            "PRE_ACTION", "agent-1", "Ember", "zone_command",
            Map.of(), "nexus", 0);

        var result = runner.run(ActionHookRunner.HookEvent.PRE_ACTION, payload);
        assertThat(result.allowed()).isFalse();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void hook_output_captured() {
        var runner = new ActionHookRunner(Map.of(
            ActionHookRunner.HookEvent.POST_ACTION,
            List.of("cat > /dev/null; echo 'action logged'")
        ));
        var payload = new ActionHookRunner.HookPayload(
            "POST_ACTION", "agent-1", "Ember", "go_to_room",
            Map.of(), "nexus", 0);

        var result = runner.run(ActionHookRunner.HookEvent.POST_ACTION, payload);
        assertThat(result.allowed()).isTrue();
        // Output is logged but run() returns ALLOW for non-deny hooks
    }
}
