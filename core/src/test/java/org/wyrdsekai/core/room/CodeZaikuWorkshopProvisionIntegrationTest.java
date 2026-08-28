package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * integration test for {@link
 * ZoneGuardian.ProvisionCodeZaikuWorkshop} through the real actor mailbox.
 *
 * <p>The unit test ({@link WorkshopProvisionerTest}) covers the seed-shape
 * assertions; this integration test covers the actor wiring — that
 * sending the command actually spawns a RoomActor reachable from the
 * registry with the expected aliases, name, description, and furnishings.
 * Mirrors {@link ZoneGuardianDeferredSeedingTest}'s testkit pattern.</p>
 */
@Tag("integration")
class CodeZaikuWorkshopProvisionIntegrationTest {

    private static ActorTestKit testKit;

    @BeforeAll
    static void setup() {
        var config = ConfigFactory.parseString("""
            pekko.actor.provider = local
            pekko.persistence.journal.plugin = "pekko.persistence.journal.inmem"
            pekko.persistence.snapshot-store.plugin = "pekko.persistence.snapshot-store.local"
            pekko.persistence.snapshot-store.local.dir = "target/test-snapshots-cp-workshop"
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

    @Test void provisionCodeZaikuWorkshop_spawns_room_actor() throws InterruptedException {
        var guardian = testKit.spawn(
            ZoneGuardian.create(null, List.of(), null, null));

        // Let the empty foundation seed window close (deferred seeding fires
        // after the 3s timeout for an empty seed list).
        Thread.sleep(3500);

        // Workshop provisioning is an on-demand path the same way ProvisionStudy is.
        guardian.tell(new ZoneGuardian.ProvisionCodeZaikuWorkshop("u-operator", "Operator"));

        // Wait briefly for async seedRoom() to complete.
        Thread.sleep(800);

        var roomId = WorkshopProvisioner.workshopRoomId("u-operator");
        ActorRef<RoomCommand> roomRef = RoomRegistry.get().ref(roomId);
        assertThat(roomRef)
            .as("Workshop room actor must be registered post-provision")
            .isNotNull();

        var probe = testKit.createTestProbe(RoomSnapshot.class);
        roomRef.tell(new RoomCommand.GetSnapshot(probe.getRef()));
        var snap = probe.receiveMessage(Duration.ofSeconds(5));

        assertThat(snap.name()).isEqualTo("Operator's CodeZaiku Workshop");
        assertThat(snap.aliases())
            .contains("workshop", "codezaiku", "code", "studio");
        assertThat(snap.description())
            .contains("workbench")
            .contains("library")
            .contains("Chronicle")
            .contains("Forge");
        var objectIds = snap.objects().stream().map(o -> o.id()).toList();
        assertThat(objectIds).contains(
            "workshop-bench",
            "workshop-library-shelves",
            "workshop-chronicle-stone",
            "workshop-portal-rack",
            "workshop-forge-link",
            "workshop-familiar-perch");
    }

    @Test void provisionCodeZaikuWorkshop_idempotent() throws InterruptedException {
        var guardian = testKit.spawn(
            ZoneGuardian.create(null, List.of(), null, null));
        Thread.sleep(3500);

        // Two consecutive provisions for the same bondholder must converge on
        // a single live room actor — the journal makes seedRoom() idempotent.
        guardian.tell(new ZoneGuardian.ProvisionCodeZaikuWorkshop("u-idem", "Idem"));
        Thread.sleep(500);
        guardian.tell(new ZoneGuardian.ProvisionCodeZaikuWorkshop("u-idem", "Idem"));
        Thread.sleep(500);

        var roomId = WorkshopProvisioner.workshopRoomId("u-idem");
        ActorRef<RoomCommand> roomRef = RoomRegistry.get().ref(roomId);
        assertThat(roomRef).isNotNull();

        // Verify the room is still reachable + functional after re-provision.
        var probe = testKit.createTestProbe(RoomSnapshot.class);
        roomRef.tell(new RoomCommand.GetSnapshot(probe.getRef()));
        var snap = probe.receiveMessage(Duration.ofSeconds(5));
        assertThat(snap.name()).isEqualTo("Idem's CodeZaiku Workshop");
    }

    @Test void provisionCodeZaikuWorkshop_multi_bondholder_distinct_rooms()
            throws InterruptedException {
        var guardian = testKit.spawn(
            ZoneGuardian.create(null, List.of(), null, null));
        Thread.sleep(3500);

        // §2.3 multi-bondholder: each bondholder's workshop is a distinct room.
        guardian.tell(new ZoneGuardian.ProvisionCodeZaikuWorkshop("user-a", "Alice"));
        guardian.tell(new ZoneGuardian.ProvisionCodeZaikuWorkshop("user-b", "Bob"));
        Thread.sleep(800);

        var roomA = RoomRegistry.get().ref(WorkshopProvisioner.workshopRoomId("user-a"));
        var roomB = RoomRegistry.get().ref(WorkshopProvisioner.workshopRoomId("user-b"));
        assertThat(roomA).isNotNull();
        assertThat(roomB).isNotNull();
        assertThat(roomA).isNotSameAs(roomB);

        var probe = testKit.createTestProbe(RoomSnapshot.class);
        roomA.tell(new RoomCommand.GetSnapshot(probe.getRef()));
        assertThat(probe.receiveMessage(Duration.ofSeconds(5)).name())
            .isEqualTo("Alice's CodeZaiku Workshop");
        roomB.tell(new RoomCommand.GetSnapshot(probe.getRef()));
        assertThat(probe.receiveMessage(Duration.ofSeconds(5)).name())
            .isEqualTo("Bob's CodeZaiku Workshop");
    }
}
