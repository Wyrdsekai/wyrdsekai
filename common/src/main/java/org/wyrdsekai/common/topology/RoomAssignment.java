package org.wyrdsekai.common.topology;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Assignment of a room to a node in the household topology.
 * Carried inside {@link NodeAnnouncement} and maintained by the local room view.
 *
 * @param roomId         unique room identifier (sharding key)
 * @param ownership      ownership classification
 * @param primaryNodeId  node currently hosting this room as primary
 * @param ownerDid       DID of the owning human (nullable — null for shared rooms)
 * @param agentDid       DID of the owning agent (nullable — only set for AGENT_HOME)
 * @param replicaTarget  desired number of replicas across household nodes
 * @param lastAnnounced  when this assignment was last announced to the household
 * @param lastSnapshotAt when the last state snapshot was taken
 */
public record RoomAssignment(
    @JsonProperty("roomId") String roomId,
    @JsonProperty("ownership") RoomOwnership ownership,
    @JsonProperty("primaryNodeId") String primaryNodeId,
    @JsonProperty("ownerDid") String ownerDid,
    @JsonProperty("agentDid") String agentDid,
    @JsonProperty("replicaTarget") int replicaTarget,
    @JsonProperty("lastAnnounced") Instant lastAnnounced,
    @JsonProperty("lastSnapshotAt") Instant lastSnapshotAt
) {}
