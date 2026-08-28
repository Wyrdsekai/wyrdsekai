package org.wyrdsekai.between.layer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.topology.ReplicationTier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for RoomEventReplicator — event publication for event-sourced and write-through rooms.
 */
class RoomEventReplicatorTest {

    private RoomEventReplicator replicator;

    @BeforeEach
    void setup() {
        // null NATS — single-node mode
        replicator = new RoomEventReplicator(null, "test-zone");
    }

    private static WorldEvent sampleEvent(String roomId) {
        return new WorldEvent.Said(roomId, Instant.now(), "player-1", "Operator", "Hello");
    }

    @Nested
    class EventSourcingMode {

        @Test
        void enabled_room_tracks_as_event_sourced() {
            replicator.enableEventSourcing("nexus");

            assertThat(replicator.isEventSourced("nexus")).isTrue();
            assertThat(replicator.isWriteThrough("nexus")).isFalse();
            assertThat(replicator.eventSourcedCount()).isEqualTo(1);
        }

        @Test
        void disabled_room_is_not_event_sourced() {
            replicator.enableEventSourcing("nexus");
            replicator.disableReplication("nexus");

            assertThat(replicator.isEventSourced("nexus")).isFalse();
            assertThat(replicator.eventSourcedCount()).isZero();
        }

        @Test
        void event_sourcing_disables_write_through() {
            replicator.enableWriteThrough("nexus");
            assertThat(replicator.isWriteThrough("nexus")).isTrue();

            replicator.enableEventSourcing("nexus");
            assertThat(replicator.isEventSourced("nexus")).isTrue();
            assertThat(replicator.isWriteThrough("nexus")).isFalse();
        }

