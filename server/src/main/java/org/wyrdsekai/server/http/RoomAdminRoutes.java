package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import org.wyrdsekai.core.item.HouseholdItems;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.RoomMetadataService;
import org.wyrdsekai.core.room.RoomDemolition;
import org.wyrdsekai.core.room.RoomNaming;
import org.wyrdsekai.core.room.ZoneTopology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * The steward's view of the rooms and the door to demolition — what {@code wyrd rooms}
 * calls. {@code GET /api/rooms} lists every room with who made it, its doorways, who is in
 * it and whether its id carries leaked markup; {@code DELETE /api/rooms/{id}} demolishes one;
 * {@code POST /api/rooms/prune} demolishes the junk (markup ids, and duplicates by name when
 * asked), or only lists what it would do with {@code dry=true}.
 */
public final class RoomAdminRoutes {

    private final AuthService auth;
    private final RoomMetadataService metadata;
    private final Set<String> protectedRooms;

    /** The companions of this node: name → entity id. */
    private java.util.function.Supplier<Map<String, String>> companions = Map::of;

    public RoomAdminRoutes(AuthService auth, RoomMetadataService metadata, Set<String> protectedRooms,
                           java.util.function.Supplier<Map<String, String>> companions) {
        this(auth, metadata, protectedRooms);
        if (companions != null) this.companions = companions;
    }

    public RoomAdminRoutes(AuthService auth, RoomMetadataService metadata, Set<String> protectedRooms) {
        this.auth = auth;
        this.metadata = metadata;
        this.protectedRooms = protectedRooms == null ? Set.of() : Set.copyOf(protectedRooms);
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/rooms", this::handleList);
        app.delete("/api/rooms/{roomId}", this::handleDemolish);
        app.post("/api/rooms/prune", this::handlePrune);
        app.get("/api/rooms/made", this::handleMade);
        app.get("/api/items/made", this::handleItemsMade);
        app.post("/api/items/made/out", this::handleItemOut);
        app.post("/api/items/made/away", this::handleItemAway);
        app.get("/api/rooms/archive", this::handleArchive);
        app.post("/api/rooms/tidy", this::handleTidy);
        app.post("/api/rooms/{roomId}/keep", this::handleKeep);
        app.post("/api/rooms/{roomId}/close", this::handleCloseUp);
        app.post("/api/rooms/{roomId}/open", this::handleOpen);
    }

    record ErrorResponse(String error) {}

    /** One room as the steward sees it. {@code lastActivityAt} is the newest event in its journal, 0 when not known. */
    public record RoomRow(String roomId, String name, String madeBy, long createdAt,
                          int doorways, List<String> occupants, boolean markupId, boolean protectedRoom,
                          long lastActivityAt) {
        public RoomRow(String roomId, String name, String madeBy, long createdAt,
                       int doorways, List<String> occupants, boolean markupId, boolean protectedRoom) {
            this(roomId, name, madeBy, createdAt, doorways, occupants, markupId, protectedRoom, 0L);
        }
    }
    static final long DAY_MS = 24L * 60 * 60 * 1000;

    private void handleList(Context ctx) {
        ctx.json(rows());
    }

    /**
     * A made room as the steward sees it: who it was made for, how long since anyone was in it,
     * whether the house holds it and why, whether it is closed up.
     */
    public record MadeRow(String roomId, String name, String madeBy, String madeFor, long quietDays,
                          boolean aPersonHasBeenIn, boolean closed, long closedDays, String held, boolean cold) {}

