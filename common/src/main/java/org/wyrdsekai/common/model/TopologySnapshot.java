package org.wyrdsekai.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wire-format topology snapshot for client map rendering (§N1).
 * Sent in response to map/nearby/rooms commands.
 * Contains a subgraph centered on a room with nodes and directed edges.
 */
public record TopologySnapshot(
    @JsonProperty("centerRoomId") String centerRoomId,
    @JsonProperty("nodes") List<MapNode> nodes,
    @JsonProperty("edges") List<MapEdge> edges
) {
    /**
     * A room node in the topology graph.
     *
     * @param roomId         Room identifier
     * @param name           Display name (null/"?" if unvisited and fog-of-war)
     * @param zone           Zone this room belongs to
     * @param current        True if the player is currently in this room
     * @param visited        True if the player has previously entered this room
     * @param hopsFromCenter BFS distance from the center room
     */
    public record MapNode(
        @JsonProperty("roomId") String roomId,
        @JsonProperty("name") String name,
        @JsonProperty("zone") String zone,
        @JsonProperty("current") boolean current,
        @JsonProperty("visited") boolean visited,
        @JsonProperty("hopsFromCenter") int hopsFromCenter
    ) {}

    /**
     * A directed edge in the topology graph.
     * Exits are directional: A→B does NOT imply B→A.
     *
     * @param fromRoomId Source room
     * @param toRoomId   Target room
     * @param direction  Direction label (e.g., "north", "portal", "down")
     * @param label      Human-readable exit description
     * @param hasReturn  True if target room has an exit back to source
     */
    public record MapEdge(
        @JsonProperty("fromRoomId") String fromRoomId,
        @JsonProperty("toRoomId") String toRoomId,
        @JsonProperty("direction") String direction,
        @JsonProperty("label") String label,
        @JsonProperty("hasReturn") boolean hasReturn
    ) {}

    /** Empty snapshot. */
    public static TopologySnapshot empty(String centerRoomId) {
        return new TopologySnapshot(centerRoomId, List.of(), List.of());
    }
}
