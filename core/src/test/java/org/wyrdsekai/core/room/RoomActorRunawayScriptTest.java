package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.FishingOutcomes;
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
 * A room script stuck in {@code while(true){}} used to hold the room actor's thread for good:
 * hooks ran with no limit on the actor thread. Now the hook is stopped and the room keeps working.
 */
@Tag("integration")
class RoomActorRunawayScriptTest {

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

    @Test
    void a_hook_that_never_ends_is_stopped_and_the_room_answers_the_next_command(
            @TempDir Path scripts) throws Exception {
        Files.writeString(scripts.resolve("runaway-room.js"), """
            function onSay(id, name, text) {
              if (text === "spin") { while (true) {} }
              world.emit("narrate", { text: "heard " + text });
            }
            """);
        var room = EventSourcedBehaviorTestKit.<RoomCommand, RoomEvent, RoomState>create(
            testKit.system(), RoomActor.create("runaway-room", new ScriptLoader(scripts)));
        room.<RoomResponse>runCommand(ref -> new RoomCommand.CreateRoom(
            "Runaway Room", "A room whose script loops.", "test", List.of(), List.of(), ref));
        room.<RoomResponse>runCommand(ref -> new RoomCommand.EnterRoom(
            "player-1", "Alice", "player", "north", ref));
        var probe = testKit.<RoomNotification>createTestProbe();
        room.runCommand(new RoomCommand.Subscribe(probe.ref()));

        long t0 = System.nanoTime();
        room.<RoomResponse>runCommand(ref -> new RoomCommand.SayInRoom("player-1", "Alice", "spin", ref));
        // The actor only takes this command once the looping hook has been stopped.
        var reply = room.<RoomResponse>runCommand(
            ref -> new RoomCommand.SayInRoom("player-1", "Alice", "hello", ref));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(reply.reply()).isNotNull();
        assertThat(ms).as("the looping hook is stopped well inside the test kit's timeout")
            .isLessThan(3_000);
        var heard = probe.fishForMessage(Duration.ofSeconds(5), n ->
            n.event() instanceof WorldEvent.Said said && said.text().equals("heard hello")
                ? FishingOutcomes.complete()
                : FishingOutcomes.continueAndIgnore());
        assertThat(heard).isNotEmpty();
    }
}
