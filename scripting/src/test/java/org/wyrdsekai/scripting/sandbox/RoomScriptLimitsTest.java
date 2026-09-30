package org.wyrdsekai.scripting.sandbox;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.scripting.api.McpGatewayProvider;
import org.wyrdsekai.scripting.api.WorldApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Room scripts run under an enforced limit. Until 2026-09-28 every room hook ran with
 * {@code ResourceLimits.UNLIMITED} on the room actor's thread, so {@code while(true){}} in a hook
 * held that thread for good while the docs said "an infinite loop stops that room, not the world".
 */
class RoomScriptLimitsTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger sandboxLog;

    @BeforeEach
    void attach() {
        appender = new ListAppender<>();
        appender.start();
        sandboxLog = (Logger) LoggerFactory.getLogger(ScriptSandbox.class);
        sandboxLog.addAppender(appender);
    }

    @AfterEach
    void detach() {
        sandboxLog.detachAppender(appender);
    }

    private List<String> warnings() {
        var out = new ArrayList<String>();
        for (var e : appender.list) {
            if (e.getLevel() == Level.WARN) out.add(e.getFormattedMessage());
        }
        return out;
    }

    @Test
    void an_infinite_loop_in_a_hook_stops_and_the_next_hook_runs() {
        var script = """
            function onSay(id, name, text) { while (true) {} }
            function onEnter(id, name, from) { world.emit("narrate", { text: "welcome" }); }
            """;
        var emitted = new ArrayList<String>();
        var world = new WorldApi("loop-room");
        world.onEvent((type, data) -> emitted.add(type + ":" + data.get("text")));
        try (var sandbox = new ScriptSandbox("loop-room")) {
            long t0 = System.nanoTime();
            sandbox.callHook(script, "onSay", world, "p1", "Alice", "hello");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(ms).as("the default limit stops a runaway loop quickly").isLessThan(10_000);

            sandbox.callHook(script, "onEnter", world, "p1", "Alice", "north");
            assertThat(emitted).containsExactly("narrate:welcome");
        }
        assertThat(warnings())
            .anySatisfy(w -> assertThat(w).contains("loop-room").contains("onSay")
                .contains("statements"));
    }

    @Test
    void work_that_runs_no_statements_is_stopped_by_the_cpu_limit() {
        // A backreference forces the backtracking regex engine; this pattern is exponential
        // and executes no JavaScript statements while it burns CPU.
        var script = """
            function onSay(id, name, text) {
              /^(a+)+\\1b$/.test("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            }
            """;
        var limits = new ResourceLimits(1_000_000, 300, 0, 0);
        try (var sandbox = new ScriptSandbox("regex-room", limits)) {
            long t0 = System.nanoTime();
            sandbox.callHook(script, "onSay", new WorldApi("regex-room"), "p1", "Alice", "x");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(ms).isLessThan(5_000);
        }
        assertThat(warnings())
            .anySatisfy(w -> assertThat(w).contains("regex-room").contains("onSay")
                .contains("CPU"));
    }

    @Test
    void waiting_on_the_host_is_not_charged_to_the_script() {
        // The sleep stands in for a slow MCP server answering a hook's world.mcp() call.
        var slowWorld = new WorldApi("slow-room");
        slowWorld.setMcpGatewayProvider(new McpGatewayProvider() {
            @Override
            public Map<String, Object> execute(String agentId, String zoneId, String serviceId,
                                               String toolName, Map<String, Object> params) {
                try {
                    Thread.sleep(600);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Map.of("success", true);
            }

            @Override
            public boolean isAvailable(String serviceId) { return true; }

            @Override
            public int remainingBudget(String agentId, String serviceId) { return 100; }
        });
        var emitted = new ArrayList<String>();
        slowWorld.onEvent((type, data) -> emitted.add(type));
        var script = """
            function onSay(id, name, text) {
              world.mcp("library", "search", {});
              world.emit("narrate", { text: "done" });
            }
            """;
        var limits = new ResourceLimits(1_000_000, 200, 0, 0);
        try (var sandbox = new ScriptSandbox("slow-room", limits)) {
            sandbox.callHook(script, "onSay", slowWorld, "p1", "Alice", "x");
        }
        assertThat(emitted).containsExactly("narrate");
        assertThat(warnings()).isEmpty();
    }

    @Test
    void getHints_and_tool_definitions_are_bounded_too() {
        var script = """
            function getHints() { for (;;) {} }
            """;
        try (var sandbox = new ScriptSandbox("hint-room")) {
            assertThat(sandbox.callHintsFunction(script, new WorldApi("hint-room"))).isEmpty();
            assertThat(sandbox.executeAsString(script + "\n;getHints()", new WorldApi("hint-room")))
                .isNull();
        }
    }
}
