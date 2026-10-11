package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.javalin.router.JavalinDefaultRoutingApi;
import io.javalin.http.Context;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.coding.ItemContractCheck;
import org.wyrdsekai.core.coding.ItemContractRepair;
import org.wyrdsekai.core.coding.RoomScriptRepair;
import org.wyrdsekai.core.room.BrokenRoomScripts;
import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.item.ScriptedItemLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The steward's view of items that are placed and broken, and the way to have one repaired.
 *
 * <p>{@code GET /api/items/broken} lists every household item the contract gate would refuse
 * today, with its problems. {@code POST /api/items/repair} takes {@code {"item": "<id or file>"}},
 * repairs a copy through the coding backend, and replaces the placed file only when the copy
 * has no problems left; the previous version is kept. Bundled items are never touched.</p>
 */
public final class ItemRepairRoutes {

    private final AuthService auth;

    public ItemRepairRoutes(AuthService auth) {
        this.auth = auth;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/items/broken", this::handleBroken);
        app.post("/api/items/repair", this::handleRepair);
        app.post("/api/rooms/script/repair", this::handleRoomRepair);
    }

    record ErrorResponse(String error) {}

    private boolean steward(Context ctx) {
        // One rule for every steward route (ApiAuth): a steward's login, or the machine's
        // operator token from the machine itself, which this copy used to refuse.
        return ApiAuth.requireSteward(ctx);
    }

    /** Every household item script with contract problems: file, problems. */
    static List<Map<String, Object>> broken() {
        var out = new ArrayList<Map<String, Object>>();
        var dir = ScriptedItemLoader.householdItemsDir();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (var p : files.filter(f -> f.toString().endsWith(".js")).sorted().toList()) {
                var problems = ItemContractCheck.problems(Files.readString(p), p.getFileName().toString());
                if (problems.isEmpty()) continue;
                var row = new LinkedHashMap<String, Object>();
                row.put("item", p.getFileName().toString().replaceFirst("\\.js$", ""));
                row.put("file", p.toString());
                row.put("problems", problems);
                out.add(row);
            }
        } catch (Exception e) {
            // an unreadable directory is simply nothing to report
        }
        return out;
    }

    private void handleBroken(Context ctx) {
        if (!steward(ctx)) return;
        ctx.json(Map.of("broken", broken()));
    }

    /** A room script of a companion's, mended the way an item is: on a copy, replaced only when whole. */
    private void handleRoomRepair(Context ctx) {
        if (!steward(ctx)) return;
        String room = null;
        try {
            var node = JsonMapper.builder().build().readTree(ctx.body() == null || ctx.body().isBlank() ? "{}" : ctx.body());
            if (node.hasNonNull("room")) room = node.get("room").asText();
        } catch (Exception ignored) {
        }
        if (room == null || !room.matches("[A-Za-z0-9_.-]{1,120}")) {
            ctx.status(400).json(new ErrorResponse("Which room? {\"room\": \"<id>\"}"));
            return;
        }
        final var roomId = room;
        var dir = SystemPaths.scriptsDir();
        var file = dir.resolve(roomId + ".js");
        if (!Files.isRegularFile(file)) {
            ctx.status(404).json(new ErrorResponse("No script of a companion's for room " + roomId));
            return;
        }
        var entry = BrokenRoomScripts.mendable(dir).stream().filter(e -> e.roomId().equals(roomId)).findFirst().orElse(null);
        var lastError = entry == null ? null : entry.lastError();
        var r = RoomScriptRepair.repair(roomId, file, lastError);
        if (entry != null && !"nothing to repair".equals(r.note()) && !"no coding backend is registered to repair with".equals(r.note())) {
            BrokenRoomScripts.mendAttempted(entry, r.fixed());
            BrokenRoomScripts.tellMended(entry, r.fixed(), String.join(" | ", r.after()), "at your word");
        } else if (r.fixed() || "nothing to repair".equals(r.note())) {
            BrokenRoomScripts.rewritten(roomId, dir);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("room", r.roomId());
        out.put("fixed", r.fixed());
        out.put("note", r.note());
        out.put("before", r.before());
        out.put("after", r.after());
        ctx.json(out);
    }

    private void handleRepair(Context ctx) {
        if (!steward(ctx)) return;
        String item = null;
        try {
            var node = JsonMapper.builder().build().readTree(ctx.body() == null || ctx.body().isBlank() ? "{}" : ctx.body());
            if (node.hasNonNull("item")) item = node.get("item").asText();
        } catch (Exception ignored) {
            // falls through to the 400
        }
        if (item == null || !item.matches("[A-Za-z0-9_.-]{1,80}")) {
            ctx.status(400).json(new ErrorResponse("Which item? {\"item\": \"<id>\"}"));
            return;
        }
        var dir = ScriptedItemLoader.householdItemsDir();
        Path file = dir == null ? null : dir.resolve(item.endsWith(".js") ? item : item + ".js");
        if (file == null || !Files.isRegularFile(file)) {
            ctx.status(404).json(new ErrorResponse("No household item called " + item));
            return;
        }
        var r = ItemContractRepair.repairPlaced(file);
        if (r.fixed()) {
            try { ScriptedItemLoader.get().register(file); } catch (RuntimeException ignored) { /* picked up at the next load */ }
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("item", r.item());
        out.put("fixed", r.fixed());
        out.put("note", r.note());
        out.put("before", r.before());
        out.put("after", r.after());
        ctx.json(out);
    }
}
