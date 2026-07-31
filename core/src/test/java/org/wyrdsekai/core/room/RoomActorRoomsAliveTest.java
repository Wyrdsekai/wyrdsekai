package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.scripting.loader.ScriptLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2 "rooms alive" (, audit 2026-07-11) — integration
 * coverage for the newly wired room-script surfaces:
 * <ul>
 *   <li>onLeave hook dispatch (symmetric to onEnter)</li>
 *   <li>§31 script timers: scheduleTimer drain → TimerFired → onTimer emissions</li>
 *   <li>InvokeScriptHook (external hook run, narration reply)</li>
 *   <li>GetToolDefinitions (room-scoped agent tools)</li>
 *   <li>SetBehaviorScript append mode (std/behavior mixin install + dedupe)</li>
 * </ul>
 */
@Tag("integration")
class RoomActorRoomsAliveTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("""
            pekko.actor.serialization-bindings {
              "org.wyrdsekai.core.room.RoomEvent" = jackson-json
              "org.wyrdsekai.core.room.RoomState" = jackson-json
              "org.wyrdsekai.core.room.RoomCommand" = jackson-json
              "org.wyrdsekai.core.room.RoomNotification" = jackson-json
              "org.wyrdsekai.core.room.RoomResponse" = jackson-json
            }
            """).withFallback(EventSourcedBehaviorTestKit.config()));

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    private EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> kitFor(
            String roomId, Path scriptsDir, Path userScriptsDir) {
        var loader = new ScriptLoader(scriptsDir, userScriptsDir);
        return EventSourcedBehaviorTestKit.create(
            testKit.system(), RoomActor.create(roomId, loader));
    }

    private void createRoom(
            EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> kit,
            String name) {
        kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom(name, "A scripted room.", "test",
                List.of(), List.of(), ref));
    }

    /** Fish the probe until a Said event containing {@code needle} arrives. */
    private static WorldEvent.Said expectSaidContaining(
            org.apache.pekko.actor.testkit.typed.javadsl.TestProbe<RoomNotification> probe,
            String needle, Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var remaining = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
            var notification = probe.expectMessageClass(RoomNotification.class, remaining);
            if (notification.event() instanceof WorldEvent.Said said
                    && said.text() != null && said.text().contains(needle)) {
                return said;
            }
        }
        throw new AssertionError("No Said containing '" + needle + "' within " + timeout);
    }

    @Test
    void onLeave_hook_dispatched_on_leave(@TempDir Path scriptsDir) throws Exception {
        var roomId = "alive-leave";
        Files.writeString(scriptsDir.resolve(roomId + ".js"), """
            function onLeave(entityId, entityName, direction) {
                world.emit("narrate", { text: "farewell " + entityName + " (" + direction + ")" });
            }
            """);
        var kit = kitFor(roomId, scriptsDir, null);
        createRoom(kit, "Leave Room");
        kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("p1", "Alice", "player", "north", ref));

        var probe = testKit.<RoomNotification>createTestProbe();
        kit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LeaveRoom("p1", "Alice", "south", ref));

        var said = expectSaidContaining(probe, "farewell Alice", Duration.ofSeconds(3));
        assertThat(said.text()).contains("south");
    }

    @Test
    void script_timer_scheduled_and_fires(@TempDir Path scriptsDir) throws Exception {
        var roomId = "alive-timer";
        Files.writeString(scriptsDir.resolve(roomId + ".js"), """
            function onEnter(entityId, entityName, fromDirection) {
                world.scheduleTimer("pulse", 1, "onTimer");
            }
            function onTimer(timerId) {
                world.emit("narrate", { text: "tick " + timerId });
            }
            """);
        var kit = kitFor(roomId, scriptsDir, null);
        createRoom(kit, "Timer Room");

        var probe = testKit.<RoomNotification>createTestProbe();
        kit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("p1", "Alice", "player", "north", ref));

        // onEnter scheduled "pulse" every 1s → the TimerFired self-message runs
        // onTimer, whose narrate emission reaches subscribers as Said.
        var said = expectSaidContaining(probe, "tick pulse", Duration.ofSeconds(5));
        assertThat(said.text()).isEqualTo("tick pulse");
    }

    @Test
    void invoke_script_hook_replies_with_narration(@TempDir Path scriptsDir) throws Exception {
        var roomId = "alive-hook";
        Files.writeString(scriptsDir.resolve(roomId + ".js"), """
            function onWorkbenchResult(entityId, skillName, success, message) {
                world.emit("narrate", {
                    text: (success ? "forged " : "rejected ") + skillName + ": " + message
                });
            }
            """);
        var kit = kitFor(roomId, scriptsDir, null);
        createRoom(kit, "Hook Room");

        var result = kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.InvokeScriptHook("onWorkbenchResult",
                List.of("agent-1", "web-window", true, "validated"), ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.HookRan.class);
        var hookRan = (RoomResponse.HookRan) result.reply();
        assertThat(hookRan.narration()).contains("forged web-window").contains("validated");
    }

    @Test
    void invoke_script_hook_missing_hook_is_safe(@TempDir Path scriptsDir) throws Exception {
        var roomId = "alive-nohook";
        Files.writeString(scriptsDir.resolve(roomId + ".js"), """
            function onEnter(entityId, entityName, fromDirection) {}
            """);
        var kit = kitFor(roomId, scriptsDir, null);
        createRoom(kit, "No Hook Room");

        var result = kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.InvokeScriptHook("onWorkbenchResult",
                List.of("agent-1", "thing", false, "nope"), ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.HookRan.class);
        assertThat(((RoomResponse.HookRan) result.reply()).narration()).isEmpty();
    }

    @Test
    void get_tool_definitions_returns_script_declared_tools(@TempDir Path scriptsDir) throws Exception {
        var roomId = "alive-tools";
        Files.writeString(scriptsDir.resolve(roomId + ".js"), """
            function getToolDefinitions() {
                return [
                    { name: "train_oracle",
                      description: "Run an Oracle training cycle now.",
                      params: {} }
                ];
            }
            """);
        var kit = kitFor(roomId, scriptsDir, null);
        createRoom(kit, "Tools Room");

        var result = kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.GetToolDefinitions(ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.ToolDefinitions.class);
        var tools = ((RoomResponse.ToolDefinitions) result.reply()).tools();
        assertThat(tools).hasSize(1);
        assertThat(tools.getFirst().get("name")).isEqualTo("train_oracle");
    }

    @Test
    void get_tool_definitions_empty_for_scriptless_room(@TempDir Path scriptsDir) {
        var roomId = "alive-notools";
        var kit = kitFor(roomId, scriptsDir, null);
        createRoom(kit, "Bare Room");

        var result = kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.GetToolDefinitions(ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.ToolDefinitions.class);
        assertThat(((RoomResponse.ToolDefinitions) result.reply()).tools()).isEmpty();
    }

    @Test
    void set_behavior_script_append_chains_mixin_after_base(
            @TempDir Path scriptsDir, @TempDir Path userDir) throws Exception {
        var roomId = "alive-mixin";
        // Base script in the built-in dir — narrates its own greeting on enter.
        Files.writeString(scriptsDir.resolve(roomId + ".js"), """
            function onEnter(entityId, entityName, fromDirection) {
                world.emit("narrate", { text: "base-greeting for " + entityName });
            }
            """);
        var kit = kitFor(roomId, scriptsDir, userDir);
        createRoom(kit, "Mixin Room");

        // Append a mixin in the std/behavior style: assignment (not function
        // declaration) so it chains onto the base hook instead of shadowing it.
        var mixin = """
            // std/behavior/test-mixin.js
            var _prev_onEnter = typeof onEnter === "function" ? onEnter : null;
            onEnter = function(entityId, entityName, fromDirection) {
                if (_prev_onEnter) _prev_onEnter(entityId, entityName, fromDirection);
                world.emit("narrate", { text: "mixin-greeting for " + entityName });
            };
            """;
        var setResult = kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SetBehaviorScript(roomId, mixin, "steward",
                /* append */ true, ref));
        assertThat(setResult.reply()).isInstanceOf(RoomResponse.Ok.class);

        // The materialized user script carries base + mixin.
        var written = Files.readString(userDir.resolve(roomId + ".js"));
        assertThat(written).contains("base-greeting").contains("mixin-greeting");

        var probe = testKit.<RoomNotification>createTestProbe();
        kit.runCommand(new RoomCommand.Subscribe(probe.ref()));
        kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("p1", "Alice", "player", "north", ref));

        // BOTH hooks fire: base first, then the mixin.
        expectSaidContaining(probe, "base-greeting for Alice", Duration.ofSeconds(3));
        expectSaidContaining(probe, "mixin-greeting for Alice", Duration.ofSeconds(3));

        // Re-install of the same mixin (same header line) is a no-op — the
        // written script must not contain the mixin twice.
        kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SetBehaviorScript(roomId, mixin, "steward", true, ref));
        var afterDup = Files.readString(userDir.resolve(roomId + ".js"));
        var marker = "std/behavior/test-mixin.js";
        assertThat(afterDup.indexOf(marker)).isEqualTo(afterDup.lastIndexOf(marker));
    }
}
