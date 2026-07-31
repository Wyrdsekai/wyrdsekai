package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.recipe.AuthoredRecipeLog;
import org.wyrdsekai.core.recipe.RecipeAuthorService;
import org.wyrdsekai.core.recipe.RecipeProvenanceReport;
import org.wyrdsekai.core.recipe.RecipeService;
import org.wyrdsekai.core.recipe.SqlRecipeQueue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * #1014 (OPEN-R1) — REST surface for the agent-authored recipe
 * compartment ({@code data/recipes/}). The in-world {@code shape_recipe} action
 * delegates here; a steward can also drive it directly.
 *
 * <p>These live under their own {@code /api/authored-recipes} namespace rather
 * than under {@code /api/recipes/*} on purpose: {@code RecipesRoutes} owns a
 * catch-all {@code GET /api/recipes/&#123;name&#125;} that would shadow a bare
 * {@code /api/recipes/authored} (a recipe could legitimately be named
 * "authored"). Keeping authoring on its own path is collision-free regardless
 * of route-registration order.</p>
 *
 * <ul>
 *   <li>{@code POST /api/authored-recipes} — body {@code {yaml, authorDid?,
 *       overwrite?}}. Validates (structural + the {@link
 *       org.wyrdsekai.core.recipe.AuthoredRecipeValidator authoring contract})
 *       then writes to the compartment. 200 with {@code applied:false} + the
 *       violation list on a rejected author (never a 500 for an author error).</li>
 *   <li>{@code GET  /api/authored-recipes} — names in the compartment.</li>
 *   <li>{@code GET  /api/authored-recipes/{name}} — the authored YAML back
 *       (text/yaml), or 404.</li>
 *   <li>{@code DELETE /api/authored-recipes/{name}} — retire an authored
 *       recipe.</li>
 * </ul>
 *
 * <p>Steward-auth (env token or loopback) — same gate as the other recipe
 * routes. The authoring contract is the real safety boundary regardless of
 * caller.</p>
 */
public final class RecipeAuthorRoutes {

    private static final Logger log = LoggerFactory.getLogger(RecipeAuthorRoutes.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_HEADER = "X-Wyrdsekai-Admin-Token";

    /** Set once by {@link #register} so handlers can reach the recipe DB
     *  (authoring provenance log + the {@code recipe_queue} read). Nullable —
     *  the no-arg register form leaves it unset (authoring still works, just
     *  unlogged; the provenance endpoint reports zero). */
    private static volatile String jdbcUrl;

    private RecipeAuthorRoutes() {}

    /** Back-compat: register without a DB handle (authoring works, unlogged). */
    public static void register(JavalinConfig cfg) {
        register(cfg, null);
    }

    public static void register(JavalinConfig cfg, String jdbc) {
        jdbcUrl = jdbc;
        cfg.routes.post("/api/authored-recipes", RecipeAuthorRoutes::handleAuthor);
        cfg.routes.get("/api/authored-recipes", RecipeAuthorRoutes::handleListAuthored);
        cfg.routes.get("/api/authored-recipes/{name}", RecipeAuthorRoutes::handleExport);
        cfg.routes.delete("/api/authored-recipes/{name}", RecipeAuthorRoutes::handleRemove);
        // B.1 — own namespace, NOT /api/recipes/* (would
        // be shadowed by RecipesRoutes' catch-all {name}; see the ITEM-D lesson).
        cfg.routes.get("/api/recipe-provenance", RecipeAuthorRoutes::handleProvenance);
    }

    private static RecipeAuthorService service() {
        Path recipesDir = SystemPaths.dataDir().resolve("recipes");
        Path scriptsRoot = Path.of(System.getProperty("user.dir"), "scripts");
        Path effScripts = Files.isDirectory(scriptsRoot) ? scriptsRoot : null;
        AuthoredRecipeLog logStore = jdbcUrl == null ? null : new AuthoredRecipeLog(jdbcUrl);
        return new RecipeAuthorService(recipesDir, effScripts,
            new LinkedHashSet<>(RecipeService.bundledNames()), logStore);
    }

    /** .B1 — the agent-initiated fraction over a
     *  window. {@code ?days=30&agent=<did>}; agent optional (household-wide). */
    private static void handleProvenance(Context ctx) {
        if (!authorised(ctx)) return;
        if (jdbcUrl == null) {
            ctx.json(Map.of("error", "no recipe database configured"));
            return;
        }
        int days = parsePositiveInt(ctx.queryParam("days"), 30);
        String agent = ctx.queryParam("agent");  // null = household-wide
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(days));
        var queue = new SqlRecipeQueue(jdbcUrl);
        int authoredCount = new AuthoredRecipeLog(jdbcUrl).countSince(from, agent);
        var p = RecipeProvenanceReport.compute(queue,
            new RecipeProvenanceReport.Window(from, to, agent), authoredCount);
        var trend = new ArrayList<Map<String, Object>>();
        for (var pt : p.trend()) {
            trend.add(Map.of("epochDay", pt.epochDay(),
                "agentInitiated", pt.agentInitiated(), "total", pt.total()));
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("days", days);
        out.put("scope", agent == null ? "household" : agent);
        out.put("agentInitiated", p.agentInitiated());
        out.put("requested", p.agent());       // request_recipe runs
        out.put("authored", p.authored());     // shape_recipe acts
        out.put("gap", p.gap());
        out.put("cron", p.cron());
        out.put("steward", p.steward());
        out.put("total", p.total());
        out.put("agentFraction", p.agentFraction());
        out.put("trend", trend);
        ctx.json(out);
    }

    private static int parsePositiveInt(String s, int dflt) {
        if (s == null) return dflt;
        try { int v = Integer.parseInt(s.trim()); return v > 0 ? v : dflt; }
        catch (NumberFormatException e) { return dflt; }
    }

    private static void handleAuthor(Context ctx) {
        if (!authorised(ctx)) return;
        JsonNode body;
        try {
            body = JSON.readTree(ctx.body() == null ? "" : ctx.body());
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "bad_body: " + e.getMessage()));
            return;
        }
        if (body == null || !body.has("yaml")) {
            ctx.status(400).json(Map.of("error", "body requires {yaml}"));
            return;
        }
        String yaml = body.get("yaml").asText("");
        String authorDid = body.has("authorDid") ? body.get("authorDid").asText(null) : null;
        boolean overwrite = body.has("overwrite") && body.get("overwrite").asBoolean(false);

        var result = service().importRecipe(yaml, authorDid, overwrite);
        var out = new LinkedHashMap<String, Object>();
        if (result.ok()) {
            out.put("applied", true);
            out.put("name", result.name());
            out.put("path", result.path());
            log.info("Recipe authored via REST: {} (by {})",
                result.name(), authorDid == null ? "?" : authorDid);
        } else {
            out.put("applied", false);
            out.put("error", result.error());
            out.put("violations", result.violations());
        }
        ctx.json(out);
    }

    private static void handleListAuthored(Context ctx) {
        if (!authorised(ctx)) return;
        var names = service().listAuthored();
        ctx.json(Map.of("count", names.size(), "authored", names));
    }

    private static void handleExport(Context ctx) {
        if (!authorised(ctx)) return;
        String name = ctx.pathParam("name");
        var yaml = service().exportRecipe(name);
        if (yaml.isEmpty()) {
            ctx.status(404).json(Map.of("error", "not an authored recipe: " + name));
            return;
        }
        ctx.contentType("application/yaml").result(yaml.get());
    }

    private static void handleRemove(Context ctx) {
        if (!authorised(ctx)) return;
        String name = ctx.pathParam("name");
        boolean removed = service().removeRecipe(name);
        ctx.json(Map.of("removed", removed, "name", name));
    }

    /** Steward-auth gate (matches LibraryCompactRoutes / RecipeTuneRoutes). */
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