    /** The rows of {@code GET /api/rooms/made}, from the store's records. Pure. */
    static List<MadeRow> madeRows(List<org.wyrdsekai.core.persistence.MadeThingsStore.RoomRecord> records,
                                  Set<String> protectedRooms, Set<String> companions, long now) {
        var out = new ArrayList<MadeRow>();
        for (var r : records) {
            var held = org.wyrdsekai.core.room.RoomKeeping.heldBecause(r, protectedRooms, companions);
            // Founding rooms, Homes and Studies are not "made rooms" for this list.
            if (held != null && !held.equals("a person asked for it") && !held.equals("a person has been in it")
                    && !held.equals("nobody recorded who it was made for")) continue;
            var forWhom = r.madeFor() == null ? "not recorded"
                : org.wyrdsekai.core.persistence.MadeThingsStore.OWN.equals(r.madeFor()) ? "her own" : "a person";
            out.add(new MadeRow(r.roomId(), r.name(), r.createdBy(), forWhom,
                org.wyrdsekai.core.room.RoomKeeping.quietFor(r, now).toDays(), r.aPersonHasBeenIn(), r.closed(),
                r.closed() ? Math.max(0, (now - r.closedAt()) / DAY_MS) : 0, held == null ? "" : held, r.cold()));
        }
        return out;
    }

    private void handleMade(Context ctx) {
        if (requireSteward(ctx) == null) return;
        var closing = org.wyrdsekai.core.room.RoomClosing.get();
        if (closing == null) { ctx.status(503).json(new ErrorResponse("room keeping not available")); return; }
        var cfg = org.wyrdsekai.core.config.WyrdConfig.get();
        ctx.json(Map.of("rooms", madeRows(closing.store().rooms(), protectedRooms, companions.get().keySet(), System.currentTimeMillis()),
            "emptyDays", cfg.madeRoomEmptyDays(), "heldDays", cfg.madeRoomHeldDays(), "openMax", cfg.madeRoomsOpenMax()));
    }

    /** A made item as the steward sees it. */
    public record MadeItemRow(String item, String madeBy, String madeFor, long unusedDays,
                              boolean aPersonHasUsed, boolean putAway, String held, boolean cold) {}

    /** The rows of {@code GET /api/items/made}. Pure. */
    static List<MadeItemRow> madeItemRows(List<org.wyrdsekai.core.persistence.MadeThingsStore.ItemRecord> records, long now) {
        var out = new ArrayList<MadeItemRow>();
        for (var i : records) {
            var forWhom = i.madeFor() == null ? "not recorded"
                : org.wyrdsekai.core.persistence.MadeThingsStore.OWN.equals(i.madeFor()) ? "her own" : "a person";
            var held = i.askedFor() ? "a person asked for it" : i.aPersonHasUsed() ? "it has been used for a person" : "";
            out.add(new MadeItemRow(i.shownName(), i.madeBy(), forWhom,
                org.wyrdsekai.core.item.ItemKeeping.unusedFor(i, now).toDays(), i.aPersonHasUsed(), i.putAway(), held, i.cold()));
        }
        return out;
    }

    /**
     * The steward takes a put-away item out again: {@code ?item=<name>} and, when two companions have
     * one of that name, {@code &of=<companion entity id>}. It is back on her menu when she next names
     * it, or after her next sleep.
     */
    /**
     * The steward puts a household item away: {@code ?item=<name>}. Its file moves beside the items
     * folder, the loader drops it on its next look and every companion's menu loses it; it is kept,
     * and {@code wyrd items out} brings it back. Twelve "check" tools the workshop built from one
     * sentence sat on both menus with no way off them (household node, 2026-10-27).
     */
    private void handleItemAway(Context ctx) {
        if (requireSteward(ctx) == null) return;
        var item = ctx.queryParam("item");
        if (item == null || item.isBlank()) { ctx.status(400).json(new ErrorResponse("item=<name> is required")); return; }
        var done = HouseholdItems.putAway(item.strip());
        if (done.isEmpty()) {
            ctx.status(404).json(Map.of("ok", false, "message", "No household item called '" + item + "'. wyrd items made lists the made ones; "
                + "put away by name only what is in the items folder."));
            return;
        }
        ctx.json(Map.of("ok", true, "message", done.get()));
    }

