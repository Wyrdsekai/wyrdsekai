package org.wyrdsekai.core.identity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Player presence record published via Between to track which players are online
 * on which nodes and in which rooms.
 *
 * <p>Published to {@code zone.{zoneId}.presence.{did}} via NATS. Other nodes maintain
 * a local map of online players, enabling:
 * <ul>
 *   <li>"Masumi is in The Nexus on gpu-host"</li>
 *   <li>Companion knows where its human is</li>
 *   <li>Room assignments know which node the player is on</li>
 * </ul></p>
 *
 * @param did          Player's DID:key identifier
 * @param displayName  Human-readable name
 * @param nodeId       Node where the player is connected
 * @param roomId       Current room ID
 * @param lastSeen     Last activity timestamp
 */
public record PlayerPresence(
    @JsonProperty("did") String did,
    @JsonProperty("displayName") String displayName,
    @JsonProperty("nodeId") String nodeId,
    @JsonProperty("roomId") String roomId,
    @JsonProperty("lastSeen") Instant lastSeen
) {
    @JsonCreator
    public PlayerPresence {
        if (did == null || did.isBlank()) throw new IllegalArgumentException("DID must not be blank");
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("Node ID must not be blank");
        if (lastSeen == null) lastSeen = Instant.now();
    }

    /**
     * Create a presence for a connected player.
     */
    public static PlayerPresence online(String did, String displayName, String nodeId, String roomId) {
        return new PlayerPresence(did, displayName, nodeId, roomId, Instant.now());
    }

    /**
     * Return a copy with an updated room.
     */
    public PlayerPresence inRoom(String newRoomId) {
        return new PlayerPresence(did, displayName, nodeId, newRoomId, Instant.now());
    }

    /**
     * Return a copy with a refreshed timestamp.
     */
    public PlayerPresence refreshed() {
        return new PlayerPresence(did, displayName, nodeId, roomId, Instant.now());
    }
}
