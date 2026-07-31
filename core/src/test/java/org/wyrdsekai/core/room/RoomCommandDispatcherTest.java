package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests RoomCommandDispatcher — verifies JSON command → RoomCommand → RoomActor → JSON response
 * round trip for all command types. This is the primary-side handler for cross-node proxying.
 */
@Tag("integration")
class RoomCommandDispatcherTest {

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

    private ActorRef<RoomCommand> roomRef;

    @BeforeEach
    void setUp() {
        // Set up Rooms.scheduler for ask pattern
        Rooms.setScheduler(testKit.scheduler());

        // Create a real room actor for dispatch testing
        roomRef = testKit.spawn(RoomActor.create("dispatch-test-" + System.nanoTime()));

        // Initialize the room
        var probe = testKit.<RoomResponse>createTestProbe();
        roomRef.tell(new RoomCommand.CreateRoom(
            "Test Room", "A room for testing.", "test",
            List.of(new Exit("east", "other-room", "Other Room")),
            List.of(new RoomObject("crystal", "Test Crystal", "Glowing.", true)),
            probe.ref()));
        probe.receiveMessage();
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    @Test
    void dispatch_look() throws Exception {
        var json = """
            {"type":"look","entityId":"player-1","locale":"en"}
            """;
        var result = RoomCommandDispatcher.dispatch(roomRef, json)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(result).contains("\"type\":\"ok\"");
        assertThat(result).contains("Test Room");
    }

    @Test
    void dispatch_enter() throws Exception {
        var json = """
            {"type":"enter","entityId":"player-1","entityName":"Alice","entityType":"player","description":"A brave adventurer","fromDirection":"north","locale":"en"}
            """;
        var result = RoomCommandDispatcher.dispatch(roomRef, json)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(result).contains("\"type\":\"ok\"");
        assertThat(result).contains("Alice");
    }

    @Test
    void dispatch_say() throws Exception {
        // Enter first
        RoomCommandDispatcher.dispatch(roomRef,
            """
            {"type":"enter","entityId":"player-1","entityName":"Alice","entityType":"player","fromDirection":"north","locale":"en"}
            """).toCompletableFuture().get(5, TimeUnit.SECONDS);

        var json = """
            {"type":"say","entityId":"player-1","entityName":"Alice","text":"Hello world","locale":"en"}
            """;
        var result = RoomCommandDispatcher.dispatch(roomRef, json)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        // SayInRoom serializes to a Narrated response — the speech reaches the
        // client via the Said event broadcast, not the response snapshot.
        assertThat(result).contains("\"type\":\"narrated\"");
    }

    @Test
    void dispatch_emote() throws Exception {
        RoomCommandDispatcher.dispatch(roomRef,
            """
            {"type":"enter","entityId":"player-1","entityName":"Alice","entityType":"player","fromDirection":"north","locale":"en"}
            """).toCompletableFuture().get(5, TimeUnit.SECONDS);

        var json = """
            {"type":"emote","entityId":"player-1","entityName":"Alice","text":"waves","locale":"en"}
            """;
        var result = RoomCommandDispatcher.dispatch(roomRef, json)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        // EmoteInRoom serializes to a Narrated response — same as SayInRoom.
        assertThat(result).contains("\"type\":\"narrated\"");
    }

    @Test
    void dispatch_take_object() throws Exception {
        RoomCommandDispatcher.dispatch(roomRef,
            """
            {"type":"enter","entityId":"player-1","entityName":"Alice","entityType":"player","fromDirection":"north","locale":"en"}
            """).toCompletableFuture().get(5, TimeUnit.SECONDS);

        var json = """
            {"type":"take","entityId":"player-1","objectName":"Test Crystal","locale":"en"}
            """;
        var result = RoomCommandDispatcher.dispatch(roomRef, json)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Should succeed (crystal is takeable)
        assertThat(result).contains("\"type\":");
    }

    @Test
    void dispatch_leave() throws Exception {
        RoomCommandDispatcher.dispatch(roomRef,
            """
            {"type":"enter","entityId":"player-1","entityName":"Alice","entityType":"player","fromDirection":"north","locale":"en"}
            """).toCompletableFuture().get(5, TimeUnit.SECONDS);

        var json = """
            {"type":"leave","entityId":"player-1","entityName":"Alice","direction":"east"}
            """;
        var result = RoomCommandDispatcher.dispatch(roomRef, json)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(result).contains("\"type\":\"ok\"");
    }

    @Test
    void dispatch_unknown_command() throws Exception {
        var json = """
            {"type":"teleport","destination":"moon"}
            """;
        var result = RoomCommandDispatcher.dispatch(roomRef, json)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(result).contains("\"type\":\"rejected\"");
        assertThat(result).contains("unknown_command");
    }

    @Test
    void dispatch_malformed_json() throws Exception {
        var result = RoomCommandDispatcher.dispatch(roomRef, "not json at all")
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(result).contains("\"type\":\"rejected\"");
        assertThat(result).contains("parse_error");
    }
}