    private void handleItemOut(Context ctx) {
        if (requireSteward(ctx) == null) return;
        var item = ctx.queryParam("item");
        var of = ctx.queryParam("of");
        if (item == null || item.isBlank()) { ctx.status(400).json(new ErrorResponse("item=<name> is required")); return; }
        // A household item put away by wyrd items away comes back the same way.
        if (of == null || of.isBlank()) {
            var back = HouseholdItems.takeOut(item.strip());
            if (back.isPresent()) { ctx.json(Map.of("ok", true, "message", back.get())); return; }
        }
        var store = org.wyrdsekai.core.persistence.MadeThingsStore.shared();
        if (store == null) { ctx.status(503).json(new ErrorResponse("item keeping not available")); return; }
        if (of == null || of.isBlank()) {
            var b = org.wyrdsekai.core.room.ArchiveCommands.bringBack(item, "en", false, true);
            if (b.ok()) { ctx.json(Map.of("ok", true, "message", b.message())); return; }
            if (b.several().isEmpty()) { ctx.status(404).json(Map.of("ok", false, "message", b.message())); return; }
        }
        var matches = store.items().stream()
            .filter(org.wyrdsekai.core.persistence.MadeThingsStore.ItemRecord::putAway)
            .filter(i -> i.itemId().substring(i.itemId().indexOf('/') + 1).equalsIgnoreCase(item.strip())
                || i.shownName().equalsIgnoreCase(item.strip()))
            .filter(i -> of == null || of.isBlank() || i.madeBy().equalsIgnoreCase(of.strip()))
            .toList();
        if (matches.isEmpty()) { ctx.status(404).json(Map.of("ok", false, "message", "No put-away item called '" + item + "'.")); return; }
        if (matches.isEmpty() && (of == null || of.isBlank())) {
            ctx.status(409).json(Map.of("ok", false, "message",
                org.wyrdsekai.core.room.ArchiveCommands.bringBack(item, "en", false, true).message()));
            return;
        }
        if (matches.size() > 1) {
            ctx.status(409).json(Map.of("ok", false, "message", "More than one companion has a put-away item called '" + item
                + "': add --of " + String.join(" or --of ", matches.stream().map(org.wyrdsekai.core.persistence.MadeThingsStore.ItemRecord::madeBy).toList())));
            return;
        }
        var brought = org.wyrdsekai.core.room.ArchiveCommands.bringBack(matches.getFirst().shownName(), "en", false, true);
        if (!brought.ok()) {
            // Two companions hold one of this name: the one asked for is taken out by its own record.
            store.itemTakenOut(matches.getFirst().itemId(), System.currentTimeMillis());
            var ref = org.wyrdsekai.core.room.ZoneGuardian.getCompanionRef(null, matches.getFirst().madeBy());
            if (ref != null) ref.tell(new org.wyrdsekai.core.agent.CompanionActor.ItemsBroughtBack());
        }
        ctx.json(Map.of("ok", true, "message", brought.ok() ? brought.message() : item + " is out again. She has it back."));
    }

    /** One thing in the archive, as the steward's search shows it. */
    public record ArchiveRow(String name, String id, String madeBy, long days, String what) {}

    static List<ArchiveRow> archiveRows(List<org.wyrdsekai.core.room.MadeArchive.Found> found, long now) {
        return archiveRows(found, now, Map.of());
    }

    /** {@code names}: a companion's entity id to her name, so an item's maker reads as a room's does. */
    static List<ArchiveRow> archiveRows(List<org.wyrdsekai.core.room.MadeArchive.Found> found, long now, Map<String, String> names) {
        var out = new ArrayList<ArchiveRow>();
        for (var f : found) {
            out.add(new ArchiveRow(f.name(), f.id(), f.madeBy() == null ? "" : names.getOrDefault(f.madeBy(), f.madeBy()),
                Math.max(0, (now - f.archivedAtMs()) / DAY_MS), f.what() == null ? "" : f.what()));
        }
        return out;
    }

