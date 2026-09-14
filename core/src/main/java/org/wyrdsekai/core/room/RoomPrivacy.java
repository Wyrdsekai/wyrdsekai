package org.wyrdsekai.core.room;

/**
 * Which rooms are somebody's, and what the map may say about a person standing in one.
 *
 * <p>The map shows everyone by name in public rooms — a companion in the Garden is
 * visible to whoever is in the Garden already, so naming the room on a map gives away
 * nothing she is not already showing. Her Home, a person's Study and the sanctuary are
 * different: the map says the kind of place and never the room's name, and nobody has to
 * opt in to be findable where they are already findable (2026-09-13).</p>
 */
public final class RoomPrivacy {

    private RoomPrivacy() {}

    /** A short phrase for a private room ("at home"), or {@code null} when the room is public. */
    public static String whereabouts(String roomId) {
        if (roomId == null) return null;
        if (roomId.startsWith("home-")) return "at home";
        if (roomId.startsWith("study-")) return "in their Study";
        if (roomId.equals("sanctuary") || roomId.startsWith("sanctuary-")) return "resting";
        return null;
    }

    public static boolean isPrivate(String roomId) {
        return whereabouts(roomId) != null;
    }
}
