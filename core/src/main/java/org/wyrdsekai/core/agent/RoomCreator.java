package org.wyrdsekai.core.agent;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.core.room.ZoneTopology;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.room.ZoneGuardian;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Creates rooms via RoomRegistry on behalf of agents.
 */
public final class RoomCreator {

    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(5);

    private final Scheduler scheduler;
    @SuppressWarnings("rawtypes")
    private final ActorSystem system;

    @SuppressWarnings("rawtypes")
    public RoomCreator(ActorSystem system) {
        this.system = system;
        this.scheduler = system != null ? system.scheduler() : null;
    }

    public RoomCreator() {
        this.system = null;
        this.scheduler = null;
    }

    @SuppressWarnings("unchecked")
    public CompletionStage<RoomResponse> createRoom(
            String roomId, String name, String description,
            String zone, List<Exit> exits, List<RoomObject> objects) {
        var roomRef = RoomRegistry.get().ref(roomId);
        if (roomRef == null && system != null) {
            // Room doesn't exist — tell ZoneGuardian to spawn it
            system.tell(new ZoneGuardian.CreateNewRoom(
                roomId, name, description, zone, exits, objects));
            // Poll for room to appear in registry
            return CompletableFuture.supplyAsync(() -> {
                for (int i = 0; i < 10; i++) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                    if (RoomRegistry.get().ref(roomId) != null) {
                        return (RoomResponse) new RoomResponse.Ok(new RoomSnapshot(
                            roomId, name, description, zone, exits, List.of(), objects, List.of()));
                    }
                }
                return (RoomResponse) new RoomResponse.Rejected("error","Room spawn timed out: " + roomId);
            });
        }
        if (roomRef == null) {
            return CompletableFuture.completedFuture(
                new RoomResponse.Rejected("error","Room not found: " + roomId));
        }
        if (scheduler == null) {
            return CompletableFuture.completedFuture(
                new RoomResponse.Rejected("error","No scheduler available for ask"));
        }
        return AskPattern.<RoomCommand, RoomResponse>ask(roomRef,
            ref -> new RoomCommand.CreateRoom(name, description, zone, exits, objects, ref),
            ASK_TIMEOUT, scheduler)
            // The shared topology is built once at boot from the foundation seeds, so a
            // room made afterwards was invisible to `map` — it rendered as `->[?]`, an
            // unnamed destination on a one-way arrow, however walkable it actually was.
            .whenComplete((res, err) -> {
                if (err == null && res instanceof RoomResponse.Ok) {
                    ZoneTopology.learnRoom(roomId, name, zone, exits, null, null);
                }
            });
    }

    public CompletionStage<RoomResponse> addExit(
            String roomId, String direction, String targetRoom, String label) {
        // RESOLVE, don't ref(). ref() is an exact roomId match, but callers pass
        // whatever a model said: `create_room_from_template` forwarded
        // connect_to="Nexus" while the room's id is "nexus" and its name is "The
        // Nexus". That returned Rejected("Room not found: Nexus"), the caller
        // discarded the response, and the log claimed "connected to 'Nexus'" —
        // producing a greenhouse on home-server 2026-07-29 that could be left but never
        // entered. resolve() handles id and case-insensitive alias.
        var resolved = RoomRegistry.get().resolveRoomId(roomId);
        var roomRef = resolved != null ? RoomRegistry.get().ref(resolved)
                                       : RoomRegistry.get().ref(roomId);
        if (roomRef == null) {
            return CompletableFuture.completedFuture(
                new RoomResponse.Rejected("error","Room not found: " + roomId));
        }
        if (scheduler == null) {
            return CompletableFuture.completedFuture(
                new RoomResponse.Rejected("error","No scheduler available for ask"));
        }
        final var sourceId = resolved != null ? resolved : roomId;
        return AskPattern.<RoomCommand, RoomResponse>ask(roomRef,
            ref -> new RoomCommand.AddExit(direction, targetRoom, label, ref),
            ASK_TIMEOUT, scheduler)
            .whenComplete((res, err) -> {
                if (err == null && res instanceof RoomResponse.Ok) {
                    ZoneTopology.learnExit(sourceId, direction, targetRoom, label);
                }
            });
    }

    public static String generateRoomId(String name) {
        return name.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "")
            + "-" + (System.currentTimeMillis() % 10000);
    }
}
