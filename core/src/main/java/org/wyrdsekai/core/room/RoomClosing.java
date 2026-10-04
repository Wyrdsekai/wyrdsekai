package org.wyrdsekai.core.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.persistence.MadeThingsStore;
import org.wyrdsekai.core.persistence.MadeThingsStore.RoomRecord;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Closing up a made room, and opening it again.
 *
 * <p>Closing up is not demolition. Every doorway into the room is closed (and remembered), the
 * room's actor is stopped, the room leaves the registry and the map. Its row stays, marked closed,
 * and so does its journal: what was in it is in it when it opens. The zone does not start a
 * closed room at boot. Opening it starts the actor again (it recovers from its journal), puts the
 * doorways back, and counts as someone having been in it.
 *
 * <p>The house closes up only what {@link RoomKeeping} says is a companion's own and untended. A
 * steward may close up or open any made room.
 */
public final class RoomClosing {

    private static final Logger log = LoggerFactory.getLogger(RoomClosing.class);
    private static final Duration ASK = Duration.ofSeconds(5);

    public record Result(boolean ok, String message, String roomId, String name, long quietDays) {}

    private static volatile RoomClosing INSTANCE;

    public static void install(RoomClosing c) { INSTANCE = c; }
    public static RoomClosing get() { return INSTANCE; }

    private final MadeThingsStore store;
    private final Set<String> protectedRooms;
    private final Consumer<ZoneGuardian.Command> guardian;

    public RoomClosing(MadeThingsStore store, Set<String> protectedRooms, Consumer<ZoneGuardian.Command> guardian) {
        this.store = store;
        this.protectedRooms = protectedRooms == null ? Set.of() : Set.copyOf(protectedRooms);
        this.guardian = guardian;
    }

    public Set<String> protectedRooms() { return protectedRooms; }
    public MadeThingsStore store() { return store; }

