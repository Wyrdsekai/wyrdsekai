package org.wyrdsekai.core.room;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for RoomActor event sourcing — recovery and persistence.
 * Previously tested multi-replica convergence (§82) but now verifies single-writer
 * EventSourcedBehavior correctness after the refactor from ReplicatedEventSourcedBehavior.
 */
@Tag("integration")
class RoomActorReplicationTest {

    static ActorTestKit testKit;

    @BeforeAll
    static void setup() {
        testKit = ActorTestKit.create(EventSourcedBehaviorTestKit.config());
    }

    @AfterAll
    static void teardown() {
        testKit.shutdownTestKit();
    }

    @Test
    void room_persists_and_recovers_entities() {
        var probe = testKit.createTestProbe(RoomResponse.class);

        var actor = testKit.spawn(RoomActor.create("rep-room-1"), "rep-room-1-v1");

        // Create room
        actor.tell(new RoomCommand.CreateRoom("Replicated Room", "A test room.", "test",
            List.of(), List.of(), probe.ref()));
        var reply = probe.receiveMessage(Duration.ofSeconds(3));
        assertThat(reply).isInstanceOf(RoomResponse.Ok.class);

        // Enter two players
        actor.tell(new RoomCommand.EnterRoom("player-1", "Alice", "player", "north", probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        actor.tell(new RoomCommand.EnterRoom("player-2", "Bob", "player", "south", probe.ref()));
        var enterReply = probe.receiveMessage(Duration.ofSeconds(3));
        assertThat(enterReply).isInstanceOf(RoomResponse.Ok.class);

        // Verify both entities present
        var ok = (RoomResponse.Ok) enterReply;
        assertThat(ok.snapshot().entities()).anyMatch(e -> e.name().equals("Alice"));
        assertThat(ok.snapshot().entities()).anyMatch(e -> e.name().equals("Bob"));
    }

    @Test
    void room_persists_objects_and_take_operations() {
        var probe = testKit.createTestProbe(RoomResponse.class);

        var actor = testKit.spawn(RoomActor.create("rep-room-2"), "rep-room-2-v1");

        // Create room with two objects
        actor.tell(new RoomCommand.CreateRoom("Object Room", "Has items.", "test",
            List.of(),
            List.of(
                new RoomObject("key", "Golden Key", "An ornate key.", true),
                new RoomObject("gem", "Ruby", "A red gem.", true)
            ), probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        // Take key
        actor.tell(new RoomCommand.TakeObject("player-1", "Golden Key", probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        // Take gem
        actor.tell(new RoomCommand.TakeObject("player-2", "Ruby", probe.ref()));
        var reply = probe.receiveMessage(Duration.ofSeconds(3));

        // Both objects should be gone
        assertThat(reply).isInstanceOf(RoomResponse.ObjectTakenOk.class);
        var ok = (RoomResponse.ObjectTakenOk) reply;
        assertThat(ok.snapshot().objects()).isEmpty();
    }

    @Test
    void room_recovers_after_restart() throws Exception {
        var probe = testKit.createTestProbe(RoomResponse.class);

        var actor = testKit.spawn(RoomActor.create("rep-room-3"), "rep-room-3-a1");

        // Create room and add entity
        actor.tell(new RoomCommand.CreateRoom("Recovery Room", "Tests recovery.", "test",
            List.of(new Exit("east", "hall", "The Hall")),
            List.of(), probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        actor.tell(new RoomCommand.EnterRoom("player-1", "Alice", "player", "west", probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        // Stop actor
        testKit.stop(actor);
        Thread.sleep(500);

        // Restart — should recover from journal
        var actor2 = testKit.spawn(RoomActor.create("rep-room-3"), "rep-room-3-a2");

        actor2.tell(new RoomCommand.LookRoom("test", probe.ref()));
        var reply = probe.receiveMessage(Duration.ofSeconds(3));
        assertThat(reply).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) reply;
        assertThat(ok.snapshot().name()).isEqualTo("Recovery Room");
        assertThat(ok.snapshot().exits()).hasSize(1);
        assertThat(ok.snapshot().entities()).anyMatch(e -> e.name().equals("Alice"));
    }

    @Test
    void sequential_say_events_persist_correctly() {
        var probe = testKit.createTestProbe(RoomResponse.class);

        var actor = testKit.spawn(RoomActor.create("rep-room-4"), "rep-room-4-v1");

        // Create room
        actor.tell(new RoomCommand.CreateRoom("Chat Room", "Talk here.", "test",
            List.of(), List.of(), probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        // Enter two players
        actor.tell(new RoomCommand.EnterRoom("player-1", "Alice", "player", "north", probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        actor.tell(new RoomCommand.EnterRoom("player-2", "Bob", "player", "south", probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        // Say from both players
        actor.tell(new RoomCommand.SayInRoom("player-1", "Alice", "Hello from Alice!", probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        actor.tell(new RoomCommand.SayInRoom("player-2", "Bob", "Hello from Bob!", probe.ref()));
        var reply = probe.receiveMessage(Duration.ofSeconds(3));

        // SayInRoom replies Narrated (no snapshot) so the speech doesn't
        // trigger a client-side room redraw. Fetch the snapshot explicitly
        // via LookRoom to verify both players are still resident.
        assertThat(reply).isInstanceOf(RoomResponse.Narrated.class);

        actor.tell(new RoomCommand.LookRoom("player-1", probe.ref()));
        var look = probe.receiveMessage(Duration.ofSeconds(3));
        assertThat(look).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) look;
        assertThat(ok.snapshot().entities()).hasSize(2);
    }

    @Test
    void multiple_exits_persist_correctly() {
        var probe = testKit.createTestProbe(RoomResponse.class);

        var actor = testKit.spawn(RoomActor.create("rep-room-5"), "rep-room-5-v1");

        // Create room
        actor.tell(new RoomCommand.CreateRoom("Hub Room", "Central hub.", "test",
            List.of(), List.of(), probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        // Add exits
        actor.tell(new RoomCommand.AddExit("north", "tower", "The Tower", probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        actor.tell(new RoomCommand.AddExit("south", "dungeon", "The Dungeon", probe.ref()));
        var reply = probe.receiveMessage(Duration.ofSeconds(3));

        // Both exits should be present
        assertThat(reply).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) reply;
        assertThat(ok.snapshot().exits()).hasSize(2);
    }
}
