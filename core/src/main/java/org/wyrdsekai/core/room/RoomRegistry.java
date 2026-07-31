package org.wyrdsekai.core.room;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.RecipientRef;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.javadsl.AskPattern;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Registry of room actor refs by room ID, with alias-based resolution.
 *
 * Replaces ClusterSharding's entityRefFor(). Rooms are spawned as plain
 * child actors by ZoneGuardian and registered here.
 *
 * Resolution order for {@link #resolve(String)}:
 * 1. Exact roomId match
 * 2. Exact alias match (case-insensitive)
 * 3. Partial alias match (contains)
 */
public final class RoomRegistry {

    private static final RoomRegistry INSTANCE = new RoomRegistry();

    private final ConcurrentHashMap<String, ActorRef<RoomCommand>> rooms = new ConcurrentHashMap<>();
    /** alias (lowercase) → roomId. Multiple aliases can map to same room. */
    private final ConcurrentHashMap<String, String> aliasIndex = new ConcurrentHashMap<>();
    private volatile Scheduler scheduler;

    private RoomRegistry() {}

    public static RoomRegistry get() {
        return INSTANCE;
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** Get the room ActorRef by exact roomId. Returns null if room not registered. */
    public ActorRef<RoomCommand> ref(String roomId) {
        return rooms.get(roomId);
    }

    /**
     * Resolve a room by roomId or alias.
     * Tries exact roomId first, then alias index.
     * Returns null if no match.
     */
    public ActorRef<RoomCommand> resolve(String query) {
        if (query == null) return null;
        // 1. Exact roomId
        var direct = rooms.get(query);
        if (direct != null) return direct;
        // 2. Alias lookup (case-insensitive), then the same unambiguous partial
        // fallback resolveRoomId applies — one resolution, not two.
        var aliasRoomId = resolveRoomId(query);
        if (aliasRoomId != null) return rooms.get(aliasRoomId);
        return null;
    }

    /**
     * Resolve a query to a roomId (not ActorRef).
     * Tries exact roomId first, then alias index.
     */
    public String resolveRoomId(String query) {
        if (query == null) return null;
        if (rooms.containsKey(query)) return query;
        var exact = aliasIndex.get(query.toLowerCase());
        if (exact != null) return exact;
        // UNAMBIGUOUS partial fallback, exact-first preserved (the codebase's
        // established pattern — examine went exact-match-first the same way).
        //
        // Live on home-server 2026-07-30: a bunshin's builtin passed connect_to:"Study";
        // the alias is the FULL name "steward's Study", so exact lookup missed,
        // addExit was rejected, and the furnished sunroom was created with no way
        // in. A person (or model) reaching for a room by part of its name is the
        // normal case, not an error — but only when it names ONE room. Two or
        // more candidates return null rather than guess.
        var needle = query.toLowerCase().trim();
        if (needle.length() < 3) return null;   // "a"/"to" must never match a room
        String found = null;
        for (var e : aliasIndex.entrySet()) {
            if (e.getKey().contains(needle)) {
                if (found != null && !found.equals(e.getValue())) return null;
                found = e.getValue();
            }
        }
        for (var id : rooms.keySet()) {
            if (id.toLowerCase().contains(needle)) {
                if (found != null && !found.equals(id)) return null;
                found = id;
            }
        }
        return found;
    }

    /**
     * Ask a room for a response (replacement for EntityRef.ask()).
     * Callers use: RoomRegistry.get().askRoom(roomId, factory, timeout)
     */
    public <Res> CompletionStage<Res> askRoom(String roomId,
            Function<ActorRef<Res>, RoomCommand> factory, Duration timeout) {
        var ref = rooms.get(roomId);
        if (ref == null) throw new IllegalStateException("Room not found: " + roomId);
        return AskPattern.<RoomCommand, Res>ask(ref, factory::apply, timeout, scheduler);
    }

    public void register(String roomId, ActorRef<RoomCommand> ref) {
        rooms.put(roomId, ref);
    }

    /** Register aliases for a room. Aliases are stored lowercase for case-insensitive lookup. */
    public void registerAliases(String roomId, List<String> aliases) {
        if (aliases == null) return;
        for (var alias : aliases) {
            if (alias != null && !alias.isBlank()) {
                aliasIndex.put(alias.toLowerCase(), roomId);
            }
        }
    }

    /** Register room and its aliases in one call. */
    public void register(String roomId, ActorRef<RoomCommand> ref, List<String> aliases) {
        rooms.put(roomId, ref);
        registerAliases(roomId, aliases);
    }

    public void remove(String roomId) {
        rooms.remove(roomId);
        // Remove any aliases pointing to this roomId
        aliasIndex.entrySet().removeIf(e -> e.getValue().equals(roomId));
    }

    public int size() {
        return rooms.size();
    }

    public Set<String> roomIds() {
        return Collections.unmodifiableSet(rooms.keySet());
    }

    /** Get all registered aliases. */
    public Map<String, String> aliases() {
        return Collections.unmodifiableMap(aliasIndex);
    }

    /** Clear all room registrations and aliases. Used between test runs. */
    public void clear() {
        rooms.clear();
        aliasIndex.clear();
    }
}