    /**
     * The archive, searched: {@code ?q=<words>} by what a thing was (all of the tier when empty),
     * {@code &cold=true} for cold storage. Rooms and items both.
     */
    private void handleArchive(Context ctx) {
        if (requireSteward(ctx) == null) return;
        var cold = "true".equalsIgnoreCase(ctx.queryParam("cold"));
        var q = ctx.queryParam("q");
        var found = org.wyrdsekai.core.room.ArchiveCommands.find(q == null ? "" : q, cold);
        if (found == null) { ctx.status(503).json(new ErrorResponse("the archive is not kept on this node")); return; }
        long now = System.currentTimeMillis();
        var names = new java.util.HashMap<String, String>();
        for (var c : companions.get().entrySet()) names.put(c.getValue(), c.getKey());
        ctx.json(Map.of("cold", cold, "query", found.query(), "rooms", archiveRows(found.rooms(), now, names),
            "items", archiveRows(found.items(), now, names), "coldRooms", found.coldRooms(), "coldItems", found.coldItems(),
            "coldDays", org.wyrdsekai.core.config.WyrdConfig.get().madeColdDays()));
    }

    private void handleItemsMade(Context ctx) {
        if (requireSteward(ctx) == null) return;
        var store = org.wyrdsekai.core.persistence.MadeThingsStore.shared();
        if (store == null) { ctx.status(503).json(new ErrorResponse("item keeping not available")); return; }
        var cfg = org.wyrdsekai.core.config.WyrdConfig.get();
        ctx.json(Map.of("items", madeItemRows(store.items(), System.currentTimeMillis()),
            "unusedDays", cfg.madeItemUnusedDays(), "outMax", cfg.madeItemsOutMax(), "firstLookDays", cfg.madeFirstLookDays()));
    }

    /**
     * Close up, now, every room the nightly pass would: each companion's own rooms that nobody has
     * entered past the setting. {@code dry=true} only lists them. Each companion is told what was closed.
     */
    private void handleTidy(Context ctx) {
        if (requireSteward(ctx) == null) return;
        var closing = org.wyrdsekai.core.room.RoomClosing.get();
        if (closing == null) { ctx.status(503).json(new ErrorResponse("room keeping not available")); return; }
        boolean dry = !"false".equalsIgnoreCase(ctx.queryParam("dry"));
        var cfg = org.wyrdsekai.core.config.WyrdConfig.get();
        var settings = new org.wyrdsekai.core.room.RoomKeeping.Settings(
            cfg.madeRoomEmptyDays(), cfg.madeRoomHeldDays(), cfg.madeRoomsOpenMax());
        long now = System.currentTimeMillis();
        var rows = new ArrayList<Map<String, Object>>();
        try {
            for (var c : companions.get().entrySet()) {
                if (dry) {
                    for (var r : closing.dueFor(c.getKey(), settings).toCompletableFuture().get(120, TimeUnit.SECONDS)) {
                        rows.add(Map.of("maker", c.getKey(), "roomId", r.roomId(), "name", r.name() == null ? r.roomId() : r.name(),
                            "quietDays", org.wyrdsekai.core.room.RoomKeeping.quietFor(r, now).toDays()));
                    }
                } else {
                    var closed = closing.pass(c.getKey(), settings).toCompletableFuture().get(300, TimeUnit.SECONDS);
                    org.wyrdsekai.core.room.RoomClosing.tell(c.getValue(), closed);
                    for (var r : closed) {
                        rows.add(Map.of("maker", c.getKey(), "roomId", r.roomId(), "name", r.name(), "quietDays", r.quietDays()));
                    }
                }
            }
        } catch (Exception e) {
            ctx.status(500).json(new ErrorResponse("tidy did not finish: " + e.getMessage()));
            return;
        }
        ctx.json(Map.of("dry", dry, "rooms", rows, "emptyDays", cfg.madeRoomEmptyDays(), "heldDays", cfg.madeRoomHeldDays()));
    }

