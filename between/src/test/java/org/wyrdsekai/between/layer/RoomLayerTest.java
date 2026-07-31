package org.wyrdsekai.between.layer;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.topology.NodeAnnouncement;
import org.wyrdsekai.common.topology.NodeResources;
import org.wyrdsekai.common.topology.RoomAssignment;
import org.wyrdsekai.common.topology.RoomClaimMessage;
import org.wyrdsekai.common.topology.RoomOwnership;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoomLayerTest {

    private static ActorTestKit testKit;

    @BeforeAll
    static void setup() {
        testKit = ActorTestKit.create();
    }

    @AfterAll
    static void teardown() {
        testKit.shutdownTestKit();
    }

    private static NodeResources emptyResources() {
        return new NodeResources(0, 1024, List.of(), List.of(), 0.0, 50);
    }

    @Nested
    class AnnouncementTests {

        @Test
        void announcement_tick_updates_view_with_local_rooms() {
            var layer = testKit.spawn(RoomLayer.create());
            var probe = testKit.createTestProbe(LocalRoomView.Snapshot.class);

            var localRooms = List.of(
                new RoomAssignment("nexus", RoomOwnership.SHARED, "node-1",
                    null, null, 2, Instant.now(), Instant.now()),
                new RoomAssignment("terminal", RoomOwnership.SHARED, "node-1",
                    null, null, 2, Instant.now(), Instant.now())
            );

            // Start with no NATS (single-node mode) — should not crash
            layer.tell(new RoomLayer.Start(null, "home", "node-1", () -> localRooms,
                Duration.ofSeconds(60), Duration.ofSeconds(120)));

            // Wait a moment for the initial announcement to process
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}

            layer.tell(new RoomLayer.GetView(probe.getRef()));
            var snapshot = probe.receiveMessage(Duration.ofSeconds(3));

            // The local rooms should be in the view even without NATS
            assertThat(snapshot.rooms()).containsKey("nexus");
            assertThat(snapshot.rooms()).containsKey("terminal");
        }

        @Test
        void inbound_announcement_updates_view() {
            var layer = testKit.spawn(RoomLayer.create());
            var probe = testKit.createTestProbe(LocalRoomView.Snapshot.class);

            layer.tell(new RoomLayer.Start(null, "home", "node-1", List::of,
                Duration.ofSeconds(60), Duration.ofSeconds(120)));

            // Simulate announcement from peer node-2
            var peerRooms = List.of(
                new RoomAssignment("nexus", RoomOwnership.SHARED, "node-2",
                    null, null, 2, Instant.now(), Instant.now())
            );
            var announcement = new NodeAnnouncement(
                "node-2", "peer-host", List.of(), peerRooms, emptyResources(), Instant.now());

            layer.tell(new RoomLayer.AnnouncementReceived(announcement));
            layer.tell(new RoomLayer.GetView(probe.getRef()));

            var snapshot = probe.receiveMessage(Duration.ofSeconds(3));
            assertThat(snapshot.rooms()).containsKey("nexus");
            assertThat(snapshot.rooms().get("nexus").primaryNodeId()).isEqualTo("node-2");
        }
    }

    @Nested
    class PeerTimeoutTests {

        @Test
        void peer_timeout_releases_rooms_and_claims_orphans() {
            var layer = testKit.spawn(RoomLayer.create());
            var probe = testKit.createTestProbe(LocalRoomView.Snapshot.class);

            layer.tell(new RoomLayer.Start(null, "home", "node-1", List::of,
                Duration.ofSeconds(60), Duration.ofSeconds(120)));

            // Add peer rooms
            var peerRooms = List.of(
                new RoomAssignment("library", RoomOwnership.SHARED, "node-2",
                    null, null, 2, Instant.now(), Instant.now()),
                new RoomAssignment("garden", RoomOwnership.SHARED, "node-2",
                    null, null, 2, Instant.now(), Instant.now())
            );
            layer.tell(new RoomLayer.AnnouncementReceived(
                new NodeAnnouncement("node-2", "peer", List.of(), peerRooms,
                    emptyResources(), Instant.now())));

            // Peer times out
            layer.tell(new RoomLayer.PeerTimedOut("node-2"));

            layer.tell(new RoomLayer.GetView(probe.getRef()));
            var snapshot = probe.receiveMessage(Duration.ofSeconds(3));

            // Rooms should now be claimed by node-1 (this node claimed orphans)
            assertThat(snapshot.rooms()).containsKey("library");
            assertThat(snapshot.rooms().get("library").primaryNodeId()).isEqualTo("node-1");
            assertThat(snapshot.rooms()).containsKey("garden");
            assertThat(snapshot.rooms().get("garden").primaryNodeId()).isEqualTo("node-1");
        }
    }

    @Nested
    class ClaimTests {

        @Test
        void claim_conflict_resolved_by_timestamp() {
            var layer = testKit.spawn(RoomLayer.create());
            var probe = testKit.createTestProbe(LocalRoomView.Snapshot.class);

            layer.tell(new RoomLayer.Start(null, "home", "node-1", List::of,
                Duration.ofSeconds(60), Duration.ofSeconds(120)));

            var olderTimestamp = Instant.now().minusSeconds(100);
            var newerTimestamp = Instant.now();

            // First claim: node-2 with older snapshot
            layer.tell(new RoomLayer.ClaimReceived(
                new RoomClaimMessage("nexus", "node-2", olderTimestamp,
                    RoomOwnership.SHARED, Instant.now())));

            // Second claim: node-3 with newer snapshot — should win
            layer.tell(new RoomLayer.ClaimReceived(
                new RoomClaimMessage("nexus", "node-3", newerTimestamp,
                    RoomOwnership.SHARED, Instant.now())));

            layer.tell(new RoomLayer.GetView(probe.getRef()));
            var snapshot = probe.receiveMessage(Duration.ofSeconds(3));

            assertThat(snapshot.rooms().get("nexus").primaryNodeId()).isEqualTo("node-3");
        }

        @Test
        void claim_tie_broken_by_lexicographic_node_id() {
            var layer = testKit.spawn(RoomLayer.create());
            var probe = testKit.createTestProbe(LocalRoomView.Snapshot.class);

            layer.tell(new RoomLayer.Start(null, "home", "node-1", List::of,
                Duration.ofSeconds(60), Duration.ofSeconds(120)));

            var sameTimestamp = Instant.now();

            // Two claims with same timestamp — lower nodeId wins
            layer.tell(new RoomLayer.ClaimReceived(
                new RoomClaimMessage("nexus", "node-z", sameTimestamp,
                    RoomOwnership.SHARED, Instant.now())));

            layer.tell(new RoomLayer.ClaimReceived(
                new RoomClaimMessage("nexus", "node-a", sameTimestamp,
                    RoomOwnership.SHARED, Instant.now())));

            layer.tell(new RoomLayer.GetView(probe.getRef()));
            var snapshot = probe.receiveMessage(Duration.ofSeconds(3));

            assertThat(snapshot.rooms().get("nexus").primaryNodeId()).isEqualTo("node-a");
        }
    }

    @Nested
    class ViewTests {

        @Test
        void get_view_returns_current_state() {
            var layer = testKit.spawn(RoomLayer.create());
            var probe = testKit.createTestProbe(LocalRoomView.Snapshot.class);

            layer.tell(new RoomLayer.Start(null, "home", "node-1", List::of,
                Duration.ofSeconds(60), Duration.ofSeconds(120)));

            layer.tell(new RoomLayer.GetView(probe.getRef()));
            var snapshot = probe.receiveMessage(Duration.ofSeconds(3));

            assertThat(snapshot).isNotNull();
            assertThat(snapshot.rooms()).isNotNull();
        }

        @Test
        void get_view_before_start_returns_empty() {
            var layer = testKit.spawn(RoomLayer.create());
            var probe = testKit.createTestProbe(LocalRoomView.Snapshot.class);

            // Query before Start
            layer.tell(new RoomLayer.GetView(probe.getRef()));
            var snapshot = probe.receiveMessage(Duration.ofSeconds(3));

            assertThat(snapshot.rooms()).isEmpty();
        }
    }

    @Nested
    class SingleNodeTests {

        @Test
        void single_node_mode_no_nats_does_not_crash() {
            var layer = testKit.spawn(RoomLayer.create());
            var probe = testKit.createTestProbe(LocalRoomView.Snapshot.class);

            var localRooms = List.of(
                new RoomAssignment("nexus", RoomOwnership.SHARED, "solo-node",
                    null, null, 1, Instant.now(), Instant.now())
            );

            // Start with null NATS — single node mode
            layer.tell(new RoomLayer.Start(null, "home", "solo-node", () -> localRooms,
                Duration.ofSeconds(60), Duration.ofSeconds(120)));

            // Should still work for local view
            layer.tell(new RoomLayer.GetView(probe.getRef()));
            var snapshot = probe.receiveMessage(Duration.ofSeconds(3));
            assertThat(snapshot.rooms()).containsKey("nexus");
        }
    }

    @Nested
    class SnapshotReplicationTests {

        @Test
        void snapshot_received_records_in_view() {
            var layer = testKit.spawn(RoomLayer.create());
            var probe = testKit.createTestProbe(LocalRoomView.Snapshot.class);

            layer.tell(new RoomLayer.Start(null, "home", "node-1", List::of,
                Duration.ofSeconds(60), Duration.ofSeconds(120)));

            // First, add the room via announcement so recordSnapshot has something to update
            var peerRooms = List.of(
                new RoomAssignment("nexus", RoomOwnership.SHARED, "node-2",
                    null, null, 2, Instant.now(), Instant.now())
            );
            layer.tell(new RoomLayer.AnnouncementReceived(
                new NodeAnnouncement("node-2", "peer", List.of(), peerRooms,
                    emptyResources(), Instant.now())));

            var snapshotTs = Instant.now();
            var data = "snapshot-data".getBytes();
            layer.tell(new RoomLayer.SnapshotReceived("nexus", "node-3", data, snapshotTs));

            layer.tell(new RoomLayer.GetView(probe.getRef()));
            var snapshot = probe.receiveMessage(Duration.ofSeconds(3));
            assertThat(snapshot.rooms()).containsKey("nexus");
            // The replica should be recorded
            assertThat(snapshot.rooms().get("nexus").replicas()).containsKey("node-3");
        }
    }
}
