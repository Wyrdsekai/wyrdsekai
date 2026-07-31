package org.wyrdsekai.common.topology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.util.Json;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for common topology types: enums, records, serialization round-trips.
 */
class TopologyTypesTest {

    private final ObjectMapper mapper = Json.mapper();

    private <T> T roundTrip(Object value, Class<T> type) throws JsonProcessingException {
        var json = mapper.writeValueAsString(value);
        return mapper.readValue(json, type);
    }

    // --- RoomOwnership ---

    @Nested
    class RoomOwnershipTests {

        @Test
        void personal_does_not_replicate() {
            assertThat(RoomOwnership.PERSONAL.replicates()).isFalse();
        }

        @Test
        void shared_replicates() {
            assertThat(RoomOwnership.SHARED.replicates()).isTrue();
        }

        @Test
        void agent_home_replicates() {
            assertThat(RoomOwnership.AGENT_HOME.replicates()).isTrue();
        }
    }

    // --- RoomAssignment serialization ---

    @Nested
    class RoomAssignmentTests {

        @Test
        void serialization_roundtrip() throws Exception {
            var now = Instant.now();
            var assignment = new RoomAssignment(
                "nexus", RoomOwnership.SHARED, "node-1",
                "did:key:owner1", null, 2, now, now
            );
            var result = roundTrip(assignment, RoomAssignment.class);

            assertThat(result.roomId()).isEqualTo("nexus");
            assertThat(result.ownership()).isEqualTo(RoomOwnership.SHARED);
            assertThat(result.primaryNodeId()).isEqualTo("node-1");
            assertThat(result.ownerDid()).isEqualTo("did:key:owner1");
            assertThat(result.agentDid()).isNull();
            assertThat(result.replicaTarget()).isEqualTo(2);
            assertThat(result.lastAnnounced()).isEqualTo(now);
            assertThat(result.lastSnapshotAt()).isEqualTo(now);
        }

        @Test
        void serialization_with_agent_home() throws Exception {
            var now = Instant.now();
            var assignment = new RoomAssignment(
                "agent-home-home-server", RoomOwnership.AGENT_HOME, "node-2",
                null, "did:key:home-server", 3, now, now
            );
            var result = roundTrip(assignment, RoomAssignment.class);

            assertThat(result.ownership()).isEqualTo(RoomOwnership.AGENT_HOME);
            assertThat(result.agentDid()).isEqualTo("did:key:home-server");
            assertThat(result.ownerDid()).isNull();
        }
    }

    // --- NodeAnnouncement serialization ---

    @Nested
    class NodeAnnouncementTests {

        @Test
        void serialization_roundtrip() throws Exception {
            var now = Instant.now();
            var resources = new NodeResources(
                8192, 32768, List.of("RTX 4090"), List.of("qwen2.5:7b"), 23.5, 10
            );
            var rooms = List.of(
                new RoomAssignment("nexus", RoomOwnership.SHARED, "node-1",
                    null, null, 2, now, now),
                new RoomAssignment("home-home-server", RoomOwnership.AGENT_HOME, "node-1",
                    null, "did:key:home-server", 3, now, now)
            );
            var announcement = new NodeAnnouncement(
                "node-1", "desktop.local",
                List.of("did:key:alice", "did:key:bob"),
                rooms, resources, now
            );

            var result = roundTrip(announcement, NodeAnnouncement.class);

            assertThat(result.nodeId()).isEqualTo("node-1");
            assertThat(result.hostname()).isEqualTo("desktop.local");
            assertThat(result.owners()).containsExactly("did:key:alice", "did:key:bob");
            assertThat(result.rooms()).hasSize(2);
            assertThat(result.resources().vramMb()).isEqualTo(8192);
            assertThat(result.resources().gpuModels()).containsExactly("RTX 4090");
            assertThat(result.resources().inferenceModels()).containsExactly("qwen2.5:7b");
            assertThat(result.resources().loadPct()).isEqualTo(23.5);
            assertThat(result.resources().availableRoomSlots()).isEqualTo(10);
            assertThat(result.timestamp()).isEqualTo(now);
        }
    }

    // --- RoomClaimMessage serialization ---

    @Nested
    class RoomClaimMessageTests {

        @Test
        void serialization_roundtrip() throws Exception {
            var snapshotTs = Instant.now().minusSeconds(10);
            var claimTs = Instant.now();
            var claim = new RoomClaimMessage(
                "library", "node-3", snapshotTs, RoomOwnership.SHARED, claimTs
            );

            var result = roundTrip(claim, RoomClaimMessage.class);

            assertThat(result.roomId()).isEqualTo("library");
            assertThat(result.claimingNodeId()).isEqualTo("node-3");
            assertThat(result.snapshotTimestamp()).isEqualTo(snapshotTs);
            assertThat(result.ownership()).isEqualTo(RoomOwnership.SHARED);
            assertThat(result.timestamp()).isEqualTo(claimTs);
        }
    }

    // --- NodeResources serialization ---

    @Nested
    class NodeResourcesTests {

        @Test
        void serialization_roundtrip() throws Exception {
            var resources = new NodeResources(
                0, 4096, List.of(), List.of(), 55.2, 25
            );
            var result = roundTrip(resources, NodeResources.class);

            assertThat(result.vramMb()).isZero();
            assertThat(result.ramMb()).isEqualTo(4096);
            assertThat(result.gpuModels()).isEmpty();
            assertThat(result.inferenceModels()).isEmpty();
            assertThat(result.loadPct()).isEqualTo(55.2);
            assertThat(result.availableRoomSlots()).isEqualTo(25);
        }
    }

    // --- ReplicationTier ---

    @Nested
    class ReplicationTierTests {

        @Test
        void event_sourced_has_zero_interval_and_supports_streaming() {
            assertThat(ReplicationTier.EVENT_SOURCED.defaultSnapshotInterval()).isEqualTo(Duration.ZERO);
            assertThat(ReplicationTier.EVENT_SOURCED.supportsEventStreaming()).isTrue();
        }

        @Test
        void write_through_has_30s_interval_and_supports_streaming() {
            assertThat(ReplicationTier.WRITE_THROUGH.defaultSnapshotInterval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(ReplicationTier.WRITE_THROUGH.supportsEventStreaming()).isTrue();
        }

        @Test
        void periodic_has_60s_interval_and_no_streaming() {
            assertThat(ReplicationTier.PERIODIC.defaultSnapshotInterval()).isEqualTo(Duration.ofSeconds(60));
            assertThat(ReplicationTier.PERIODIC.supportsEventStreaming()).isFalse();
        }

        @Test
        void lazy_has_5min_interval_and_no_streaming() {
            assertThat(ReplicationTier.LAZY.defaultSnapshotInterval()).isEqualTo(Duration.ofMinutes(5));
            assertThat(ReplicationTier.LAZY.supportsEventStreaming()).isFalse();
        }

        @Test
        void config_only_has_10min_interval_and_no_streaming() {
            assertThat(ReplicationTier.CONFIG_ONLY.defaultSnapshotInterval()).isEqualTo(Duration.ofMinutes(10));
            assertThat(ReplicationTier.CONFIG_ONLY.supportsEventStreaming()).isFalse();
        }
    }
}