    /** The steward says a made room is theirs to keep: the house holds it from then on. Open or closed up. */
    private void handleKeep(Context ctx) {
        var who = requireSteward(ctx);
        if (who == null) return;
        var closing = org.wyrdsekai.core.room.RoomClosing.get();
        if (closing == null) { ctx.status(503).json(new ErrorResponse("room keeping not available")); return; }
        var asked = ctx.pathParam("roomId");
        var rec = closing.findOpen(asked).or(() -> closing.findClosed(asked)).orElse(null);
        if (rec == null) { ctx.status(404).json(Map.of("ok", false, "message", "No made room called '" + asked + "'.")); return; }
        boolean ok = closing.store().keepRoom(rec.roomId(), who);
        ctx.status(ok ? 200 : 409).json(Map.of("ok", ok, "message", ok
            ? rec.name() + " is kept: the house will not close it up." + (rec.closed() ? " It is closed up now; open it with: wyrd rooms open" : "")
            : rec.name() + " could not be marked."));
    }

    private void handleCloseUp(Context ctx) {
        if (requireSteward(ctx) == null) return;
        var closing = org.wyrdsekai.core.room.RoomClosing.get();
        if (closing == null) { ctx.status(503).json(new ErrorResponse("room keeping not available")); return; }
        var r = awaitClosing(closing.closeUp(ctx.pathParam("roomId"), "by the steward"));
        ctx.status(r.ok() ? 200 : 409).json(Map.of("ok", r.ok(), "message", r.message()));
    }

    private void handleOpen(Context ctx) {
        if (requireSteward(ctx) == null) return;
        var closing = org.wyrdsekai.core.room.RoomClosing.get();
        if (closing == null) { ctx.status(503).json(new ErrorResponse("room keeping not available")); return; }
        var b = org.wyrdsekai.core.room.ArchiveCommands.bringBack(ctx.pathParam("roomId"), "en", true, false);
        ctx.status(b.ok() ? 200 : 409).json(Map.of("ok", b.ok(), "message", b.message()));
    }

    private static org.wyrdsekai.core.room.RoomClosing.Result awaitClosing(
            CompletionStage<org.wyrdsekai.core.room.RoomClosing.Result> stage) {
        try {
            return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new org.wyrdsekai.core.room.RoomClosing.Result(false, "did not finish: " + e.getMessage(), null, null, 0);
        }
    }

    private void handleDemolish(Context ctx) {
        if (requireSteward(ctx) == null) return;
        var demolition = RoomDemolition.get();
        if (demolition == null) { ctx.status(503).json(new ErrorResponse("demolition not available")); return; }
        boolean withObjects = "true".equalsIgnoreCase(ctx.queryParam("with_objects"));
        var result = await(demolition.demolish(ctx.pathParam("roomId"), "steward", withObjects));
        ctx.status(result.ok() ? 200 : 409).json(Map.of("ok", result.ok(), "message", result.message(),
            "roomId", result.roomId() == null ? "" : result.roomId(), "doorwaysClosed", result.doorwaysClosed()));
    }

    private void handlePrune(Context ctx) {
        if (requireSteward(ctx) == null) return;
        boolean dry = "true".equalsIgnoreCase(ctx.queryParam("dry"));
        boolean duplicates = "true".equalsIgnoreCase(ctx.queryParam("duplicates"));
        boolean withObjects = "true".equalsIgnoreCase(ctx.queryParam("with_objects"));
        int staleDays = 0;
        try { staleDays = Integer.parseInt(ctx.queryParam("stale_days") == null ? "0" : ctx.queryParam("stale_days")); } catch (NumberFormatException ignored) { }
        var plan = prunePlan(rows(), duplicates, staleDays, System.currentTimeMillis());
        if (dry) { ctx.json(Map.of("dry", true, "rooms", plan, "now", System.currentTimeMillis())); return; }
        var demolition = RoomDemolition.get();
        if (demolition == null) { ctx.status(503).json(new ErrorResponse("demolition not available")); return; }
        var results = new ArrayList<Map<String, Object>>();
        for (var row : plan) {
            var r = await(demolition.demolish(row.roomId(), "steward", withObjects));
            results.add(Map.of("roomId", row.roomId(), "name", row.name(), "ok", r.ok(), "message", r.message()));
        }
        ctx.json(Map.of("dry", false, "results", results));
    }

