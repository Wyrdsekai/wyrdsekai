package org.wyrdsekai.core.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.persistence.RoomMetadataService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * A steward takes a made room down.
 *
 * <p>Until 2026-09-14 nothing could: a household node had 93 rooms, 13 of them made by a
 * companion from a name the model never finished (six carried raw tool-call markup), several
 * made twice, and the Nexus listed forty exits. The rules are small and pure so they can be
 * tested without actors:</p>
 * <ul>
 *   <li>a founding room (seeded, or with no record of who made it) is never demolished;</li>
 *   <li>a companion's Home is hers and is never demolished;</li>
 *   <li>an occupied room is refused — whoever is in it is asked to leave first.</li>
 * </ul>
 * <p>Otherwise every doorway into the room is closed (persisted in each neighbour), the
 * actor is stopped, the room leaves the registry, the topology and the metadata table. The
 * journal stays; nothing respawns a room that has no metadata row.</p>
 */
public final class RoomDemolition {

    private static final Logger log = LoggerFactory.getLogger(RoomDemolition.class);
    private static final Duration ASK = Duration.ofSeconds(5);

    public record Result(boolean ok, String message, String roomId, int doorwaysClosed) {}

    private static volatile RoomDemolition INSTANCE;

    /** Register the zone's demolition service at boot. */
    public static void install(RoomDemolition d) { INSTANCE = d; }

    /** The registered service, or {@code null} when this node has none (tests, relays). */
    public static RoomDemolition get() { return INSTANCE; }

    private final RoomMetadataService metadata;
    private final Set<String> protectedRooms;
    private final Consumer<ZoneGuardian.Command> guardian;

    public RoomDemolition(RoomMetadataService metadata, Set<String> protectedRooms,
                          Consumer<ZoneGuardian.Command> guardian) {
        this.metadata = metadata;
        this.protectedRooms = protectedRooms == null ? Set.of() : Set.copyOf(protectedRooms);
        this.guardian = guardian;
    }

    /** Why this room may not be demolished, or {@code null} when it may. Pure. */
    static String refusal(String roomId, RoomMetadataService.RoomInfo info, Set<String> protectedRooms) {
        if (roomId == null || roomId.isBlank()) return "no room named";
        if (roomId.startsWith("home-")) return "a companion's Home is hers — it is not demolished";
        if (roomId.startsWith("study-")) return "a person's Study is theirs — it is not demolished";
        if (protectedRooms.contains(roomId)) return "a founding room of the zone stays";
        if (info == null) return "no record of who made it — a founding room stays";
        if (info.createdBy() == null || info.createdBy().isBlank()) return "a founding room of the zone stays";
        return null;
    }

    /** Who is standing in the room, by name — empty when nobody is. Pure. */
    static List<String> occupants(RoomSnapshot snapshot) {
        var out = new ArrayList<String>();
        if (snapshot == null || snapshot.entities() == null) return out;
        for (Entity e : snapshot.entities()) out.add(e.name() != null ? e.name() : e.id());
        return out;
    }

    /** Demolish the room named or identified by {@code query}. */
    public CompletionStage<Result> demolish(String query, String requester) {
        var registry = RoomRegistry.get();
        var roomId = registry.resolveRoomId(query);
        if (roomId == null) {
            return CompletableFuture.completedFuture(
                new Result(false, "No room called '" + query + "' here.", null, 0));
        }
        var info = metadata == null ? null : metadata.getRoom(roomId).orElse(null);
        var why = refusal(roomId, info, protectedRooms);
        if (why != null) {
            return CompletableFuture.completedFuture(new Result(false, why + " (" + roomId + ").", roomId, 0));
        }
        CompletionStage<RoomSnapshot> snap;
        try {
            snap = registry.<RoomSnapshot>askRoom(roomId, RoomCommand.GetSnapshot::new, ASK);
        } catch (IllegalStateException e) {
            return CompletableFuture.completedFuture(
                new Result(false, "That room is not running here (" + roomId + ").", roomId, 0));
        }
        return snap.thenCompose(snapshot -> {
            var who = occupants(snapshot);
            if (!who.isEmpty()) {
                return CompletableFuture.completedFuture(new Result(false,
                    "Someone is in it — " + String.join(", ", who) + ". Ask them to leave first.", roomId, 0));
            }
            var name = snapshot != null && snapshot.name() != null ? snapshot.name() : roomId;
            var topo = ZoneTopology.getShared();
            var doorways = topo == null ? List.<Map.Entry<String, String>>of() : topo.exitsInto(roomId);
            CompletionStage<Integer> closed = CompletableFuture.completedFuture(0);
            for (var d : doorways) {
                closed = closed.thenCompose(n -> closeDoorway(d.getKey(), d.getValue()).thenApply(ok -> n + (ok ? 1 : 0)));
            }
            return closed.thenApply(n -> {
                if (guardian != null) guardian.accept(new ZoneGuardian.RetireRoom(roomId));
                ZoneTopology.forgetRoom(roomId);
                if (metadata != null) metadata.delete(roomId);
                log.info("Room {} ('{}') demolished by {} — {} doorway(s) closed", roomId, name, requester, n);
                return new Result(true, "Demolished " + name + " (" + roomId + "); " + n + " doorway(s) closed.", roomId, n);
            });
        });
    }

    private CompletionStage<Boolean> closeDoorway(String sourceRoomId, String direction) {
        try {
            return RoomRegistry.get().<RoomResponse>askRoom(sourceRoomId,
                    ref -> new RoomCommand.RemoveExit(direction, ref), ASK)
                .thenApply(r -> r instanceof RoomResponse.Ok)
                .exceptionally(ex -> {
                    log.warn("Doorway {} from {} not closed: {}", direction, sourceRoomId, ex.toString());
                    return false;
                });
        } catch (IllegalStateException e) {
            return CompletableFuture.completedFuture(false);
        }
    }
}
