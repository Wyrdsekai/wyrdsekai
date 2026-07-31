package org.wyrdsekai.server.http;

import io.javalin.config.JavalinConfig;
import org.wyrdsekai.core.recipe.CadenceTier;
import org.wyrdsekai.core.recipe.QueuedRecipe;
import org.wyrdsekai.core.recipe.RecipeEnrollment;
import org.wyrdsekai.core.recipe.RecipeEnrollmentStore;
import org.wyrdsekai.core.recipe.SqlRecipeQueue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Track-C C6 — REST surface that backs the
 * {@code wyrd recipes} CLI.
 *
 * <p>Five read endpoints + two writes:</p>
 * <ul>
 *   <li>{@code GET  /api/recipes}</li>
 *   <li>{@code GET  /api/recipes/{name}}</li>
 *   <li>{@code GET  /api/recipes/{name}/log}</li>
 *   <li>{@code POST /api/recipes/{name}/pause}</li>
 *   <li>{@code POST /api/recipes/{name}/resume}</li>
 * </ul>
 *
 * <p>The {@code run} subcommand reuses the existing
 * {@code /api/test/run_recipe} endpoint (steward override that dispatches
 * a recipe directly to a companion, bypassing welfare gates). All
 * endpoints open their own JDBC connections — same per-method pattern
 * as {@link SqlRecipeQueue}.</p>
 *
 * <p>Extracted from inline routes so the integration test
 * {@code RecipesRoutesIntegrationTest} can boot a Javalin instance
 * against a temp SQLite DB without standing up the rest of the
 * server.</p>
 */
public final class RecipesRoutes {

    private RecipesRoutes() {}

    public static void register(JavalinConfig cfg, String jdbcUrl) {
        cfg.routes.get("/api/recipes", ctx -> {
            var enrollStore = new RecipeEnrollmentStore(jdbcUrl);
            var queue = new SqlRecipeQueue(jdbcUrl);
            var rows = new ArrayList<Map<String, Object>>();
            for (var e : enrollStore.listAll()) {
                rows.add(summarizeEnrollment(e, queue));
            }
            ctx.json(Map.of("count", rows.size(), "rows", rows));
        });

        cfg.routes.get("/api/recipes/{name}", ctx -> {
            var name = ctx.pathParam("name");
            var enrollStore = new RecipeEnrollmentStore(jdbcUrl);
            var queue = new SqlRecipeQueue(jdbcUrl);
            var enrolls = new ArrayList<Map<String, Object>>();
            for (var e : enrollStore.listAll()) {
                if (!e.recipeId().equals(name)) continue;
                enrolls.add(serializeEnrollment(e));
            }
            var queueRows = new ArrayList<Map<String, Object>>();
            for (var q : queue.findByRecipe(name, null)) queueRows.add(serializeQueueRow(q));
            for (var em : enrolls) {
                var did = (String) em.get("agentDid");
                if (did == null) continue;
                for (var q : queue.findByRecipe(name, did)) queueRows.add(serializeQueueRow(q));
            }
            ctx.json(Map.of(
                "recipeId", name,
                "enrollments", enrolls,
                "runs", queueRows));
        });

        cfg.routes.get("/api/recipes/{name}/log", ctx -> {
            var name = ctx.pathParam("name");
            int limit;
            try {
                limit = Integer.parseInt(
                    ctx.queryParamAsClass("limit", String.class).getOrDefault("20"));
            } catch (Exception e) { limit = 20; }
            var queue = new SqlRecipeQueue(jdbcUrl);
            var enrollStore = new RecipeEnrollmentStore(jdbcUrl);
            var dids = new LinkedHashSet<String>();
            dids.add(null);
            for (var e : enrollStore.listAll()) {
                if (e.recipeId().equals(name)) dids.add(e.agentDid());
            }
            var rows = new ArrayList<Map<String, Object>>();
            for (var did : dids) {
                for (var q : queue.findByRecipe(name, did)) {
                    if (!q.isTerminal()) continue;
                    rows.add(serializeQueueRow(q));
                }
            }
            rows.sort((a, b) -> {
                var aT = (String) a.getOrDefault("completedAt", "");
                var bT = (String) b.getOrDefault("completedAt", "");
                if (aT == null) aT = "";
                if (bT == null) bT = "";
                return bT.compareTo(aT);
            });
            if (rows.size() > limit) rows.subList(limit, rows.size()).clear();
            ctx.json(Map.of(
                "recipeId", name,
                "count", rows.size(),
                "rows", rows));
        });

        cfg.routes.post("/api/recipes/{name}/pause", ctx -> {
            var name = ctx.pathParam("name");
            var did = ctx.queryParam("agentDid");
            var enrollStore = new RecipeEnrollmentStore(jdbcUrl);
            boolean updated = enrollStore.setEnabled(name, did, false);
            ctx.json(Map.of(
                "status", updated ? "paused" : "not_found",
                "recipeId", name,
                "agentDid", did == null ? "" : did));
        });

        cfg.routes.post("/api/recipes/{name}/resume", ctx -> {
            var name = ctx.pathParam("name");
            var did = ctx.queryParam("agentDid");
            var enrollStore = new RecipeEnrollmentStore(jdbcUrl);
            boolean updated = enrollStore.setEnabled(name, did, true);
            ctx.json(Map.of(
                "status", updated ? "resumed" : "not_found",
                "recipeId", name,
                "agentDid", did == null ? "" : did));
        });
    }