    /** What prune would take down: markup ids always; later duplicates of a name when asked. Pure. */
    static List<RoomRow> prunePlan(List<RoomRow> rooms, boolean duplicates) {
        return prunePlan(rooms, duplicates, 0, 0L);
    }

    /**
     * As above, plus the stale rule when {@code staleDays > 0}: a made room (never a protected
     * one), nobody in it, whose newest journal event is at least that many days before {@code now}.
     * A room whose activity is not known (no journal seen) is never called stale. Pure.
     */
    static List<RoomRow> prunePlan(List<RoomRow> rooms, boolean duplicates, int staleDays, long now) {
        var out = new ArrayList<RoomRow>();
        var firstByName = new HashMap<String, RoomRow>();
        var sorted = new ArrayList<>(rooms);
        sorted.sort((a, b) -> Long.compare(a.createdAt(), b.createdAt()));
        for (var r : sorted) {
            if (r.protectedRoom()) continue;
            if (r.markupId()) { out.add(r); continue; }
            if (staleDays > 0 && r.lastActivityAt() > 0 && r.occupants().isEmpty()
                    && now - r.lastActivityAt() >= staleDays * DAY_MS) { out.add(r); continue; }
            if (!duplicates) continue;
            var key = RoomNaming.normalise(r.name());
            if (key.isEmpty()) continue;
            var first = firstByName.putIfAbsent(key, r);
            if (first != null) out.add(r);
        }
        return out;
    }

    private List<RoomRow> rows() {
        var topo = ZoneTopology.getShared();
        var registry = EntityRegistry.get();
        var occupants = new HashMap<String, List<String>>();
        if (registry != null) {
            for (var e : registry.occupantsByRoom().entrySet()) {
                occupants.put(e.getKey(), e.getValue().stream().map(EntityRegistry.Occupant::name).toList());
            }
        }
        var info = new LinkedHashMap<String, RoomMetadataService.RoomInfo>();
        if (metadata != null) for (var i : metadata.listRooms()) info.put(i.roomId(), i);
        var activity = metadata == null ? Map.<String, Long>of() : metadata.lastActivityByRoom();
        var out = new ArrayList<RoomRow>();
        if (topo == null) return out;
        for (var node : topo.rooms().values()) {
            var i = info.get(node.roomId());
            var madeBy = i == null || i.createdBy() == null ? null : i.createdBy();
            // Seeded rooms carry created_by = "system" on real nodes, the same as rooms a
            // companion made — the seed set is what tells them apart.
            boolean protectedRoom = madeBy == null || protectedRooms.contains(node.roomId())
                || node.roomId().startsWith("home-") || node.roomId().startsWith("study-")
                || org.wyrdsekai.core.room.WorkshopProvisioner.isWorkshop(node.roomId());
            out.add(new RoomRow(node.roomId(), node.name(), madeBy == null ? "founding" : madeBy,
                i == null ? 0 : i.createdAt(), node.exits().size(),
                occupants.getOrDefault(node.roomId(), List.of()),
                RoomNaming.looksLikeMarkupId(node.roomId()), protectedRoom,
                activity.getOrDefault(node.roomId(), 0L)));
        }
        return out;
    }

    private static RoomDemolition.Result await(CompletionStage<RoomDemolition.Result> stage) {
        try {
            return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new RoomDemolition.Result(false, "demolition did not finish: " + e.getMessage(), null, 0);
        }
    }

    private String requireSteward(Context ctx) {
        // The node's own launcher, run by the person who looks after the machine, is the steward's hand.
        if (ApiAuth.isOperator(ctx)) return "operator";
        var token = AuthRoutes.extractToken(ctx);
        if (token == null) { ctx.status(401).json(new ErrorResponse("Authentication required")); return null; }
        var user = auth.validateSession(token);
        if (user.isEmpty()) { ctx.status(401).json(new ErrorResponse("Invalid or expired session")); return null; }
        if (!"steward".equals(user.get().role())) { ctx.status(403).json(new ErrorResponse("Only the steward demolishes rooms")); return null; }
        return user.get().id();
    }
}
