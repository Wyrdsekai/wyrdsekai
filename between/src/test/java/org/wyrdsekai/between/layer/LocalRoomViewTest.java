package org.wyrdsekai.between.layer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.topology.NodeAnnouncement;
import org.wyrdsekai.common.topology.NodeResources;
import org.wyrdsekai.common.topology.RoomAssignment;
import org.wyrdsekai.common.topology.RoomClaimMessage;
import org.wyrdsekai.common.topology.RoomOwnership;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for LocalRoomView — thread-safe local view of room-to-node assignments.
 */
class LocalRoomViewTest {

    private LocalRoomView view;

    @BeforeEach
    void setup() {
        view = new LocalRoomView();
    }

    private static NodeResources emptyResources() {
        return new NodeResources(0, 1024, List.of(), List.of(), 0.0, 50);
    }

    private static NodeAnnouncement announcement(String nodeId, List<RoomAssignment> rooms) {
        return new NodeAnnouncement(nodeId, "host-" + nodeId, List.of(), rooms,
            emptyResources(), Instant.now());
    }

    private static RoomAssignment room(String roomId, RoomOwnership ownership, String nodeId) {
        return new RoomAssignment(roomId, ownership, nodeId,
            null, null, 2, Instant.now(), Instant.now());
    }

    // --- updateFromAnnouncement ---

    @Nested
    class UpdateFromAnnouncement {

        @Test
        void adds_rooms_from_announcement() {
            var ann = announcement("node-1", List.of(
                room("nexus", RoomOwnership.SHARED, "node-1"),
                room("terminal", RoomOwnership.SHARED, "node-1")
            ));

            view.updateFromAnnouncement(ann);

            assertThat(view.size()).isEqualTo(2);
            var snap = view.snapshot();
            assertThat(snap.rooms()).containsKey("nexus");
            assertThat(snap.rooms()).containsKey("terminal");
            assertThat(snap.rooms().get("nexus").primaryNodeId()).isEqualTo("node-1");
        }

        @Test
        void updates_existing_rooms_from_announcement() {
            // First announcement: node-1 owns nexus
            view.updateFromAnnouncement(announcement("node-1", List.of(
                room("nexus", RoomOwnership.SHARED, "node-1")
            )));

            // Second announcement: node-2 claims nexus
            view.updateFromAnnouncement(announcement("node-2", List.of(
                room("nexus", RoomOwnership.SHARED, "node-2")
            )));

            var snap = view.snapshot();
            assertThat(snap.rooms().get("nexus").primaryNodeId()).isEqualTo("node-2");
        }
    }

    // --- claimRoom ---

    @Nested
    class ClaimRoom {

        @Test
        void succeeds_for_unclaimed_room() {
            var claim = new RoomClaimMessage(
                "nexus", "node-1", Instant.now(), RoomOwnership.SHARED, Instant.now());

            boolean accepted = view.claimRoom(claim);

            assertThat(accepted).isTrue();
            assertThat(view.snapshot().rooms().get("nexus").primaryNodeId()).isEqualTo("node-1");
        }

        @Test
        void higher_snapshot_timestamp_wins() {
            var older = Instant.now().minusSeconds(100);
            var newer = Instant.now();

            // node-1 claims first with older snapshot
            view.claimRoom(new RoomClaimMessage(
                "nexus", "node-1", older, RoomOwnership.SHARED, Instant.now()));

            // node-2 claims with newer snapshot — should win
            boolean accepted = view.claimRoom(new RoomClaimMessage(
                "nexus", "node-2", newer, RoomOwnership.SHARED, Instant.now()));

            assertThat(accepted).isTrue();
            assertThat(view.snapshot().rooms().get("nexus").primaryNodeId()).isEqualTo("node-2");
        }

        @Test
        void lower_snapshot_timestamp_loses() {
            var older = Instant.now().minusSeconds(100);
            var newer = Instant.now();

            // node-1 claims first with newer snapshot
            view.claimRoom(new RoomClaimMessage(
                "nexus", "node-1", newer, RoomOwnership.SHARED, Instant.now()));

            // node-2 claims with older snapshot — should lose
            boolean accepted = view.claimRoom(new RoomClaimMessage(
                "nexus", "node-2", older, RoomOwnership.SHARED, Instant.now()));

            assertThat(accepted).isFalse();
            assertThat(view.snapshot().rooms().get("nexus").primaryNodeId()).isEqualTo("node-1");
        }

