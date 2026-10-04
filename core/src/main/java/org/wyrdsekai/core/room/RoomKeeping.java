package org.wyrdsekai.core.room;

import org.wyrdsekai.core.persistence.MadeThingsStore.RoomRecord;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Which made rooms the house keeps open and which it closes up. Pure rules, no actors.
 *
 * <ul>
 *   <li>A room a person asked for is theirs to let go: the house never closes it up and never counts it.</li>
 *   <li>A room she made on her own that a person has since been in is held the same way.</li>
 *   <li>A founding room, a Home, a Study, a room with no known maker: never.</li>
 *   <li>A room she made on her own, nobody else's: closed up when nobody has entered it for the
 *       configured days (longer when it holds something beyond its furnishings), and counted against
 *       the number of her own rooms she keeps open.</li>
 * </ul>
 */
public final class RoomKeeping {

    private RoomKeeping() {}

    /** The numbers, each a setting; 0 turns that rule off. */
    public record Settings(int emptyDays, int heldDays, int openMax) {}

    /** Why the house leaves a room alone, in words a steward's list can show; {@code null} when it does not. */
    public static String heldBecause(RoomRecord r, Set<String> protectedRooms, Set<String> companionNames) {
        if (r == null || r.roomId() == null) return "no record";
        var id = r.roomId();
        if (id.startsWith("home-")) return "a companion's Home";
        if (id.startsWith("study-")) return "a person's Study";
        if (WorkshopProvisioner.isWorkshop(id)) return "a person's Workshop";
        if (protectedRooms != null && protectedRooms.contains(id)) return "a founding room";
        if (!madeByACompanion(r, companionNames)) return "no companion made it";
        // Made before the house kept a record, and the trail could not say who asked: held. Treating
        // "nobody wrote it down" as "she made it on her own" would close rooms a person asked for.
        if (r.madeFor() == null || r.madeFor().isBlank()) return "nobody recorded who it was made for";
        if (r.askedFor()) return "a person asked for it";
        if (r.aPersonHasBeenIn()) return "a person has been in it";
        return null;
    }

    static boolean madeByACompanion(RoomRecord r, Set<String> companionNames) {
        if (r.createdBy() == null || r.createdBy().isBlank() || companionNames == null) return false;
        var by = r.createdBy().toLowerCase(Locale.ROOT);
        return companionNames.stream().anyMatch(n -> n != null && n.toLowerCase(Locale.ROOT).equals(by));
    }

    /** How long since anyone entered it; a room nobody has entered counts from when it was made. */
    public static Duration quietFor(RoomRecord r, long nowMs) {
        long last = Math.max(r.createdAtMs(), r.lastEnteredAt() == null ? 0L : r.lastEnteredAt());
        return Duration.ofMillis(Math.max(0L, nowMs - last));
    }

    /** Whether an open room of her own, held by nothing, has been quiet long enough to close up. */
    public static boolean due(RoomRecord r, boolean holdsSomething, Settings s, long nowMs) {
        if (r.closed()) return false;
        int days = holdsSomething ? s.heldDays() : s.emptyDays();
        if (days <= 0) return false;
        return quietFor(r, nowMs).compareTo(Duration.ofDays(days)) > 0;
    }

    /** Her own open rooms that count against how many she keeps open: made by her, held by nothing. */
    public static List<RoomRecord> openOwn(List<RoomRecord> all, String maker, Set<String> protectedRooms) {
        var out = new ArrayList<RoomRecord>();
        if (maker == null) return out;
        for (var r : all) {
            if (r.closed()) continue;
            if (heldBecause(r, protectedRooms, Set.of(maker)) != null) continue;
            out.add(r);
        }
        return out;
    }

    /** Of her own open rooms, the one nobody has entered for longest, leaving out the ones named. */
    public static Optional<RoomRecord> longestQuiet(List<RoomRecord> openOwn, Set<String> notThese, long nowMs) {
        return openOwn.stream()
            .filter(r -> notThese == null || !notThese.contains(r.roomId()))
            .max(Comparator.comparing(r -> quietFor(r, nowMs)));
    }

    /** Of her own open rooms, the few nobody has entered for longest, quietest first, each quiet at least a day. */
    public static List<RoomRecord> quietest(List<RoomRecord> openOwn, Set<String> notThese, long nowMs, int howMany) {
        return openOwn.stream()
            .filter(r -> notThese == null || !notThese.contains(r.roomId()))
            .filter(r -> quietFor(r, nowMs).toDays() >= 1)
            .sorted(Comparator.comparing((RoomRecord r) -> quietFor(r, nowMs)).reversed())
            .limit(Math.max(0, howMany))
            .toList();
    }

    /** Whether one more room of her own would pass the number she keeps open. */
    public static boolean atLimit(List<RoomRecord> openOwn, Settings s) {
        return s.openMax() > 0 && openOwn.size() >= s.openMax();
    }

    public static String days(Duration d) {
        long days = d.toDays();
        return days == 1 ? "1 day" : days + " days";
    }
}
