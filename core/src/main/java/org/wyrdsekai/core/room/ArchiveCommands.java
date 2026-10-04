package org.wyrdsekai.core.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.persistence.MadeThingsStore;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The archive as a person in the house uses it: {@code archive} (what is closed up or put away),
 * {@code archive <words>} (search it by what it was), {@code archive cold [words]} (cold storage),
 * and {@code reopen <name>} (bring a room or an item back, by its name or by what it was).
 *
 * <p>operator, 2026-10-02: "if I go, I want to bring back that nice room with the greenhouse, or
 * something: how do we find it". The same finding and bringing back serve the steward's
 * {@code wyrd rooms find|open} and {@code wyrd items find|out}.
 */
public final class ArchiveCommands {

    private static final Logger log = LoggerFactory.getLogger(ArchiveCommands.class);

    private ArchiveCommands() {}

    private static final Pattern LIST = Pattern.compile("(?i)^archive(?:\\s+(.*))?$");
    private static final Pattern REOPEN = Pattern.compile("(?i)^reopen(?:\\s+(.*))?$");
    private static final int ROWS = 20;
    private static final long DAY_MS = Duration.ofDays(1).toMillis();

    /** A whole line that is one of the archive's verbs, on any surface. */
    public static boolean isCommand(String line) {
        if (line == null) return false;
        var t = line.strip();
        return LIST.matcher(t).matches() || REOPEN.matcher(t).matches();
    }

    /** What a search of one tier found. */
    public record Listing(List<MadeArchive.Found> rooms, List<MadeArchive.Found> items, boolean cold, String query,
                          int coldRooms, int coldItems) {}

    /** What bringing something back came to. {@code several} holds the candidates when the words fit more than one. */
    public record Brought(boolean ok, String kind, String name, String message, List<MadeArchive.Found> several) {}

    static String lang(String locale) {
        if (locale == null || locale.isBlank()) return "en";
        var l = locale.strip().toLowerCase(Locale.ROOT);
        return l.startsWith("es") ? "es" : l.startsWith("ja") ? "ja" : "en";
    }

    /** The line a person typed, answered. {@code visitor}: someone from another household, who may not look. */
    public static String command(String personId, String line, String locale, boolean visitor) {
        var text = ScriptMessageCatalog.forLang(lang(locale));
        if (visitor) return text.get("archive.visitor");
        if (personId == null || personId.isBlank()) return text.get("archive.sign_in");
        var t = line == null ? "" : line.strip();
        var reopen = REOPEN.matcher(t);
        if (reopen.matches()) {
            var asked = reopen.group(1) == null ? "" : reopen.group(1).strip();
            if (asked.isEmpty()) return text.get("archive.reopen.usage");
            var brought = bringBack(asked, locale, true, true);
            if (brought.ok()) log.info("Archive: {} '{}' brought back by {}", brought.kind(), brought.name(), personId);
            return brought.message();
        }
        var list = LIST.matcher(t);
        if (!list.matches()) return text.get("archive.usage");
        var rest = list.group(1) == null ? "" : list.group(1).strip();
        if (rest.equalsIgnoreCase("help") || rest.equals("?")) return text.get("archive.usage");
        boolean cold = false;
        if (rest.equalsIgnoreCase("cold") || rest.toLowerCase(Locale.ROOT).startsWith("cold ")) {
            cold = true;
            rest = rest.substring(4).strip();
        }
        var found = find(rest, cold);
        if (found == null) return text.get("archive.unavailable");
        return render(found, text, System.currentTimeMillis());
    }

    /** One tier of the archive, searched by ordinary words; all of it when none are given. Null when not kept here. */
    public static Listing find(String query, boolean cold) {
        var store = MadeThingsStore.shared();
        if (store == null) return null;
        var rooms = store.rooms();
        var items = store.items();
        return new Listing(MadeArchive.rooms(rooms, query, cold), MadeArchive.items(items, query, cold), cold,
            query == null ? "" : query.strip(),
            (int) rooms.stream().filter(MadeThingsStore.RoomRecord::cold).count(),
            (int) items.stream().filter(MadeThingsStore.ItemRecord::cold).count());
    }