    /** The same slug the house cuts a room id from: letters and digits, the rest a dash. */
    static String slug(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    /** A closed-up room by its id or its name, however the name is written. */
    public Optional<RoomRecord> findClosed(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) return Optional.empty();
        // A doorway's own words name the room too: "to-test-nook-1283", "A path to Test Nook".
        var asked = nameOrId.strip();
        if (asked.toLowerCase(Locale.ROOT).startsWith("a path to ")) asked = asked.substring(10);
        if (asked.toLowerCase(Locale.ROOT).startsWith("to-")) asked = asked.substring(3);
        nameOrId = asked;
        var want = slug(nameOrId);
        var wantNoThe = want.startsWith("the-") ? want.substring(4) : want;
        for (var r : store.rooms()) {
            if (!r.closed()) continue;
            var name = slug(r.name());
            var nameNoThe = name.startsWith("the-") ? name.substring(4) : name;
            if (r.roomId().equalsIgnoreCase(nameOrId.strip()) || name.equals(want) || nameNoThe.equals(wantNoThe)
                    || r.roomId().toLowerCase(Locale.ROOT).startsWith(want + "-")) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** Whether a room holds more than it was furnished with. The first look at an older room sets what it had. */
    public CompletionStage<Boolean> holdsSomething(RoomRecord r) {
        try {
            return RoomRegistry.get().<RoomSnapshot>askRoom(r.roomId(), RoomCommand.GetSnapshot::new, ASK)
                .thenApply(snap -> {
                    int n = snap == null || snap.objects() == null ? 0 : snap.objects().size();
                    if (r.baselineObjects() == null) {
                        store.setBaseline(r.roomId(), n);
                        return false;
                    }
                    return n > r.baselineObjects();
                })
                .exceptionally(ex -> false);
        } catch (IllegalStateException e) {
            return CompletableFuture.completedFuture(false);
        }
    }

    /** An open made room by its id or its name, however the name is written. */
    public Optional<RoomRecord> findOpen(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) return Optional.empty();
        var byId = store.room(nameOrId.strip());
        if (byId.isPresent() && !byId.get().closed()) return byId;
        var want = slug(nameOrId);
        var wantNoThe = want.startsWith("the-") ? want.substring(4) : want;
        for (var r : store.rooms()) {
            if (r.closed()) continue;
            var name = slug(r.name());
            var nameNoThe = name.startsWith("the-") ? name.substring(4) : name;
            if (name.equals(want) || nameNoThe.equals(wantNoThe)) return Optional.of(r);
        }
        return Optional.empty();
    }

    /** Close up one room, by id or name. Refused when someone is in it, or when it is not a made room at all. */
    public CompletionStage<Result> closeUp(String nameOrId, String why) {
        var rec = findOpen(nameOrId).orElse(store.room(nameOrId).orElse(null));
        if (rec == null) return done(new Result(false, "No made room called '" + nameOrId + "'.", nameOrId, nameOrId, 0));
        final var roomId = rec.roomId();
        if (rec.closed()) return done(new Result(false, rec.name() + " is already closed up.", roomId, rec.name(), 0));
        if (roomId.startsWith("home-") || roomId.startsWith("study-") || WorkshopProvisioner.isWorkshop(roomId)
                || protectedRooms.contains(roomId)) {
            return done(new Result(false, rec.name() + " is not a room the house closes up.", roomId, rec.name(), 0));
        }
        long quietDays = RoomKeeping.quietFor(rec, System.currentTimeMillis()).toDays();
        CompletionStage<RoomSnapshot> snap;
        try {
            snap = RoomRegistry.get().<RoomSnapshot>askRoom(roomId, RoomCommand.GetSnapshot::new, ASK);
        } catch (IllegalStateException e) {
            return done(new Result(false, "That room is not running here (" + roomId + ").", roomId, rec.name(), quietDays));
        }
        return snap.thenCompose(snapshot -> {
            var who = RoomDemolition.occupants(snapshot);
            if (!who.isEmpty()) {
                return done(new Result(false, "Someone is in it — " + String.join(", ", who) + ".", roomId, rec.name(), quietDays));
            }
            var topo = ZoneTopology.getShared();
            var doorways = topo == null ? List.<Map.Entry<String, String>>of() : topo.exitsInto(roomId);
            var remembered = new StringBuilder();
            CompletionStage<Integer> closed = CompletableFuture.completedFuture(0);
            for (var d : doorways) {
                remembered.append(d.getKey()).append('\t').append(d.getValue()).append('\n');
                closed = closed.thenCompose(n -> closeDoorway(d.getKey(), d.getValue()).thenApply(ok -> n + (ok ? 1 : 0)));
            }
            return closed.thenApply(n -> {
                store.roomClosed(roomId, System.currentTimeMillis(), remembered.toString(), summaryOf(snapshot));
                if (guardian != null) guardian.accept(new ZoneGuardian.RetireRoom(roomId));
                ZoneTopology.forgetRoom(roomId);
                log.info("Room {} ('{}') closed up ({}) — {} doorway(s) closed; it is kept and can be opened again",
                    roomId, rec.name(), why, n);
                ArchiveCommands.refreshKeptForMaker(rec.createdBy());
                return new Result(true, "Closed up " + rec.name() + "; it is kept as it was.", roomId, rec.name(), quietDays);
            });
        });
    }

    /**
     * The note a closed-up room is found by later: what it was, and what was in it. Written at
     * closing, because a closed room is not running and cannot be looked into.
     */
    static String summaryOf(RoomSnapshot snap) {
        if (snap == null) return null;
        var sb = new StringBuilder();
        if (snap.description() != null && !snap.description().isBlank()) {
            var d = snap.description().strip().replaceAll("\\s+", " ");
            sb.append(d.length() > 400 ? d.substring(0, 399) + "…" : d);
        }
        var things = new ArrayList<String>();
        if (snap.objects() != null) {
            for (var o : snap.objects()) {
                if (o.name() != null && !o.name().isBlank() && things.size() < 12) things.add(o.name().strip());
            }
        }
        if (!things.isEmpty()) sb.append(sb.isEmpty() ? "" : " ").append("In it: ").append(String.join(", ", things)).append('.');
        return sb.isEmpty() ? null : sb.toString();
    }

    /** The archive (or cold storage), searched by ordinary words; everything in the tier when none are given. */
    public List<MadeArchive.Found> archive(String query, boolean cold) {
        return MadeArchive.rooms(store.rooms(), query, cold);
    }

    /**
     * Closed-up rooms that have been in the archive past the setting go to cold storage: still kept,
     * out of the everyday list and the loose search, opened by name. Returns how many went.
     */
    public int coldPass(int coldDays) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (var r : store.rooms()) {
            if (r.closed() && MadeArchive.dueForCold(r.closedAt(), r.coldAt(), coldDays, now)) {
                store.roomCold(r.roomId(), now);
                ArchiveCommands.refreshKeptForMaker(r.createdBy());
                log.info("Room {} ('{}') went to cold storage: closed up more than {} days; it is kept and opens by name",
                    r.roomId(), r.name(), coldDays);
                n++;
            }
        }
        return n;
    }

    /** Open a closed-up room again, by id or name: the actor, then its doorways. */
    public CompletionStage<Result> open(String nameOrId) {
        var rec = findClosed(nameOrId).orElse(null);
        if (rec == null) return done(new Result(false, "No closed-up room called '" + nameOrId + "'.", null, nameOrId, 0));
        var roomId = rec.roomId();
        store.roomOpened(roomId, System.currentTimeMillis());
        if (guardian != null) guardian.accept(new ZoneGuardian.ReopenRoom(roomId));
        return CompletableFuture.supplyAsync(() -> {
            for (int i = 0; i < 40 && RoomRegistry.get().ref(roomId) == null; i++) {
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            return RoomRegistry.get().ref(roomId) != null;
        }).thenCompose(up -> {
            if (!up) {
                return done(new Result(false, rec.name() + " did not start again.", roomId, rec.name(), 0));
            }
            CompletionStage<Integer> opened = CompletableFuture.completedFuture(0);
            for (var line : (rec.closedExits() == null ? "" : rec.closedExits()).split("\n")) {
                var parts = line.split("\t");
                if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) continue;
                opened = opened.thenCompose(n -> openDoorway(parts[0], parts[1], roomId, rec.name()).thenApply(ok -> n + (ok ? 1 : 0)));
            }
            return opened.thenApply(n -> {
                ArchiveCommands.refreshKeptForMaker(rec.createdBy());
                log.info("Room {} ('{}') opened again — {} doorway(s) back", roomId, rec.name(), n);
                return new Result(true, "Opened " + rec.name() + " again.", roomId, rec.name(), 0);
            });
        });
    }

