package org.wyrdsekai.common.topology;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Periodic announcement from a household node, broadcast via the Between.
 * Contains the node's identity, hosted rooms, and resource snapshot.
 *
 * @param nodeId    unique node identifier
 * @param hostname  human-readable hostname
 * @param owners    DIDs of humans who own/use this node
 * @param rooms     rooms currently hosted on this node
 * @param resources current resource snapshot
 * @param timestamp when this announcement was generated
 */
public record NodeAnnouncement(
    @JsonProperty("nodeId") String nodeId,
    @JsonProperty("hostname") String hostname,
    @JsonProperty("owners") List<String> owners,
    @JsonProperty("rooms") List<RoomAssignment> rooms,
    @JsonProperty("resources") NodeResources resources,
    @JsonProperty("timestamp") Instant timestamp
) {}