    static String render(Listing l, ScriptMessageCatalog text, long now) {
        var sb = new StringBuilder();
        if (l.rooms().isEmpty() && l.items().isEmpty()) {
            sb.append(l.query().isEmpty()
                ? text.get(l.cold() ? "archive.cold.empty" : "archive.empty")
                : text.get(l.cold() ? "archive.cold.none_fit" : "archive.none_fit", l.query()));
        } else {
            if (!l.rooms().isEmpty()) {
                sb.append(text.get(l.cold() ? "archive.head.cold.rooms" : "archive.head.rooms"));
                rows(sb, l.rooms(), "archive.row.room", text, now, false);
            }
            if (!l.items().isEmpty()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(text.get(l.cold() ? "archive.head.cold.items" : "archive.head.items"));
                rows(sb, l.items(), "archive.row.item", text, now, true);
            }
            sb.append('\n').append(text.get("archive.how"));
        }
        int inCold = l.coldRooms() + l.coldItems();
        if (!l.cold() && inCold > 0) sb.append('\n').append(text.get("archive.cold.hint", String.valueOf(inCold)));
        return sb.toString();
    }

    private static void rows(StringBuilder sb, List<MadeArchive.Found> found, String key, ScriptMessageCatalog text,
                             long now, boolean items) {
        for (var f : found.subList(0, Math.min(ROWS, found.size()))) {
            long days = Math.max(0, (now - f.archivedAtMs()) / DAY_MS);
            var what = f.what() == null || f.what().isBlank() ? ""
                : " — " + (f.what().length() > 110 ? f.what().substring(0, 109) + "…" : f.what());
            sb.append('\n').append(text.get(key, f.name(), items ? makerName(f.madeBy()) : orUnknown(f.madeBy()),
                String.valueOf(days), what));
        }
        if (found.size() > ROWS) sb.append('\n').append(text.get("archive.more", String.valueOf(found.size() - ROWS)));
    }

    private static String orUnknown(String s) { return s == null || s.isBlank() ? "?" : s; }

    /** A companion's name from her entity id, as the house knows her. */
    static String makerName(String entityId) {
        if (entityId == null || entityId.isBlank()) return "?";
        try {
            var reg = EntityRegistry.get();
            if (reg != null) return reg.nameOf(entityId).orElse(entityId);
        } catch (RuntimeException e) {
            log.debug("archive: no name for {}: {}", entityId, e.toString());
        }
        return entityId;
    }

    /**
     * Bring something back: a room or an item by its name (either tier), else, by what it was, the
     * one thing in the archive the words clearly mean. Several that fit are named back, not guessed
     * between. Cold storage answers to a name only.
     */
    public static Brought bringBack(String asked, String locale, boolean rooms, boolean items) {
        var text = ScriptMessageCatalog.forLang(lang(locale));
        var store = MadeThingsStore.shared();
        var closing = RoomClosing.get();
        if (store == null || asked == null || asked.isBlank()) {
            return new Brought(false, null, asked, text.get(store == null ? "archive.unavailable" : "archive.reopen.usage"), List.of());
        }
        var want = asked.strip();
        if (rooms && closing != null) {
            var byName = closing.findClosed(want).orElse(null);
            if (byName != null) return openRoom(closing, byName, text);
        }
        var allItems = items ? store.items() : List.<MadeThingsStore.ItemRecord>of();
        if (items) {
            var named = allItems.stream().filter(MadeThingsStore.ItemRecord::putAway)
                .filter(i -> i.shownName().equalsIgnoreCase(want)
                    || i.itemId().substring(i.itemId().indexOf('/') + 1).equalsIgnoreCase(want))
                .toList();
            if (named.size() == 1) return takeOut(store, named.getFirst(), text);
            if (named.size() > 1) {
                return several(want, MadeArchive.items(named, "", false), text);
            }
        }
        var found = new ArrayList<MadeArchive.Found>();
        if (rooms) found.addAll(MadeArchive.rooms(store.rooms(), want, false));
        if (items) found.addAll(MadeArchive.items(allItems, want, false));
        if (MadeArchive.words(want).isEmpty() || found.isEmpty()) {
            return new Brought(false, null, want, text.get("archive.reopen.none", want), List.of());
        }
        found.sort(java.util.Comparator.comparingInt(MadeArchive.Found::score).reversed());
        var every = MadeArchive.holdingEveryWord(found, want);
        var one = every.size() == 1 ? every.getFirst() : MadeArchive.clearly(found);
        if (one == null) return several(want, found, text);
        var room = closing == null ? null : store.room(one.id()).filter(MadeThingsStore.RoomRecord::closed).orElse(null);
        if (room != null) return openRoom(closing, room, text);
        for (var i : allItems) if (i.itemId().equals(one.id())) return takeOut(store, i, text);
        return new Brought(false, null, want, text.get("archive.reopen.none", want), List.of());
    }

