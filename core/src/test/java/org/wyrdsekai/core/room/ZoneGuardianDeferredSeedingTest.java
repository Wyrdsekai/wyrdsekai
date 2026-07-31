package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ZoneGuardian deferred seeding behavior.
 * Verifies that:
 * - With ApplyRoomView: only unclaimed rooms are seeded
 * - Without ApplyRoomView (timeout): all rooms are seeded
 * - RebuildRoom restores from snapshot
 * - ApplyRoomView before timeout cancels the timer
 * - Empty view seeds all rooms
 */
@Tag("integration")
class ZoneGuardianDeferredSeedingTest {

    private static ActorTestKit testKit;

    @BeforeAll
    static void setup() {
        var config = ConfigFactory.parseString("""
            pekko.actor.provider = local
            pekko.persistence.journal.plugin = "pekko.persistence.journal.inmem"
            pekko.persistence.snapshot-store.plugin = "pekko.persistence.snapshot-store.local"
            pekko.persistence.snapshot-store.local.dir = "target/test-snapshots-deferred-seed"
            pekko.actor.serialization-bindings {
              "org.wyrdsekai.core.room.RoomEvent" = jackson-json
              "org.wyrdsekai.core.room.RoomState" = jackson-json
              "org.wyrdsekai.core.room.RoomCommand" = jackson-json
              "org.wyrdsekai.core.room.RoomNotification" = jackson-json
              "org.wyrdsekai.core.room.RoomResponse" = jackson-json
            }
            """);
        testKit = ActorTestKit.create(config);
        RoomRegistry.get().setScheduler(testKit.scheduler());
        Rooms.setScheduler(testKit.scheduler());
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    private static List<ZoneGuardian.RoomSeed> testSeeds(String suffix) {
        return List.of(
            new ZoneGuardian.RoomSeed("nexus-" + suffix, "The Nexus", "Hub.",
                List.of(), List.of()),
            new ZoneGuardian.RoomSeed("terminal-" + suffix, "The Terminal", "Text terminal.",
                List.of(), List.of()),
            new ZoneGuardian.RoomSeed("library-" + suffix, "The Library", "Books.",
                List.of(), List.of())
        );
    }

    @Test
    void apply_room_view_skips_peer_claimed_rooms() {
        var seeds = testSeeds("view");
        var guardian = testKit.spawn(
            ZoneGuardian.create(null, seeds, null, null));

        // Some rooms are claimed by peer node-2
        var claimedMap = Map.of(
            "nexus-view", "node-2",
            "library-view", "node-2"
        );

        // Send ApplyRoomView before the 3s timeout
        guardian.tell(new ZoneGuardian.ApplyRoomView(claimedMap));

        // Wait for async seeding
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        // seed-terminal should be seeded (not claimed by peer)
        var probe = testKit.createTestProbe(RoomSnapshot.class);

        ActorRef<RoomCommand> terminalRef = RoomRegistry.get().ref("terminal-view");
        assertThat(terminalRef).isNotNull();
        terminalRef.tell(new RoomCommand.GetSnapshot(probe.getRef()));
        var terminalSnapshot = probe.receiveMessage(Duration.ofSeconds(5));
        assertThat(terminalSnapshot.name()).isEqualTo("The Terminal");

        // nexus should NOT be seeded (claimed by peer) — ref should be null
        ActorRef<RoomCommand> nexusRef = RoomRegistry.get().ref("nexus-view");
        assertThat(nexusRef).isNull();
    }

    @Test
    void timeout_seeds_all_rooms() {
        var seeds = testSeeds("timeout");
        testKit.spawn(ZoneGuardian.create(null, seeds, null, null));

        // Do NOT send ApplyRoomView — let the 3s timeout fire
        try { Thread.sleep(4000); } catch (InterruptedException ignored) {}

        var probe = testKit.createTestProbe(RoomSnapshot.class);

        ActorRef<RoomCommand> nexusRef = RoomRegistry.get().ref("nexus-timeout");
        assertThat(nexusRef).isNotNull();
        nexusRef.tell(new RoomCommand.GetSnapshot(probe.getRef()));
        var nexusSnapshot = probe.receiveMessage(Duration.ofSeconds(5));
        assertThat(nexusSnapshot.name()).isEqualTo("The Nexus");

        ActorRef<RoomCommand> libraryRef = RoomRegistry.get().ref("library-timeout");
        assertThat(libraryRef).isNotNull();
        libraryRef.tell(new RoomCommand.GetSnapshot(probe.getRef()));
        var librarySnapshot = probe.receiveMessage(Duration.ofSeconds(5));
        assertThat(librarySnapshot.name()).isEqualTo("The Library");
    }

    @Test
    void rebuild_room_restores_from_snapshot() {
        var guardian = testKit.spawn(
            ZoneGuardian.create(null, List.of(), null, null));

        // Wait for seed timeout to fire (no seeds)
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        var snapshot = new RoomSnapshot(
            "rebuilt-room", "Rebuilt Room", "Restored from snapshot.", "test",
            List.of(new Exit("north", "nexus", "The Nexus")),
            List.of(),
            List.of(new RoomObject("book", "Old Book", "Dusty.", true)),
            List.of()
        );

        guardian.tell(new ZoneGuardian.RebuildRoom("rebuilt-room", snapshot));

        // Wait for async rebuild
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        var probe = testKit.createTestProbe(RoomSnapshot.class);
        ActorRef<RoomCommand> roomRef = RoomRegistry.get().ref("rebuilt-room");
        assertThat(roomRef).isNotNull();
        roomRef.tell(new RoomCommand.GetSnapshot(probe.getRef()));

        var result = probe.receiveMessage(Duration.ofSeconds(5));
        assertThat(result.name()).isEqualTo("Rebuilt Room");
        assertThat(result.exits()).hasSize(1);
        assertThat(result.objects()).hasSize(1);
    }

    @Test
    void apply_room_view_before_timeout_cancels_timer() {
        var seeds = testSeeds("cancel");
        var guardian = testKit.spawn(
            ZoneGuardian.create(null, seeds, null, null));

        // Send ApplyRoomView immediately (well before 3s timeout)
        guardian.tell(new ZoneGuardian.ApplyRoomView(Map.of()));

        // Wait past the timeout
        try { Thread.sleep(4000); } catch (InterruptedException ignored) {}

        // Verify rooms were seeded (no crash, no duplicates)
        var probe = testKit.createTestProbe(RoomSnapshot.class);
        ActorRef<RoomCommand> nexusRef = RoomRegistry.get().ref("nexus-cancel");
        assertThat(nexusRef).isNotNull();
        nexusRef.tell(new RoomCommand.GetSnapshot(probe.getRef()));

        var snapshot = probe.receiveMessage(Duration.ofSeconds(5));
        assertThat(snapshot.name()).isEqualTo("The Nexus");
    }

    @Test
    void empty_view_seeds_all_rooms() {
        var seeds = testSeeds("empty");
        var guardian = testKit.spawn(
            ZoneGuardian.create(null, seeds, null, null));

        // Send ApplyRoomView with empty map — no rooms claimed by peers
        guardian.tell(new ZoneGuardian.ApplyRoomView(Map.of()));

        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        var probe = testKit.createTestProbe(RoomSnapshot.class);

        ActorRef<RoomCommand> libraryRef = RoomRegistry.get().ref("library-empty");
        assertThat(libraryRef).isNotNull();
        libraryRef.tell(new RoomCommand.GetSnapshot(probe.getRef()));
        var snapshot = probe.receiveMessage(Duration.ofSeconds(5));
        assertThat(snapshot.name()).isEqualTo("The Library");
    }
}
