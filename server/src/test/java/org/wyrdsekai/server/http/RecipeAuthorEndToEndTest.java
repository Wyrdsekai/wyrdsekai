package org.wyrdsekai.server.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.recipe.ProcessCommandRunner;
import org.wyrdsekai.core.recipe.RecipeAuthorService;
import org.wyrdsekai.core.recipe.RecipeRunner;
import org.wyrdsekai.core.recipe.RecipeService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #1014 (OPEN-R1) — the agent-authored recipe loop end to end.
 *
 * <p>Two real-HTTP cases against a booted {@link RecipeAuthorRoutes} (the actual
 * wire the {@code shape_recipe} action + a steward hit), and one
 * <b>full-loop</b> case that authors a recipe, lets {@link RecipeService}
 * discover it, and <b>runs it through a real {@link ProcessCommandRunner}
 * subprocess</b> — proving an agent-authored recipe genuinely executes (not just
 * validates), composing an existing recipe-callable script + a gate to
 * SUCCESS.</p>
 */
class RecipeAuthorEndToEndTest {

    @TempDir Path tmpRecipes;

    private Javalin app;
    private HttpClient http;
    private String baseUrl;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /** A recipe an agent could author: composes an existing recipe-callable
     *  script + a gate. Runs offline ({@code library_freshness.py report}
     *  degrades to a clean {@code freshness_ok:1} no-op when no zone is up). */
    private static final String AUTHORED = """
        recipe: demo-authored-freshness
        deploys: false
        params:
          agent_did: { type: string, default: "did:test:demo" }
        steps:
          - id: run
            kind: SHELL
            command: "python3 scripts/recipe/library_freshness.py report --agent-did {{agent_did}}"
          - id: gate-fresh
            kind: GATE
            condition: "freshness_ok == 1"
            on_fail: STOP
        """;

    private String prevDataDir;

    @BeforeEach void setUp() {
        // Isolate to the temp dir: the route writes to SystemPaths.dataDir()/recipes,
        // which otherwise resolves to the real ~/.wyrdsekai (root-owned after an
        // install → "could not write recipe"). SystemPaths honours -Dwyrdsekai.dataDir.
        prevDataDir = System.getProperty("wyrdsekai.dataDir");
        System.setProperty("wyrdsekai.dataDir", tmpRecipes.toString());
        app = Javalin.create(RecipeAuthorRoutes::register).start(0);
        baseUrl = "http://localhost:" + app.port();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach void tearDown() {
        if (app != null) app.stop();
        if (prevDataDir == null) System.clearProperty("wyrdsekai.dataDir");
        else System.setProperty("wyrdsekai.dataDir", prevDataDir);
    }

    @Test
    void http_author_accepts_a_valid_recipe_then_lists_and_exports_it() throws Exception {
        // The route writes to SystemPaths.dataDir()/recipes — now the temp dir
        // (set in setUp) so we exercise the true endpoint without touching ~/.wyrdsekai.
        Path authored = SystemPaths.dataDir().resolve("recipes")
            .resolve("demo-authored-freshness.recipe.yaml");
        try {
            var post = postJson("/api/authored-recipes",
                MAPPER.writeValueAsString(Map.of("yaml", AUTHORED, "authorDid", "did:test:demo")));
            assertThat(post).containsEntry("applied", true)
                .containsEntry("name", "demo-authored-freshness");
            assertThat(Files.isRegularFile(authored)).isTrue();

            var list = getJson("/api/authored-recipes");
            @SuppressWarnings("unchecked")
            var names = (List<String>) list.get("authored");
            assertThat(names).contains("demo-authored-freshness");

            // Export round-trips the verbatim YAML.
            var exported = getText("/api/authored-recipes/demo-authored-freshness");
            assertThat(exported).isEqualTo(AUTHORED);
        } finally {
            Files.deleteIfExists(authored);
        }
    }

    @Test
    void http_author_rejects_a_bare_shell_rce_attempt() throws Exception {
        var malicious = """
            recipe: demo-rce-attempt
            steps:
              - id: nuke
                kind: SHELL
                command: "rm -rf /"
            """;
        var post = postJson("/api/authored-recipes",
            MAPPER.writeValueAsString(Map.of("yaml", malicious)));
        assertThat(post).containsEntry("applied", false);
        @SuppressWarnings("unchecked")
        var violations = (List<String>) post.get("violations");
        assertThat(String.join(" ", violations)).contains("scripts/ helper");
        // Nothing was written.
        assertThat(Files.exists(SystemPaths.dataDir().resolve("recipes")
            .resolve("demo-rce-attempt.recipe.yaml"))).isFalse();
    }

    /**
     * Regression for the route collision the live home-server zone surfaced: when
     * {@link RecipesRoutes} (catch-all {@code GET /api/recipes/&#123;name&#125;})
     * and {@link RecipeAuthorRoutes} are both mounted, the author-list endpoint
     * must resolve to the authoring handler — not be shadowed by the per-recipe
     * detail route. Co-registers both on one app and asserts the author shape.
     */
    @Test
    void author_list_endpoint_is_not_shadowed_by_recipes_catch_all() throws Exception {
        String jdbc = "jdbc:sqlite:" + tmpRecipes.resolve("rq-collide.db").toAbsolutePath();
        var both = Javalin.create(cfg -> {
            RecipesRoutes.register(cfg, jdbc);          // owns GET /api/recipes/{name}
            RecipeAuthorRoutes.register(cfg);            // owns GET /api/authored-recipes
        }).start(0);
        try {
            var resp = http.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + both.port() + "/api/authored-recipes"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);
            var json = MAPPER.readValue(resp.body(), MAP);
            // Author-list shape ({count, authored}) — NOT the per-recipe detail
            // shape ({recipeId, runs, enrollments}) that the collision produced.
            assertThat(json).containsKey("authored").containsKey("count");
            assertThat(json).doesNotContainKey("enrollments");
        } finally {
            both.stop();
        }
    }

