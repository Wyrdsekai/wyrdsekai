package org.wyrdsekai.between.layer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.topology.ReplicationTier;
import org.wyrdsekai.common.topology.RoomOwnership;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for RoomReplicationManager — per-room replication tier management.
 */
class RoomReplicationManagerTest {

    private RoomReplicationManager manager;

    @BeforeEach
    void setup() {
        manager = new RoomReplicationManager();
    }

    @Nested
    class TierComputation {

        @Test
        void foundation_room_starts_as_config_only() {
            // Foundation room, never visited, no entities
            var tier = manager.computeTier("nexus", RoomOwnership.SHARED, false, false);
            assertThat(tier).isEqualTo(ReplicationTier.CONFIG_ONLY);
        }

        @Test
        void visited_room_promotes_to_periodic() {
            // Mark as visited first
            manager.recordActivity("nexus");

            var tier = manager.computeTier("nexus", RoomOwnership.SHARED, false, false);
            assertThat(tier).isEqualTo(ReplicationTier.PERIODIC);
        }

        @Test
        void active_room_with_entities_is_periodic() {
            manager.recordActivity("nexus");

            var tier = manager.computeTier("nexus", RoomOwnership.SHARED, true, false);
            assertThat(tier).isEqualTo(ReplicationTier.PERIODIC);
        }

        @Test
        void companion_and_human_promotes_to_event_sourced() {
            var tier = manager.computeTier("nexus", RoomOwnership.SHARED, true, true);
            assertThat(tier).isEqualTo(ReplicationTier.EVENT_SOURCED);
        }

        @Test
        void personal_room_is_write_through() {
            var tier = manager.computeTier("study-operator", RoomOwnership.PERSONAL, false, false);
            assertThat(tier).isEqualTo(ReplicationTier.WRITE_THROUGH);
        }

        @Test
        void agent_home_is_write_through() {
            var tier = manager.computeTier("home-ma", RoomOwnership.AGENT_HOME, false, false);
            assertThat(tier).isEqualTo(ReplicationTier.WRITE_THROUGH);
        }

        @Test
        void agent_home_with_companion_is_event_sourced() {
            // Companion + human in an agent home room → event-sourced overrides write-through
            var tier = manager.computeTier("home-ma", RoomOwnership.AGENT_HOME, true, true);
            assertThat(tier).isEqualTo(ReplicationTier.EVENT_SOURCED);
        }
    }

    @Nested
    class ActivityAndIdleTracking {

        @Test
        void record_activity_resets_idle_timer() {
            manager.recordActivity("nexus");

            // Should be PERIODIC (visited, recently active)
            var tier = manager.computeTier("nexus", RoomOwnership.SHARED, false, false);
            assertThat(tier).isEqualTo(ReplicationTier.PERIODIC);
        }

        @Test
        void room_empties_and_starts_demotion() throws Exception {
            // Mark as visited with entities
            manager.recordActivity("nexus");
            manager.computeTier("nexus", RoomOwnership.SHARED, true, true);
            assertThat(manager.getTier("nexus")).isEqualTo(ReplicationTier.EVENT_SOURCED);

            // Room empties — no entities, no companion
            var newTier = manager.checkPromotion("nexus", RoomOwnership.SHARED, false, false);
            assertThat(newTier).isNotNull();
            // Should have demoted from EVENT_SOURCED to PERIODIC (recently active, no entities)
            assertThat(newTier).isEqualTo(ReplicationTier.PERIODIC);
        }

        @Test
        void check_promotion_returns_null_when_unchanged() {
            manager.recordActivity("nexus");
            manager.computeTier("nexus", RoomOwnership.SHARED, true, false);

            // Same conditions → null (unchanged)
            var result = manager.checkPromotion("nexus", RoomOwnership.SHARED, true, false);
            assertThat(result).isNull();
        }
    }

    @Nested
    class SnapshotIntervals {

        @Test
        void config_only_interval_is_10_minutes() {
            assertThat(manager.getSnapshotInterval("untracked"))
                .isEqualTo(Duration.ofMinutes(10));
        }

        @Test
        void periodic_active_interval_is_1_minute() {
            manager.recordActivity("nexus");
            manager.computeTier("nexus", RoomOwnership.SHARED, true, false);

            assertThat(manager.getSnapshotInterval("nexus", true))
                .isEqualTo(Duration.ofMinutes(1));
        }

        @Test
        void periodic_idle_interval_is_5_minutes() {
            manager.recordActivity("nexus");
            manager.computeTier("nexus", RoomOwnership.SHARED, false, false);

            assertThat(manager.getSnapshotInterval("nexus", false))
                .isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        void event_sourced_interval_is_zero() {
            manager.computeTier("nexus", RoomOwnership.SHARED, true, true);

            assertThat(manager.getSnapshotInterval("nexus"))
                .isEqualTo(Duration.ZERO);
        }

        @Test
        void write_through_interval_is_30_seconds() {
            manager.computeTier("study", RoomOwnership.PERSONAL, false, false);

            assertThat(manager.getSnapshotInterval("study"))
                .isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        void lazy_interval_is_5_minutes() {
            assertThat(ReplicationTier.LAZY.defaultSnapshotInterval())
                .isEqualTo(Duration.ofMinutes(5));
        }
    }

    @Nested
    class Diagnostics {

        @Test
        void all_tiers_returns_tracked_rooms() {
            manager.computeTier("nexus", RoomOwnership.SHARED, false, false);
            manager.computeTier("home-ma", RoomOwnership.AGENT_HOME, false, false);

            var tiers = manager.allTiers();
            assertThat(tiers).hasSize(2);
            assertThat(tiers).containsKey("nexus");
            assertThat(tiers).containsKey("home-ma");
        }

        @Test
        void remove_cleans_up_tracking() {
            manager.recordActivity("nexus");
            manager.computeTier("nexus", RoomOwnership.SHARED, true, false);

            manager.remove("nexus");

            assertThat(manager.getTier("nexus")).isEqualTo(ReplicationTier.CONFIG_ONLY);
            assertThat(manager.trackedRoomCount()).isZero();
        }
    }
}