        @Test
        void tie_broken_by_lower_nodeId() {
            var sameTs = Instant.now();

            // node-z claims first
            view.claimRoom(new RoomClaimMessage(
                "nexus", "node-z", sameTs, RoomOwnership.SHARED, Instant.now()));

            // node-a claims with same timestamp — should win (lower nodeId)
            boolean accepted = view.claimRoom(new RoomClaimMessage(
                "nexus", "node-a", sameTs, RoomOwnership.SHARED, Instant.now()));

            assertThat(accepted).isTrue();
            assertThat(view.snapshot().rooms().get("nexus").primaryNodeId()).isEqualTo("node-a");
        }

        @Test
        void same_node_reclaim_updates_timestamp() {
            var ts1 = Instant.now().minusSeconds(10);
            var ts2 = Instant.now();

            view.claimRoom(new RoomClaimMessage(
                "nexus", "node-1", ts1, RoomOwnership.SHARED, Instant.now()));

            boolean accepted = view.claimRoom(new RoomClaimMessage(
                "nexus", "node-1", ts2, RoomOwnership.SHARED, Instant.now()));

            assertThat(accepted).isTrue();
            assertThat(view.snapshot().rooms().get("nexus").lastSnapshotAt()).isEqualTo(ts2);
        }
    }

    // --- releaseRooms ---

    @Nested
    class ReleaseRooms {

        @Test
        void marks_rooms_as_orphaned() {
            view.updateFromAnnouncement(announcement("node-2", List.of(
                room("library", RoomOwnership.SHARED, "node-2"),
                room("garden", RoomOwnership.SHARED, "node-2")
            )));

            view.releaseRooms("node-2");

            var snap = view.snapshot();
            assertThat(snap.rooms().get("library").primaryNodeId()).isNull();
            assertThat(snap.rooms().get("garden").primaryNodeId()).isNull();
        }

        @Test
        void does_not_affect_other_nodes() {
            view.updateFromAnnouncement(announcement("node-1", List.of(
                room("nexus", RoomOwnership.SHARED, "node-1")
            )));
            view.updateFromAnnouncement(announcement("node-2", List.of(
                room("library", RoomOwnership.SHARED, "node-2")
            )));

            view.releaseRooms("node-2");

            assertThat(view.snapshot().rooms().get("nexus").primaryNodeId()).isEqualTo("node-1");
            assertThat(view.snapshot().rooms().get("library").primaryNodeId()).isNull();
        }
    }

    // --- orphanedSharedRooms ---

    @Nested
    class OrphanedSharedRooms {

        @Test
        void returns_only_shared_orphans_not_personal() {
            view.updateFromAnnouncement(announcement("node-2", List.of(
                room("shared-room", RoomOwnership.SHARED, "node-2"),
                room("personal-room", RoomOwnership.PERSONAL, "node-2"),
                room("agent-home", RoomOwnership.AGENT_HOME, "node-2")
            )));

            view.releaseRooms("node-2");

            var orphans = view.orphanedSharedRooms();
            var orphanIds = orphans.stream().map(LocalRoomView.RoomViewEntry::roomId).toList();

            // SHARED and AGENT_HOME both replicate, so both are returned
            assertThat(orphanIds).contains("shared-room", "agent-home");
            // PERSONAL does not replicate, so it's excluded
            assertThat(orphanIds).doesNotContain("personal-room");
        }
    }

    // --- shouldHostRoom ---

    @Nested
    class ShouldHostRoom {

        @Test
        void returns_true_when_unclaimed() {
            assertThat(view.shouldHostRoom("nexus", "node-1")).isTrue();
        }

        @Test
        void returns_false_when_claimed_by_other() {
            view.updateFromAnnouncement(announcement("node-2", List.of(
                room("nexus", RoomOwnership.SHARED, "node-2")
            )));

            assertThat(view.shouldHostRoom("nexus", "node-1")).isFalse();
        }

        @Test
        void returns_true_when_claimed_by_self() {
            view.updateFromAnnouncement(announcement("node-1", List.of(
                room("nexus", RoomOwnership.SHARED, "node-1")
            )));

            assertThat(view.shouldHostRoom("nexus", "node-1")).isTrue();
        }

        @Test
        void returns_true_when_orphaned() {
            view.updateFromAnnouncement(announcement("node-2", List.of(
                room("nexus", RoomOwnership.SHARED, "node-2")
            )));
            view.releaseRooms("node-2");

            assertThat(view.shouldHostRoom("nexus", "node-1")).isTrue();
        }
    }

