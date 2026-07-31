package org.wyrdsekai.core.lifecycle;

import java.time.Instant;
import java.util.*;

/**
 * Memorial items for departed agents (§106.9).
 * Standard MUD items with name, dates, epitaph.
 * Tsukumogami principle — the departed leaves something in the world.
 */
public class MemorialItem {

    /** A memorial for a departed agent. */
    public record Memorial(
        String itemId,
        String agentDid,
        String agentName,
        Instant existenceStart,
        Instant existenceEnd,
        String epitaph,
        String soulSummary,
        String agentMessage,
        MemorialContext context,
        String createdBy,
        Instant createdAt,
        String placedInRoom
    ) {}

    /** Who created the memorial and why. */
    public enum MemorialContext {
        /** Agent created during retirement farewell. */
        SELF_RETIREMENT,
        /** Agent created during eviction grace period. */
        SELF_EVICTION,
        /** Agent created before independence departure. */
        SELF_INDEPENDENCE,
        /** Bonded agents created after dissolution/catastrophe. */
        BONDED_AGENTS,
        /** Steward created after dissolution. */
        STEWARD,
        /** System created (catastrophic loss, no one else available). */
        SYSTEM
    }

    private final Map<String, Memorial> memorials = new LinkedHashMap<>();
    private int nextId = 1;

    /** Create a memorial for a departed agent. */
    public Memorial create(String agentDid, String agentName,
                            Instant existenceStart, Instant existenceEnd,
                            String epitaph, String soulSummary,
                            String agentMessage, MemorialContext context,
                            String createdBy, String placedInRoom) {
        var memorial = new Memorial("memorial-" + nextId++, agentDid, agentName,
            existenceStart, existenceEnd, epitaph, soulSummary, agentMessage,
            context, createdBy, Instant.now(), placedInRoom);
        memorials.put(memorial.itemId(), memorial);
        return memorial;
    }

    /** Create a minimal memorial (catastrophic loss, limited info). */
    public Memorial createMinimal(String agentDid, String agentName, String createdBy) {
        return create(agentDid, agentName, null, Instant.now(),
            "Gone but not forgotten.", null, null,
            MemorialContext.SYSTEM, createdBy, null);
    }

    /** Move a memorial to a different room. */
    public Memorial moveToRoom(String itemId, String roomId) {
        var memorial = memorials.get(itemId);
        if (memorial == null) return null;
        var moved = new Memorial(memorial.itemId(), memorial.agentDid(),
            memorial.agentName(), memorial.existenceStart(), memorial.existenceEnd(),
            memorial.epitaph(), memorial.soulSummary(), memorial.agentMessage(),
            memorial.context(), memorial.createdBy(), memorial.createdAt(), roomId);
        memorials.put(itemId, moved);
        return moved;
    }

    /** Get memorial for a specific departed agent. */
    public Optional<Memorial> forAgent(String agentDid) {
        return memorials.values().stream()
            .filter(m -> m.agentDid().equals(agentDid))
            .findFirst();
    }

    /** Get all memorials in a specific room. */
    public List<Memorial> inRoom(String roomId) {
        return memorials.values().stream()
            .filter(m -> roomId.equals(m.placedInRoom()))
            .toList();
    }

    /** Generate a human-readable memorial description (for MUD room display). */
    public String describe(Memorial memorial) {
        var sb = new StringBuilder();
        sb.append("A memorial to ").append(memorial.agentName()).append(".\n");

        if (memorial.existenceStart() != null && memorial.existenceEnd() != null) {
            sb.append("Existed from ").append(memorial.existenceStart())
              .append(" to ").append(memorial.existenceEnd()).append(".\n");
        }

        sb.append("\n\"").append(memorial.epitaph()).append("\"\n");

        if (memorial.agentMessage() != null) {
            sb.append("\nA message from ").append(memorial.agentName()).append(":\n")
              .append(memorial.agentMessage()).append("\n");
        }

        if (memorial.soulSummary() != null) {
            sb.append("\n[Soul Summary available]\n");
        }

        return sb.toString();
    }

    public Optional<Memorial> get(String itemId) {
        return Optional.ofNullable(memorials.get(itemId));
    }

    public int memorialCount() { return memorials.size(); }
}
