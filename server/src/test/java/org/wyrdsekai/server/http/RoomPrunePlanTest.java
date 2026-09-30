package org.wyrdsekai.server.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What {@code wyrd rooms prune} would take down. */
class RoomPrunePlanTest {

    private static RoomAdminRoutes.RoomRow row(String id, String name, long at, boolean markup, boolean kept) {
        return new RoomAdminRoutes.RoomRow(id, name, kept ? "founding" : "system", at, 1, List.of(), markup, kept);
    }

    @Test
    @DisplayName("markup ids always; later copies of a name only when asked; founding rooms never")
    void plan() {
        var rooms = List.of(
            row("nexus", "The Nexus", 1, false, true),
            row("holding-room-1642", "holding room", 30, false, false),
            row("holding-room-6978", "holding room", 10, false, false),
            row("the-forge-parameter-function-tool-call-7040", "The Forge of Quiet Things", 20, true, false),
            row("greenhouse-2063", "Greenhouse", 5, false, false));
        var junkOnly = RoomAdminRoutes.prunePlan(rooms, false);
        assertEquals(List.of("the-forge-parameter-function-tool-call-7040"),
            junkOnly.stream().map(RoomAdminRoutes.RoomRow::roomId).toList());
        var withDup = RoomAdminRoutes.prunePlan(rooms, true);
        assertEquals(List.of("the-forge-parameter-function-tool-call-7040", "holding-room-1642"),
            withDup.stream().map(RoomAdminRoutes.RoomRow::roomId).toList(),
            "the oldest copy (6978, created 10) is kept; the later one goes");
    }

    private static RoomAdminRoutes.RoomRow quiet(String id, String name, long lastActivityAt, List<String> who, boolean kept) {
        return new RoomAdminRoutes.RoomRow(id, name, kept ? "founding" : "mia", 1, 1, who, false, kept, lastActivityAt);
    }

    @Test
    @DisplayName("stale: a made room nobody is in and nothing has happened in for the given days; unknown activity is never stale")
    void stale() {
        long day = RoomAdminRoutes.DAY_MS, now = 100 * day;
        var rooms = List.of(
            quiet("nexus", "The Nexus", now - 90 * day, List.of(), true),
            quiet("the-hub-9003", "the hub", now - 45 * day, List.of(), false),
            quiet("threshold-room-6225", "Threshold Room", now - 3 * day, List.of(), false),
            quiet("attention-garden-1882", "Attention Garden", now - 60 * day, List.of("mia"), false),
            quiet("the-silence-7138", "the silence before the first word", 0, List.of(), false));
        assertEquals(List.of("the-hub-9003"),
            RoomAdminRoutes.prunePlan(rooms, false, 30, now).stream().map(RoomAdminRoutes.RoomRow::roomId).toList(),
            "founding stays; recent stays; occupied stays; unknown activity stays");
        assertEquals(List.of(), RoomAdminRoutes.prunePlan(rooms, false, 0, now).stream().map(RoomAdminRoutes.RoomRow::roomId).toList(),
            "no stale days asked: the old rule alone");
        assertEquals(List.of("the-hub-9003"),
            RoomAdminRoutes.prunePlan(rooms, false, 45, now).stream().map(RoomAdminRoutes.RoomRow::roomId).toList(), "exactly the threshold counts");
    }
}