    /**
     * Close up the rooms of one maker that are hers alone and have been quiet past the setting.
     * Returns what was closed, for the maker to be told.
     */
    public CompletionStage<List<Result>> pass(String maker, RoomKeeping.Settings settings) {
        long now = System.currentTimeMillis();
        return dueFor(maker, settings).thenCompose(due -> {
            var closedNow = new ArrayList<Result>();
            CompletionStage<List<Result>> chain = CompletableFuture.completedFuture(closedNow);
            for (var r : due) {
                chain = chain.thenCompose(list ->
                    closeUp(r.roomId(), "quiet " + RoomKeeping.days(RoomKeeping.quietFor(r, now))).thenApply(res -> {
                        if (res.ok()) list.add(res);
                        return list;
                    }));
            }
            return chain;
        });
    }

    /** The rooms of one maker that the pass would close up now, without closing anything. */
    public CompletionStage<List<RoomRecord>> dueFor(String maker, RoomKeeping.Settings settings) {
        var due = new ArrayList<RoomRecord>();
        CompletionStage<List<RoomRecord>> chain = CompletableFuture.completedFuture(due);
        if (maker == null || maker.isBlank()) return chain;
        long now = System.currentTimeMillis();
        for (var r : RoomKeeping.openOwn(store.rooms(), maker, protectedRooms)) {
            chain = chain.thenCompose(list -> holdsSomething(r).thenApply(holds -> {
                if (RoomKeeping.due(r, holds, settings, now)) list.add(r);
                return list;
            }));
        }
        return chain;
    }

    /** The sentence the maker is told, once, when a room of hers is closed up. */
    public static String toldLine(Result r) {
        return r.name() + " was closed up: nobody had been in it for " + (r.quietDays() == 1 ? "1 day" : r.quietDays() + " days")
            + ". It is kept as it was. Go to it by name, or by what it was, and it opens again.";
    }

    /** One sentence for several rooms closed in one pass: the first few by name, the rest counted. */
    public static String toldLine(List<Result> closed) {
        if (closed == null || closed.isEmpty()) return null;
        if (closed.size() == 1) return toldLine(closed.getFirst());
        var shown = new ArrayList<String>();
        for (var r : closed.subList(0, Math.min(5, closed.size()))) {
            shown.add(r.name() + " (" + (r.quietDays() == 1 ? "1 day" : r.quietDays() + " days") + ")");
        }
        return closed.size() + " rooms of yours were closed up, because nobody had been in them for a long while: "
            + String.join(", ", shown) + (closed.size() > 5 ? ", and " + (closed.size() - 5) + " more" : "")
            + ". They are kept as they were. Go to one by name, or by what it was, and it opens again.";
    }

    /** She is told once: in her next turn, and as a mark that waits if she is away. Safe on any thread. */
    public static void tell(String entityId, List<Result> closed) {
        var told = toldLine(closed);
        if (told == null || entityId == null) return;
        org.wyrdsekai.core.agent.HouseFacts.noteForNextTurn(entityId, told);
        try {
            var map = org.wyrdsekai.core.body.BodyMap.get();
            if (map != null) map.mark("room", closed.size() == 1 ? closed.getFirst().roomId() : "rooms", entityId, told, "closed_up");
        } catch (RuntimeException e) {
            log.debug("rooms closed up: no body mark: {}", e.toString());
        }
    }

    private CompletionStage<Boolean> closeDoorway(String source, String direction) {
        try {
            return RoomRegistry.get().<RoomResponse>askRoom(source, ref -> new RoomCommand.RemoveExit(direction, ref), ASK)
                .thenApply(r -> r instanceof RoomResponse.Ok)
                .exceptionally(ex -> false);
        } catch (IllegalStateException e) {
            return CompletableFuture.completedFuture(false);
        }
    }

    private CompletionStage<Boolean> openDoorway(String source, String direction, String roomId, String name) {
        var label = "A path to " + name;
        try {
            return RoomRegistry.get().<RoomResponse>askRoom(source,
                    ref -> new RoomCommand.AddExit(direction, roomId, label, ref), ASK)
                .thenApply(r -> {
                    boolean ok = r instanceof RoomResponse.Ok;
                    if (ok) ZoneTopology.learnExit(source, direction, roomId, label);
                    return ok;
                })
                .exceptionally(ex -> false);
        } catch (IllegalStateException e) {
            return CompletableFuture.completedFuture(false);
        }
    }

    private static CompletionStage<Result> done(Result r) {
        return CompletableFuture.completedFuture(r);
    }
}