    // --- isClaimedByOther ---

    @Nested
    class IsClaimedByOther {

        @Test
        void returns_true_when_claimed_by_different_node() {
            view.updateFromAnnouncement(announcement("node-2", List.of(
                room("nexus", RoomOwnership.SHARED, "node-2")
            )));

            assertThat(view.isClaimedByOther("nexus", "node-1")).isTrue();
        }

        @Test
        void returns_false_when_claimed_by_same_node() {
            view.updateFromAnnouncement(announcement("node-1", List.of(
                room("nexus", RoomOwnership.SHARED, "node-1")
            )));

            assertThat(view.isClaimedByOther("nexus", "node-1")).isFalse();
        }

        @Test
        void returns_false_when_unclaimed() {
            assertThat(view.isClaimedByOther("nexus", "node-1")).isFalse();
        }
    }

    // --- recordSnapshot ---

    @Nested
    class RecordSnapshot {

        @Test
        void updates_replica_map() {
            view.updateFromAnnouncement(announcement("node-1", List.of(
                room("nexus", RoomOwnership.SHARED, "node-1")
            )));

            var ts = Instant.now();
            view.recordSnapshot("nexus", "node-2", ts);

            var entry = view.snapshot().rooms().get("nexus");
            assertThat(entry.replicas()).containsKey("node-2");
            assertThat(entry.replicas().get("node-2")).isEqualTo(ts);
        }

        @Test
        void no_op_for_unknown_room() {
            // Should not throw
            view.recordSnapshot("nonexistent", "node-2", Instant.now());
            assertThat(view.size()).isZero();
        }
    }

    // --- snapshot ---

    @Nested
    class SnapshotTests {

        @Test
        void returns_immutable_copy() {
            view.updateFromAnnouncement(announcement("node-1", List.of(
                room("nexus", RoomOwnership.SHARED, "node-1")
            )));

            var snap = view.snapshot();
            assertThat(snap.rooms()).hasSize(1);

            // Mutating the view after snapshot should not affect the snapshot
            view.updateFromAnnouncement(announcement("node-2", List.of(
                room("library", RoomOwnership.SHARED, "node-2")
            )));

            assertThat(snap.rooms()).hasSize(1); // still 1
            assertThat(view.snapshot().rooms()).hasSize(2); // new snapshot has 2
        }
    }

    // --- describe ---

    @Nested
    class Describe {

        @Test
        void returns_non_empty_string_when_rooms_exist() {
            view.updateFromAnnouncement(announcement("node-1", List.of(
                room("nexus", RoomOwnership.SHARED, "node-1")
            )));

            var desc = view.describe();
            assertThat(desc).isNotEmpty();
            assertThat(desc).contains("nexus");
            assertThat(desc).contains("SHARED");
            assertThat(desc).contains("node-1");
        }

        @Test
        void returns_non_empty_string_when_empty() {
            var desc = view.describe();
            assertThat(desc).isNotEmpty();
            assertThat(desc).contains("empty");
        }
    }

    // --- concurrency ---

    @Nested
    class Concurrency {

        @Test
        void concurrent_updates_do_not_crash() throws Exception {
            int threads = 8;
            int opsPerThread = 100;
            var latch = new CountDownLatch(threads);

            ExecutorService executor = Executors.newFixedThreadPool(threads);
            try {
                for (int t = 0; t < threads; t++) {
                    final int threadId = t;
                    executor.submit(() -> {
                        try {
                            for (int i = 0; i < opsPerThread; i++) {
                                var nodeId = "node-" + threadId;
                                var roomId = "room-" + threadId + "-" + i;

                                // Mix of operations
                                view.updateFromAnnouncement(announcement(nodeId, List.of(
                                    room(roomId, RoomOwnership.SHARED, nodeId)
                                )));
                                view.claimRoom(new RoomClaimMessage(
                                    roomId, nodeId, Instant.now(),
                                    RoomOwnership.SHARED, Instant.now()));
                                view.shouldHostRoom(roomId, nodeId);
                                view.isClaimedByOther(roomId, "other-node");
                                view.snapshot();
                                view.describe();
                                view.recordSnapshot(roomId, "replica-node", Instant.now());
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                latch.await();

                // Should not have thrown — just verify the view is in a consistent state
                var snap = view.snapshot();
                assertThat(snap.rooms()).isNotNull();
                assertThat(view.describe()).isNotEmpty();
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
