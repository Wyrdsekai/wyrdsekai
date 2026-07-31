package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for RoomCommand.GetSnapshot — read-only snapshot query for Between room replication.
 */
@Tag("integration")
class RoomActorGetSnapshotTest {

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

    private EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> behaviorTestKit;

    @BeforeEach void setUp() {
        behaviorTestKit = EventSourcedBehaviorTestKit.create(
            testKit.system(), RoomActor.create("test-room"));
    }

    @AfterAll static void tearDown() {
        testKit.shutdownTestKit();
    }

    @Test void get_snapshot_returns_current_room_state() {
        // Create a room first
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom(
                "The Nexus", "A shimmering hub.", "foundation",
                List.of(new Exit("east", "terminal", "The Terminal")),
                List.of(new RoomObject("crystal", "Nexus Crystal", "Glowing.", false)),
                ref));

        // Get snapshot (using ActorTestKit probe since GetSnapshot uses RoomSnapshot, not RoomResponse)
        var probe = testKit.createTestProbe(RoomSnapshot.class);
        behaviorTestKit.runCommand(new RoomCommand.GetSnapshot(probe.getRef()));

        var snapshot = probe.receiveMessage();
        assertThat(snapshot.name()).isEqualTo("The Nexus");
        assertThat(snapshot.description()).isEqualTo("A shimmering hub.");
        assertThat(snapshot.zone()).isEqualTo("foundation");
        assertThat(snapshot.exits()).hasSize(1);
        assertThat(snapshot.objects()).hasSize(1);
    }

    @Test void get_snapshot_on_empty_room_returns_valid_snapshot() {
        // GetSnapshot on an uninitialized room should return empty but valid snapshot
        var probe = testKit.createTestProbe(RoomSnapshot.class);
        behaviorTestKit.runCommand(new RoomCommand.GetSnapshot(probe.getRef()));

        var snapshot = probe.receiveMessage();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.roomId()).isEqualTo("test-room");
        assertThat(snapshot.name()).isEmpty();
        assertThat(snapshot.exits()).isEmpty();
        assertThat(snapshot.objects()).isEmpty();
    }

    @Test void get_snapshot_does_not_persist_events() {
        // Create room
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom(
                "Test Room", "A room.", "test",
                List.of(), List.of(), ref));

        // Count events after creation
        int eventsAfterCreate = behaviorTestKit.persistenceTestKit()
            .persistedInStorage("Room|test-room|local").size();

        // GetSnapshot should NOT produce any new events
        var probe = testKit.createTestProbe(RoomSnapshot.class);
        behaviorTestKit.runCommand(new RoomCommand.GetSnapshot(probe.getRef()));
        probe.receiveMessage();

        int eventsAfterSnapshot = behaviorTestKit.persistenceTestKit()
            .persistedInStorage("Room|test-room|local").size();

        assertThat(eventsAfterSnapshot).isEqualTo(eventsAfterCreate);
    }
}
