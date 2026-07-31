package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.recipe.RecipeManifest;
import org.wyrdsekai.core.recipe.RecipeParamTuner;
import org.wyrdsekai.core.recipe.RecipeService;
import org.wyrdsekai.core.recipe.SqlRecipeParamOverrides;
import org.wyrdsekai.core.recipe.SqlRecipeQueue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * #1142 — REST surface that backs the {@code tune-recipe-params}
 * meta-recipe (and a steward's manual tune). Two endpoints:
 *
 * <ul>
 *   <li>{@code GET  /api/recipes/{name}/tune/stats?agentDid=&lt;did&gt;} — the
 *       outcome history (success/fail/gate-fail counts over the queue window),
 *       the recipe's declared params with their current effective values, and a
 *       {@code floorProtected} flag per param so a caller never even proposes a
 *       nudge to a PERMANENT-gated floor.</li>
 *   <li>{@code POST /api/recipes/{name}/tune/apply} — body
 *       {@code {param, value, min, max, agentDid?, updatedBy?}}. The server
 *       re-runs {@link RecipeParamTuner#validateNudge} (the SAME pure check the
 *       recipe script runs client-side) before any write, so the
 *       floor-protection holds regardless of who calls. On allow → upsert into
 *       {@link SqlRecipeParamOverrides}; on refuse → 200 with
 *       {@code applied:false} + the refusal reason.</li>
 * </ul>
 *
 * <p>The safety invariant lives in {@link RecipeParamTuner}, not here: a param
 * referenced by a PERMANENT welfare gate condition (OPEN-R4, #1013) is a
 * load-bearing floor and is refused; everything else is bounded by the
 * caller-supplied {@code [min,max]}. The apply endpoint is the server-side
 * enforcement point — the recipe script also checks, but the server is
 * authoritative.</p>
 *
 * <p>Steward-auth: same env-token-or-loopback gate as
 * {@link LibraryCompactRoutes} (the recipe scheduler dispatches in-process, so
 * loopback is the typical path; external callers need
 * {@code WYRDSEKAI_ADMIN_TOKEN}).</p>
 */
public final class RecipeTuneRoutes {

    private static final Logger log = LoggerFactory.getLogger(RecipeTuneRoutes.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_HEADER = "X-Wyrdsekai-Admin-Token";

    private RecipeTuneRoutes() {}

    public static void register(JavalinConfig cfg, String jdbcUrl) {
        cfg.routes.get("/api/recipes/{name}/tune/stats",
            ctx -> handleStats(ctx, jdbcUrl));
        cfg.routes.post("/api/recipes/{name}/tune/apply",
            ctx -> handleApply(ctx, jdbcUrl));
    }

    private static void handleStats(Context ctx, String jdbcUrl) {
        if (!authorised(ctx)) return;
        String name = ctx.pathParam("name");
        String agentDid = blankToNull(ctx.queryParam("agentDid"));

        RecipeManifest m;
        try {
            m = manifestLookup().inspect(name);
        } catch (RuntimeException e) {
            ctx.status(404).json(Map.of("error", "recipe not found: " + name));
            return;
        }

        var queue = new SqlRecipeQueue(jdbcUrl);
        var rows = new ArrayList<>(queue.findByRecipe(name, null));
        if (agentDid != null) rows.addAll(queue.findByRecipe(name, agentDid));
        var stats = RecipeParamTuner.statsFrom(rows);

        var floors = RecipeParamTuner.floorProtectedParams(m);
        var overrides = new SqlRecipeParamOverrides(jdbcUrl).effectiveFor(name, agentDid);

        var params = new ArrayList<Map<String, Object>>();
        if (m.params() != null) {
            for (var e : m.params().entrySet()) {
                var p = new LinkedHashMap<String, Object>();
                p.put("name", e.getKey());
                p.put("type", e.getValue().type());
                p.put("required", e.getValue().required());
                p.put("manifestDefault", e.getValue().defaultValue());
                // effective = stored override if present, else manifest default
                p.put("effective", overrides.containsKey(e.getKey())
                    ? overrides.get(e.getKey()) : e.getValue().defaultValue());
                p.put("overridden", overrides.containsKey(e.getKey()));
                p.put("floorProtected", floors.contains(e.getKey()));
                params.add(p);
            }
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("recipeId", name);
        out.put("agentDid", agentDid == null ? "" : agentDid);
        out.put("deploys", m.deploys());
        out.put("stats", Map.of(
            "total", stats.total(),
            "succeeded", stats.succeeded(),
            "failed", stats.failed(),
            "gateFailed", stats.gateFailed(),
            "failRate", stats.failRate()));
        out.put("floorProtected", new ArrayList<>(floors));
        out.put("params", params);
        ctx.json(out);
    }

    private static void handleApply(Context ctx, String jdbcUrl) {
        if (!authorised(ctx)) return;
        String name = ctx.pathParam("name");

        JsonNode body;
        try {
            body = JSON.readTree(ctx.body() == null ? "" : ctx.body());
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "bad_body: " + e.getMessage()));
            return;
        }
        if (body == null || !body.has("param") || !body.has("value")
                || !body.has("min") || !body.has("max")) {
            ctx.status(400).json(Map.of(
                "error", "body requires {param, value, min, max}"));
            return;
        }
        String param = body.get("param").asText();
        double value = body.get("value").asDouble();
        double min = body.get("min").asDouble();
        double max = body.get("max").asDouble();
        String agentDid = body.has("agentDid") ? blankToNull(body.get("agentDid").asText()) : null;
        String updatedBy = body.has("updatedBy") ? body.get("updatedBy").asText() : "tune-recipe-params";

        RecipeManifest m;
        try {
            m = manifestLookup().inspect(name);
        } catch (RuntimeException e) {
            ctx.status(404).json(Map.of("error", "recipe not found: " + name));
            return;
        }

        var decision = RecipeParamTuner.validateNudge(m, param, value, min, max);
        var out = new LinkedHashMap<String, Object>();
        out.put("recipeId", name);
        out.put("param", param);
        out.put("value", value);
        out.put("agentDid", agentDid == null ? "" : agentDid);
        if (!decision.allow()) {
            out.put("applied", false);
            out.put("refusal", decision.refusal().name());
            out.put("detail", decision.detail());
            ctx.json(out);
            return;
        }
        // Persist as a string — recipe params coerce on read.
        new SqlRecipeParamOverrides(jdbcUrl)
            .upsert(name, agentDid, param, formatValue(value, m, param), updatedBy);
        out.put("applied", true);
        out.put("refusal", RecipeParamTuner.Refusal.NONE.name());
        log.info("recipe param tuned: {}/{} {} = {} (by {})",
            name, agentDid == null ? "*" : agentDid, param, value, updatedBy);
        ctx.json(out);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Classpath+household manifest lookup (no runner needed for inspect()). */
    private static RecipeService manifestLookup() {
        Path recipesDir = SystemPaths.dataDir().resolve("recipes");
        return new RecipeService(recipesDir, null);
    }

    /**
     * Render the tuned value the way the manifest's declared type expects: an
     * integer-typed param stores {@code "30"} not {@code "30.0"}. Defensive —
     * an unknown type falls back to a trimmed double.
     */
    private static String formatValue(double value, RecipeManifest m, String param) {
        String type = m.params() != null && m.params().get(param) != null
            ? m.params().get(param).type() : null;
        boolean integral = type != null
            && (type.equalsIgnoreCase("int") || type.equalsIgnoreCase("integer")
                || type.equalsIgnoreCase("long"));
        if (integral || value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Steward-auth gate (matches LibraryCompactRoutes): env token or loopback. */
    private static boolean authorised(Context ctx) {
        String expected = System.getenv("WYRDSEKAI_ADMIN_TOKEN");
        if (expected == null || expected.isBlank()) {
            String remote = ctx.ip();
            boolean local = "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote)
                || "::1".equals(remote);
            if (!local) {
                ctx.status(403).json(Map.of("error", "admin_token_required"));
                return false;
            }
            return true;
        }
        String got = ctx.header(ADMIN_HEADER);
        if (!expected.equals(got)) {
            ctx.status(403).json(Map.of("error", "invalid_admin_token"));
            return false;
        }
        return true;
    }
}