        @Test
        void on_room_event_with_null_nats_does_not_crash() {
            replicator.enableEventSourcing("nexus");

            // Should not throw — NATS is null, event silently skipped
            assertThatCode(() -> replicator.onRoomEvent("nexus", sampleEvent("nexus")))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    class WriteThroughMode {

        @Test
        void enabled_room_tracks_as_write_through() {
            replicator.enableWriteThrough("home-ma");

            assertThat(replicator.isWriteThrough("home-ma")).isTrue();
            assertThat(replicator.isEventSourced("home-ma")).isFalse();
            assertThat(replicator.writeThroughCount()).isEqualTo(1);
        }

        @Test
        void write_through_triggers_snapshot_callback() {
            var snapshots = new ArrayList<String>();
            replicator.setSnapshotTrigger(snapshots::add);
            replicator.enableWriteThrough("home-ma");

            replicator.onRoomEvent("home-ma", sampleEvent("home-ma"));

            assertThat(snapshots).containsExactly("home-ma");
        }

        @Test
        void non_write_through_room_does_not_trigger_snapshot() {
            var snapshots = new ArrayList<String>();
            replicator.setSnapshotTrigger(snapshots::add);

            // Room not enabled for write-through
            replicator.onRoomEvent("random-room", sampleEvent("random-room"));

            assertThat(snapshots).isEmpty();
        }
    }

    @Nested
    class TierUpdates {

        @Test
        void update_tier_to_event_sourced() {
            replicator.updateTier("nexus", ReplicationTier.EVENT_SOURCED);

            assertThat(replicator.isEventSourced("nexus")).isTrue();
            assertThat(replicator.isWriteThrough("nexus")).isFalse();
        }

        @Test
        void update_tier_to_write_through() {
            replicator.updateTier("home-ma", ReplicationTier.WRITE_THROUGH);

            assertThat(replicator.isWriteThrough("home-ma")).isTrue();
            assertThat(replicator.isEventSourced("home-ma")).isFalse();
        }

        @Test
        void update_tier_to_periodic_disables_real_time() {
            replicator.enableEventSourcing("nexus");
            replicator.updateTier("nexus", ReplicationTier.PERIODIC);

            assertThat(replicator.isEventSourced("nexus")).isFalse();
            assertThat(replicator.isWriteThrough("nexus")).isFalse();
        }

        @Test
        void update_tier_to_lazy_disables_real_time() {
            replicator.enableWriteThrough("home-ma");
            replicator.updateTier("home-ma", ReplicationTier.LAZY);

            assertThat(replicator.isEventSourced("home-ma")).isFalse();
            assertThat(replicator.isWriteThrough("home-ma")).isFalse();
        }

        @Test
        void update_tier_to_config_only_disables_real_time() {
            replicator.enableEventSourcing("foundation");
            replicator.updateTier("foundation", ReplicationTier.CONFIG_ONLY);

            assertThat(replicator.isEventSourced("foundation")).isFalse();
            assertThat(replicator.isWriteThrough("foundation")).isFalse();
        }
    }

    @Nested
    class MultipleRooms {

        @Test
        void multiple_rooms_are_independent() {
            replicator.enableEventSourcing("nexus");
            replicator.enableWriteThrough("home-ma");

            assertThat(replicator.isEventSourced("nexus")).isTrue();
            assertThat(replicator.isWriteThrough("nexus")).isFalse();

            assertThat(replicator.isWriteThrough("home-ma")).isTrue();
            assertThat(replicator.isEventSourced("home-ma")).isFalse();

            assertThat(replicator.eventSourcedCount()).isEqualTo(1);
            assertThat(replicator.writeThroughCount()).isEqualTo(1);
        }

        @Test
        void disabling_one_room_does_not_affect_others() {
            replicator.enableEventSourcing("nexus");
            replicator.enableEventSourcing("forge");

            replicator.disableReplication("nexus");

            assertThat(replicator.isEventSourced("nexus")).isFalse();
            assertThat(replicator.isEventSourced("forge")).isTrue();
            assertThat(replicator.eventSourcedCount()).isEqualTo(1);
        }
    }

    @Nested
    class OnRoomEventRouting {

        @Test
        void event_sourced_room_publishes_event() {
            // With null NATS, the publish is a no-op but doesn't crash
            replicator.enableEventSourcing("nexus");

            assertThatCode(() -> replicator.onRoomEvent("nexus", sampleEvent("nexus")))
                .doesNotThrowAnyException();
        }

        @Test
        void untracked_room_does_nothing() {
            var counter = new AtomicInteger(0);
            replicator.setSnapshotTrigger(roomId -> counter.incrementAndGet());

            // Room not enabled for anything
            replicator.onRoomEvent("unknown-room", sampleEvent("unknown-room"));

            assertThat(counter.get()).isZero();
        }

        @Test
        void write_through_triggers_snapshot_not_event_publish() {
            var snapshotTriggers = new ArrayList<String>();
            replicator.setSnapshotTrigger(snapshotTriggers::add);
            replicator.enableWriteThrough("home-ma");

            replicator.onRoomEvent("home-ma", sampleEvent("home-ma"));

            // Snapshot triggered (write-through)
            assertThat(snapshotTriggers).containsExactly("home-ma");
        }
    }

    @Nested
    class BroadcastAllMode {

        private RoomEventReplicator broadcastReplicator;

        @BeforeEach
        void setup() {
            // broadcastAll=true, null NATS (single-node mode — publish is no-op)
            broadcastReplicator = new RoomEventReplicator(null, "test-zone", true);
        }

        @Test
        void broadcasts_event_for_untracked_room() {
            // In broadcastAll mode, events are published for ALL rooms,
            // even those not explicitly enabled for event-sourcing or write-through.
            // With null NATS the publish is a no-op, but it should not crash.
            assertThatCode(() ->
                broadcastReplicator.onRoomEvent("random-room", sampleEvent("random-room"))
            ).doesNotThrowAnyException();
        }

        @Test
        void broadcasts_event_for_event_sourced_room() {
            broadcastReplicator.enableEventSourcing("nexus");

            assertThatCode(() ->
                broadcastReplicator.onRoomEvent("nexus", sampleEvent("nexus"))
            ).doesNotThrowAnyException();
        }

        @Test
        void does_not_trigger_write_through_snapshot_in_broadcast_mode() {
            var snapshots = new ArrayList<String>();
            broadcastReplicator.setSnapshotTrigger(snapshots::add);
            broadcastReplicator.enableWriteThrough("home-ma");

            // In broadcastAll mode, events are published (not snapshot-triggered)
            broadcastReplicator.onRoomEvent("home-ma", sampleEvent("home-ma"));

            // broadcastAll takes priority — publishes event, does NOT trigger snapshot
            assertThat(snapshots).isEmpty();
        }

        @Test
        void default_replicator_is_not_broadcast_all() {
            // The default constructor (no broadcastAll flag) should NOT broadcast
            // events for untracked rooms.
            var counter = new AtomicInteger(0);
            replicator.setSnapshotTrigger(roomId -> counter.incrementAndGet());

            replicator.onRoomEvent("untracked", sampleEvent("untracked"));

            assertThat(counter.get()).isZero();
        }
    }
}
