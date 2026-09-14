package org.wyrdsekai.server.http;

import io.javalin.http.Context;
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

    public RoomAdminRoutes(AuthService auth, RoomMetadataService metadata, Set<String> protectedRooms) {
        this.auth = auth;
        this.metadata = metadata;
        this.protectedRooms = protectedRooms == null ? Set.of() : Set.copyOf(protectedRooms);
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/rooms", this::handleList);
        app.delete("/api/rooms/{roomId}", this::handleDemolish);
        app.post("/api/rooms/prune", this::handlePrune);
    }

    record ErrorResponse(String error) {}

    /** One room as the steward sees it. */
    public record RoomRow(String roomId, String name, String madeBy, long createdAt,
                          int doorways, List<String> occupants, boolean markupId, boolean protectedRoom) {}

    private void handleList(Context ctx) {
        ctx.json(rows());
    }

    private void handleDemolish(Context ctx) {
        if (requireSteward(ctx) == null) return;
        var demolition = RoomDemolition.get();
        if (demolition == null) { ctx.status(503).json(new ErrorResponse("demolition not available")); return; }
        var result = await(demolition.demolish(ctx.pathParam("roomId"), "steward"));
        ctx.status(result.ok() ? 200 : 409).json(Map.of("ok", result.ok(), "message", result.message(),
            "roomId", result.roomId() == null ? "" : result.roomId(), "doorwaysClosed", result.doorwaysClosed()));
    }

    private void handlePrune(Context ctx) {
        if (requireSteward(ctx) == null) return;
        boolean dry = "true".equalsIgnoreCase(ctx.queryParam("dry"));
        boolean duplicates = "true".equalsIgnoreCase(ctx.queryParam("duplicates"));
        var plan = prunePlan(rows(), duplicates);
        if (dry) { ctx.json(Map.of("dry", true, "rooms", plan)); return; }
        var demolition = RoomDemolition.get();
        if (demolition == null) { ctx.status(503).json(new ErrorResponse("demolition not available")); return; }
        var results = new ArrayList<Map<String, Object>>();
        for (var row : plan) {
            var r = await(demolition.demolish(row.roomId(), "steward"));
            results.add(Map.of("roomId", row.roomId(), "name", row.name(), "ok", r.ok(), "message", r.message()));
        }
        ctx.json(Map.of("dry", false, "results", results));
    }

    /** What prune would take down: markup ids always; later duplicates of a name when asked. Pure. */
    static List<RoomRow> prunePlan(List<RoomRow> rooms, boolean duplicates) {
        var out = new ArrayList<RoomRow>();
        var firstByName = new HashMap<String, RoomRow>();
        var sorted = new ArrayList<>(rooms);
        sorted.sort((a, b) -> Long.compare(a.createdAt(), b.createdAt()));
        for (var r : sorted) {
            if (r.protectedRoom()) continue;
            if (r.markupId()) { out.add(r); continue; }
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
        var out = new ArrayList<RoomRow>();
        if (topo == null) return out;
        for (var node : topo.rooms().values()) {
            var i = info.get(node.roomId());
            var madeBy = i == null || i.createdBy() == null ? null : i.createdBy();
            // Seeded rooms carry created_by = "system" on real nodes, the same as rooms a
            // companion made — the seed set is what tells them apart.
            boolean protectedRoom = madeBy == null || protectedRooms.contains(node.roomId())
                || node.roomId().startsWith("home-") || node.roomId().startsWith("study-");
            out.add(new RoomRow(node.roomId(), node.name(), madeBy == null ? "founding" : madeBy,
                i == null ? 0 : i.createdAt(), node.exits().size(),
                occupants.getOrDefault(node.roomId(), List.of()),
                RoomNaming.looksLikeMarkupId(node.roomId()), protectedRoom));
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
        var token = AuthRoutes.extractToken(ctx);
        if (token == null) { ctx.status(401).json(new ErrorResponse("Authentication required")); return null; }
        var user = auth.validateSession(token);
        if (user.isEmpty()) { ctx.status(401).json(new ErrorResponse("Invalid or expired session")); return null; }
        if (!"steward".equals(user.get().role())) { ctx.status(403).json(new ErrorResponse("Only the steward demolishes rooms")); return null; }
        return user.get().id();
    }
}