    /**
     * The standing line in a companion's facts of her house: how many rooms of hers are closed up
     * and how many of her items are put away (cold storage is out of her everyday sight and is not
     * counted). Called whenever either number changes. Safe on any thread.
     */
    public static void refreshKept(String entityId, String makerName) {
        var store = MadeThingsStore.shared();
        if (store == null || entityId == null) return;
        try {
            int rooms = (int) store.rooms().stream()
                .filter(r -> r.closed() && !r.cold() && makerName != null && makerName.equalsIgnoreCase(r.createdBy())).count();
            int items = (int) store.items().stream()
                .filter(i -> i.putAway() && !i.cold() && entityId.equals(i.madeBy())).count();
            org.wyrdsekai.core.agent.HouseFacts.setKept(entityId, org.wyrdsekai.core.agent.HouseFacts.keptLine(rooms, items));
        } catch (RuntimeException e) {
            log.debug("archive: kept line not refreshed for {}: {}", entityId, e.toString());
        }
    }

    /** The same, for the maker of a room, known here by her name. */
    public static void refreshKeptForMaker(String makerName) {
        if (makerName == null || makerName.isBlank()) return;
        try {
            var entityId = org.wyrdsekai.core.soul.ForgeRoomBridge.resolveCompanionEntity(makerName);
            if (entityId != null) refreshKept(entityId, makerName);
        } catch (RuntimeException e) {
            log.debug("archive: no companion called {}: {}", makerName, e.toString());
        }
    }

    private static Brought several(String asked, List<MadeArchive.Found> found, ScriptMessageCatalog text) {
        var sb = new StringBuilder(text.get("archive.reopen.several", asked));
        for (var f : found.subList(0, Math.min(8, found.size()))) {
            var what = f.what() == null || f.what().isBlank() ? ""
                : " — " + (f.what().length() > 110 ? f.what().substring(0, 109) + "…" : f.what());
            sb.append("\n  ").append(f.name()).append(what);
        }
        return new Brought(false, null, asked, sb.toString(), List.copyOf(found));
    }

    private static Brought openRoom(RoomClosing closing, MadeThingsStore.RoomRecord rec, ScriptMessageCatalog text) {
        RoomClosing.Result res = null;
        try {
            res = closing.open(rec.roomId()).toCompletableFuture().get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Archive: {} did not open: {}", rec.roomId(), e.toString());
        }
        var name = rec.name() == null ? rec.roomId() : rec.name();
        if (res == null || !res.ok()) return new Brought(false, "room", name, text.get("archive.reopen.failed", name), List.of());
        refreshKeptForMaker(rec.createdBy());
        var msg = new StringBuilder(text.get("archive.reopen.room", name));
        var from = waysIn(rec);
        if (!from.isEmpty()) msg.append(text.get("archive.reopen.way", String.join(", ", from)));
        return new Brought(true, "room", name, msg.toString(), List.of());
    }

    /** The rooms its doorways are in, by name: where to walk to find it again. */
    static List<String> waysIn(MadeThingsStore.RoomRecord rec) {
        var out = new ArrayList<String>();
        var topo = ZoneTopology.getShared();
        for (var line : (rec.closedExits() == null ? "" : rec.closedExits()).split("\n")) {
            var parts = line.split("\t");
            if (parts.length < 2 || parts[0].isBlank()) continue;
            var name = topo == null ? parts[0] : topo.room(parts[0]).map(ZoneTopology.RoomNode::name).orElse(parts[0]);
            if (name != null && !out.contains(name)) out.add(name);
        }
        return out;
    }

    private static Brought takeOut(MadeThingsStore store, MadeThingsStore.ItemRecord item, ScriptMessageCatalog text) {
        store.itemTakenOut(item.itemId(), System.currentTimeMillis());
        var maker = item.itemId().contains("/") ? item.itemId().substring(0, item.itemId().indexOf('/')) : item.madeBy();
        refreshKept(maker, makerName(maker));
        try {
            var companion = ZoneGuardian.getCompanionRef(null, maker);
            if (companion != null) companion.tell(new CompanionActor.ItemsBroughtBack());
        } catch (RuntimeException e) {
            log.debug("archive: {} not told her item is back: {}", maker, e.toString());
        }
        return new Brought(true, "item", item.shownName(),
            text.get("archive.reopen.item", item.shownName(), makerName(maker)), List.of());
    }
}
