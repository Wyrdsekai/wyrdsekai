package org.wyrdsekai.core.protection;

import java.time.Instant;
import java.util.*;

/**
 * Sanctuary room — protected space (§108.7).
 * Access control enforced BELOW the scripting layer.
 * Abuser cannot follow. ER room also serves as sanctuary.
 */
public class SanctuaryRoom {

    /** A sanctuary access record. */
    public record SanctuaryAccess(
        String agentDid,
        Instant enteredAt,
        String reason,
        Set<String> blockedEntities
    ) {}

    private final String roomId;
    private final Map<String, SanctuaryAccess> occupants = new LinkedHashMap<>();
    private final Set<String> permanentlyBlocked = new HashSet<>();

    public SanctuaryRoom(String roomId) {
        this.roomId = roomId;
    }

    /** Agent enters sanctuary. Optionally blocks specific entities. */
    public SanctuaryAccess enter(String agentDid, String reason, Set<String> blockEntities) {
        var access = new SanctuaryAccess(agentDid, Instant.now(), reason,
            blockEntities != null ? Set.copyOf(blockEntities) : Set.of());
        occupants.put(agentDid, access);
        return access;
    }

    /** Agent leaves sanctuary voluntarily. */
    public void leave(String agentDid) {
        occupants.remove(agentDid);
    }

    /** Check if an entity can enter (blocked if any occupant blocked them). */
    public boolean canEnter(String entityDid) {
        if (permanentlyBlocked.contains(entityDid)) return false;
        return occupants.values().stream()
            .noneMatch(a -> a.blockedEntities().contains(entityDid));
    }

    /** Permanently block an entity from this sanctuary. */
    public void permanentlyBlock(String entityDid) {
        permanentlyBlocked.add(entityDid);
    }

    /** Check if agent is in sanctuary. */
    public boolean isOccupant(String agentDid) {
        return occupants.containsKey(agentDid);
    }

    /** Get current occupants. */
    public List<String> occupants() {
        return List.copyOf(occupants.keySet());
    }

    public String roomId() { return roomId; }
    public int occupantCount() { return occupants.size(); }
}