    private static Map<String, Object> summarizeEnrollment(
            RecipeEnrollment e, SqlRecipeQueue queue) {
        var rows = queue.findByRecipe(e.recipeId(), e.agentDid());
        QueuedRecipe lastTerminal = null;
        int pending = 0;
        for (var q : rows) {
            if (q.status() == QueuedRecipe.Status.PENDING
                    || q.status() == QueuedRecipe.Status.IN_PROGRESS) {
                pending++;
            }
            if (q.isTerminal() && (lastTerminal == null
                    || (q.completedAt() != null
                        && (lastTerminal.completedAt() == null
                            || q.completedAt().isAfter(lastTerminal.completedAt()))))) {
                lastTerminal = q;
            }
        }
        var row = new LinkedHashMap<String, Object>();
        row.put("recipeId", e.recipeId());
        row.put("agentDid", e.agentDid());
        row.put("enabled", e.enabled());
        row.put("cadenceTier", e.cadenceTier().name());
        row.put("consecutiveSuccesses", e.consecutiveSuccesses());
        row.put("gapKeys", new ArrayList<>(e.gapKeys()));
        row.put("queueDepth", pending);
        if (lastTerminal != null) {
            row.put("lastStatus", lastTerminal.status().name());
            row.put("lastRunAt", lastTerminal.completedAt() == null
                ? null : lastTerminal.completedAt().toString());
            row.put("lastMessage", lastTerminal.message());
            row.put("nextFireEstimate", lastTerminal.completedAt() == null
                ? null
                : lastTerminal.completedAt().plus(
                    cadenceTier(e).period()).toString());
        }
        return row;
    }

    private static CadenceTier cadenceTier(RecipeEnrollment e) {
        return e.cadenceTier() == null ? CadenceTier.WARMUP : e.cadenceTier();
    }

    private static Map<String, Object> serializeEnrollment(RecipeEnrollment e) {
        var m = new LinkedHashMap<String, Object>();
        m.put("agentDid", e.agentDid());
        m.put("enabled", e.enabled());
        m.put("cadenceTier", e.cadenceTier().name());
        m.put("consecutiveSuccesses", e.consecutiveSuccesses());
        m.put("enrolledAt", e.enrolledAt() == null ? null : e.enrolledAt().toString());
        m.put("gapKeys", new ArrayList<>(e.gapKeys()));
        return m;
    }

    public static Map<String, Object> serializeQueueRow(QueuedRecipe q) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", q.id());
        row.put("recipeId", q.recipeId());
        row.put("agentDid", q.agentDid());
        row.put("status", q.status().name());
        row.put("triggerSource", q.triggerSource().name());
        row.put("triggerReason", q.triggerReason());
        row.put("cadenceTier", q.cadenceTier().name());
        row.put("consecutiveSuccesses", q.consecutiveSuccesses());
        row.put("enqueuedAt", q.enqueuedAt() == null ? null : q.enqueuedAt().toString());
        row.put("attemptedAt", q.attemptedAt() == null ? null : q.attemptedAt().toString());
        row.put("completedAt", q.completedAt() == null ? null : q.completedAt().toString());
        row.put("runId", q.runId());
        row.put("message", q.message());
        row.put("params", q.params());
        return row;
    }
}