    @Test
    void full_loop_author_then_discover_then_run_executes_a_real_subprocess() {
        // Repo root holds scripts/ (this module's user.dir is .../wyrdsekai/server).
        Path repoRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path script = repoRoot.resolve("scripts/recipe/library_freshness.py");
        assumeTrue(Files.isRegularFile(script), "recipe-callable script must be present");
        assumeTrue(python3Available(), "python3 must be on PATH for the real-run leg");

        // 1) AUTHOR — into a controlled compartment (same path the action uses).
        var author = new RecipeAuthorService(
            tmpRecipes, repoRoot.resolve("scripts"),
            Set.copyOf(RecipeService.bundledNames()));
        var imp = author.importRecipe(AUTHORED, "did:test:demo", false);
        assertThat(imp.ok()).as(imp.error()).isTrue();

        // 2) DISCOVER — a fresh service finds the authored recipe.
        var runner = new RecipeRunner(new ProcessCommandRunner(
            repoRoot.toFile(), Duration.ofMinutes(1)));
        var svc = new RecipeService(tmpRecipes, runner, "did:test:demo",
            repoRoot.resolve("scripts"));
        assertThat(svc.list()).anySatisfy(s ->
            assertThat(s.name()).isEqualTo("demo-authored-freshness"));

        // 3) RUN — for real, through a subprocess. The script emits freshness_ok=1,
        // the gate reads it, the recipe reaches SUCCESS.
        var started = svc.run("demo-authored-freshness", Map.of("agent_did", "did:test:demo"));
        assertThat(started.run().status())
            .as("authored recipe should run to SUCCESS via real subprocess; msg=%s",
                started.run().message())
            .isEqualTo(RecipeRunner.Status.SUCCESS);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static boolean python3Available() {
        try {
            return new ProcessBuilder("python3", "--version")
                .redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> getJson(String path) throws Exception {
        var resp = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return MAPPER.readValue(resp.body(), MAP);
    }

    private String getText(String path) throws Exception {
        var resp = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return resp.body();
    }

    private Map<String, Object> postJson(String path, String body) throws Exception {
        var resp = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return MAPPER.readValue(resp.body(), MAP);
    }
}
