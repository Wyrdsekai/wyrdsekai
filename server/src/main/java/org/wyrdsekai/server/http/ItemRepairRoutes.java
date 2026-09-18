package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.javalin.router.JavalinDefaultRoutingApi;
import io.javalin.http.Context;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.coding.ItemContractCheck;
import org.wyrdsekai.core.coding.ItemContractRepair;
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
    }

    record ErrorResponse(String error) {}

    private boolean steward(Context ctx) {
        var token = AuthRoutes.extractToken(ctx);
        if (token == null) { ctx.status(401).json(new ErrorResponse("Authentication required")); return false; }
        var user = auth.validateSession(token);
        if (user.isEmpty()) { ctx.status(401).json(new ErrorResponse("Invalid or expired session")); return false; }
        if (!"steward".equals(user.get().role())) { ctx.status(403).json(new ErrorResponse("Only the steward repairs items")); return false; }
        return true;
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
