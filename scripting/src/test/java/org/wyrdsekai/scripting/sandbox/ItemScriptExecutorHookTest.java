package org.wyrdsekai.scripting.sandbox;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase T — exercises {@link ItemScriptExecutor#executeHook}. */
class ItemScriptExecutorHookTest {

    @Test
    void execute_named_hook() {
        var script = """
            function onWebhook(event) {
                return { ok: true, kind: event.kind, source: event.source };
            }
            """;
        try (var exec = new ItemScriptExecutor()) {
            var event = Map.<String, Object>of(
                "kind", "webhook",
                "source", "/gh-prs",
                "payload", Map.of("body", Map.of("action", "opened")));
            var result = exec.executeHook("pr_notifier", script, "onWebhook", event,
                null, ItemCapabilitySet.UNRESTRICTED);
            assertThat(result).containsEntry("ok", true);
            assertThat(result).containsEntry("kind", "webhook");
            assertThat(result).containsEntry("source", "/gh-prs");
        }
    }

    @Test
    void falls_back_to_onEvent_when_named_hook_missing() {
        var script = """
            function onEvent(event) { return { ok: true, fallback: true, kind: event.kind }; }
            """;
        try (var exec = new ItemScriptExecutor()) {
            var event = Map.<String, Object>of("kind", "mqtt", "source", "home/temp");
            var result = exec.executeHook("x", script, "onMqtt", event, null,
                ItemCapabilitySet.UNRESTRICTED);
            assertThat(result).containsEntry("ok", true);
            assertThat(result).containsEntry("fallback", true);
        }
    }

    @Test
    void missing_hook_returns_structured_error() {
        var script = "function invoke(p) { return {x:1}; }";
        try (var exec = new ItemScriptExecutor()) {
            var result = exec.executeHook("x", script, "onWebhook",
                Map.of("kind", "webhook"), null, ItemCapabilitySet.UNRESTRICTED);
            assertThat(result).containsKey("error");
            assertThat(result).containsEntry("missing_hook", "onWebhook");
        }
    }

    @Test
    void hook_throws_caught_and_surfaced() {
        var script = """
            function onWebhook(e) { throw new Error("explode"); }
            """;
        try (var exec = new ItemScriptExecutor()) {
            var result = exec.executeHook("x", script, "onWebhook",
                Map.of(), null, ItemCapabilitySet.UNRESTRICTED);
            assertThat(result).containsKey("error");
            assertThat(String.valueOf(result.get("error"))).contains("explode");
        }
    }
}
