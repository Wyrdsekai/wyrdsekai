package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.update.ActivityGauge;
import org.wyrdsekai.core.vault.Vault;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The steward's hand on the vault: {@code wyrd vault status|snapshot|drill|prune}. Restore is
 * an offline operation (the server must be down to swap its record) and lives in the CLI.
 */
public final class VaultRoutes {

    private final AuthService auth;

    public VaultRoutes(AuthService auth) {
        this.auth = auth;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/vault", this::status);
        app.post("/api/vault/snapshot", this::snapshot);
        app.post("/api/vault/drill", this::drill);
        app.post("/api/vault/prune", this::prune);
    }

    private boolean steward(Context ctx) {
        var token = AuthRoutes.extractToken(ctx);
        var user = token == null ? java.util.Optional.<AuthService.User>empty() : auth.validateSession(token);
        if (user.isEmpty()) { ctx.status(401).json(Map.of("error", "Authentication required")); return false; }
        if (!"steward".equals(user.get().role())) { ctx.status(403).json(Map.of("error", "Only the steward opens the vault")); return false; }
        return true;
    }

    private Vault vault(Context ctx) {
        var v = Vault.get();
        if (v == null) ctx.status(404).json(Map.of("error", "No vault on this node (WYRDSEKAI_VAULT_MINUTES=0)"));
        return v;
    }

    private void status(Context ctx) {
        if (!steward(ctx)) return;
        var v = vault(ctx);
        if (v == null) return;
        var out = new LinkedHashMap<String, Object>(v.status());
        var copies = new ArrayList<Map<String, Object>>();
        for (var m : v.list().stream().limit(20).toList()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", m.id());
            row.put("at", m.at().toString());
            row.put("reason", m.reason());
            row.put("keep", m.keep());
            row.put("files", m.files().size());
            row.put("bytes", m.bytes());
            row.put("drill", m.drill() == null ? null : Map.of("at", m.drill().at().toString(), "ok", m.drill().ok(), "detail", m.drill().detail()));
            copies.add(row);
        }
        out.put("recent", copies);
        ctx.json(out);
    }

    private void snapshot(Context ctx) {
        if (!steward(ctx)) return;
        var v = vault(ctx);
        if (v == null) return;
        String reason = "the steward asked";
        try {
            var node = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(ctx.body() == null || ctx.body().isBlank() ? "{}" : ctx.body());
            if (node.hasNonNull("reason") && !node.get("reason").asText().isBlank()) reason = node.get("reason").asText();
        } catch (Exception ignored) { }
        ActivityGauge.maintenanceStarted();
        try {
            var m = v.snapshot(reason, true);
            if (m.isEmpty()) { ctx.status(500).json(Map.of("ok", false, "error", v.lastError() == null ? "copy failed" : v.lastError())); return; }
            ctx.json(Map.of("ok", true, "id", m.get().id(), "files", m.get().files().size(), "bytes", m.get().bytes()));
        } finally {
            ActivityGauge.maintenanceFinished();
        }
    }

    private void drill(Context ctx) {
        if (!steward(ctx)) return;
        var v = vault(ctx);
        if (v == null) return;
        ActivityGauge.maintenanceStarted();
        try {
            var d = v.drill();
            if (d.isEmpty()) { ctx.status(409).json(Map.of("ok", false, "error", "nothing in the vault yet")); return; }
            ctx.json(Map.of("ok", d.get().ok(), "at", d.get().at().toString(), "detail", d.get().detail()));
        } finally {
            ActivityGauge.maintenanceFinished();
        }
    }

    private void prune(Context ctx) {
        if (!steward(ctx)) return;
        var v = vault(ctx);
        if (v == null) return;
        int removed = v.prune();
        ctx.json(Map.of("ok", true, "removed", removed, "copies", v.list().size()));
    }

    /** The same status as prose, for the boiler room. */
    public static String describe() {
        var v = Vault.get();
        if (v == null) return "No vault on this node.";
        var s = v.status();
        var sb = new StringBuilder();
        sb.append("Vault: ").append(s.get("copies")).append(" copies (").append(s.get("kept")).append(" kept), ")
            .append(String.format("%.1f", ((Number) s.get("chunkStoreBytes")).longValue() / 1e9)).append(" GB in the store\n");
        sb.append("Latest: ").append(s.get("latest") == null ? "none" : s.get("latest")).append("\n");
        sb.append("Last drill: ").append(s.get("lastDrill") == null ? "never" : s.get("lastDrill") + " " + (Boolean.TRUE.equals(s.get("lastDrillOk")) ? "passed" : "FAILED") + " — " + s.get("lastDrillDetail")).append("\n");
        if (s.get("lastError") != null && !String.valueOf(s.get("lastError")).isBlank()) sb.append("Last error: ").append(s.get("lastError")).append("\n");
        var un = s.get("unclassified");
        if (un instanceof List<?> l && !l.isEmpty()) sb.append("Unclassified in the data dir (not vaulted): ").append(l).append("\n");
        return sb.toString().stripTrailing();
    }
}
