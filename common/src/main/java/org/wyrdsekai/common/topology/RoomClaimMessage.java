package org.wyrdsekai.common.topology;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Claim message broadcast when a node wants to become primary for a room.
 * Conflict resolution: higher snapshotTimestamp wins; ties broken by
 * lexicographically lower nodeId (deterministic).
 *
 * @param roomId             room being claimed
 * @param claimingNodeId     node making the claim
 * @param snapshotTimestamp  timestamp of the snapshot the claiming node holds
 * @param ownership          ownership classification of the room
 * @param timestamp          when this claim was generated
 */
public record RoomClaimMessage(
    @JsonProperty("roomId") String roomId,
    @JsonProperty("claimingNodeId") String claimingNodeId,
    @JsonProperty("snapshotTimestamp") Instant snapshotTimestamp,
    @JsonProperty("ownership") RoomOwnership ownership,
    @JsonProperty("timestamp") Instant timestamp
) {}
