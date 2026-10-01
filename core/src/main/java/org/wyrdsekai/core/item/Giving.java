package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.room.Rooms;
import org.wyrdsekai.core.room.ZoneGuardian;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * A person hands something they carry to someone in the same room: {@code give <item> to <who>}.
 *
 * <p>When a bond reaches the item depth the companion offers "to exchange something — a token, a
 * small item". A person's gift then moved a row in the inventory table and nothing else: nobody in
 * the room saw it and the companion was never told she had been given anything; on the browser,
 * the phone and the terminal client the words were only said aloud (2026-09-30). The gift is now
 * one act on every surface: the item changes hands, the room sees it, and a companion who
 * receives it is told, so it is hers to remember.
 */
public final class Giving {

    private static final Logger log = LoggerFactory.getLogger(Giving.class);
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern LINE = Pattern.compile("^give\\s+(\\S.*?)\\s+to\\s+(\\S+)$", Pattern.CASE_INSENSITIVE);

    public enum Status { GIVEN, NOT_CARRIED, NOT_HERE }

    /** What came of a gift: the item as it is named in the inventory, and who it was for. */
    public record Outcome(Status status, String itemName, String targetName) {}

    private Giving() {}

    /** {@code give <item> to <who>} as typed: the item and the name, or null when the line is not that. */
    public static String[] parse(String line) {
        if (line == null) return null;
        var m = LINE.matcher(line.strip());
        return m.matches() ? new String[] {m.group(1).strip(), m.group(2).strip()} : null;
    }

    /** Hand over an item the giver carries to someone in the same room. */
    public static Outcome give(InventoryService inventory, String giverId, String giverName, String roomId,
                               String objectName, String targetName) {
        var item = inventory.findTakeableByName(giverId, objectName);
        if (item.isEmpty()) return new Outcome(Status.NOT_CARRIED, objectName, targetName);
        var registry = EntityRegistry.get();
        var targetId = registry == null ? null : registry.findByName(targetName).orElse(null);
        if (targetId == null || !registry.roomOf(targetId).map(r -> r.equals(roomId)).orElse(false)) {
            return new Outcome(Status.NOT_HERE, objectName, targetName);
        }
        var inv = item.get();
        inventory.removeItem(giverId, inv.objectId());
        inventory.addItem(targetId, inv.objectId(), inv.objectName(), inv.description(), inv.takeable(), roomId);
        var receiver = registry.nameOf(targetId).orElse(targetName);
        announce(roomId, giverId, giverName, inv.objectName(), receiver);
        var companion = ZoneGuardian.getCompanionRef(null, targetId);
        if (companion != null) companion.tell(new CompanionActor.ReceiveGift(giverId, giverName, inv.objectName()));
        log.info("{} gave '{}' to {}", giverName, inv.objectName(), receiver);
        return new Outcome(Status.GIVEN, inv.objectName(), receiver);
    }

    /** The room sees the gift change hands. */
    private static void announce(String roomId, String giverId, String giverName, String itemName, String receiver) {
        try {
            var room = RoomRegistry.get().ref(roomId);
            if (room == null) return;
            Rooms.<RoomResponse>ask(room,
                ref -> new RoomCommand.EmoteInRoom(giverId, giverName, "gives " + itemName + " to " + receiver, ref),
                ASK_TIMEOUT
            ).exceptionally(ex -> {
                log.warn("The gift was given but the room was not told: {}", ex.getMessage());
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("The gift was given but the room was not told: {}", e.toString());
        }
    }
}
