package org.wyrdsekai.server.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.recipe.CadenceTier;
import org.wyrdsekai.core.recipe.QueuedRecipe;
import org.wyrdsekai.core.recipe.SqlRecipeQueue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1142 — the tune-recipe-params loop over real HTTP against a
 * booted {@link RecipeTuneRoutes}. Targets the bundled {@code
 * research-pack-freshness} (PERMANENT-gated {@code max_dead_pct}/{@code
 * max_dead_abs} = floors; {@code validate_timeout} = tunable). Proves: stats
 * reflect seeded outcomes + floor flags; the apply endpoint REFUSES a
 * floor-protected param and an out-of-bounds value, ACCEPTS a bounded tunable;
 * and the tuned value then shows as the effective default.
 */
class RecipeTuneRoutesIntegrationTest {

    @TempDir Path tmp;

    private Javalin app;
    private HttpClient http;
    private String baseUrl;
    private String jdbcUrl;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String RECIPE = "research-pack-freshness";

    @BeforeEach void setUp() {
        jdbcUrl = "jdbc:sqlite:" + tmp.resolve("rq.db").toAbsolutePath();
        app = Javalin.create(cfg -> RecipeTuneRoutes.register(cfg, jdbcUrl)).start(0);
        baseUrl = "http://localhost:" + app.port();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach void tearDown() {
        if (app != null) app.stop();
    }

    @Test
    void stats_reflect_seeded_outcomes_and_flag_floor_protected_params() throws Exception {
        seedTerminal(QueuedRecipe.Status.SUCCEEDED, "ok");
        seedTerminal(QueuedRecipe.Status.SUCCEEDED, "ok");
        seedTerminal(QueuedRecipe.Status.FAILED, "step 'validate' gate failed");
        seedTerminal(QueuedRecipe.Status.FAILED, "timed out");

        var json = getJson("/api/recipes/" + RECIPE + "/tune/stats");
        @SuppressWarnings("unchecked")
        var stats = (Map<String, Object>) json.get("stats");
        assertThat(((Number) stats.get("total")).intValue()).isEqualTo(4);
        assertThat(((Number) stats.get("failed")).intValue()).isEqualTo(2);
        assertThat(((Number) stats.get("gateFailed")).intValue()).isEqualTo(1);
        assertThat(((Number) stats.get("failRate")).doubleValue()).isEqualTo(0.5);

        @SuppressWarnings("unchecked")
        var floors = (List<String>) json.get("floorProtected");
        assertThat(floors).contains("max_dead_pct", "max_dead_abs");

        // Each param carries a floorProtected flag the tuner reads.
        @SuppressWarnings("unchecked")
        var params = (List<Map<String, Object>>) json.get("params");
        var maxDeadPct = params.stream()
            .filter(p -> "max_dead_pct".equals(p.get("name"))).findFirst().orElseThrow();
        assertThat(maxDeadPct).containsEntry("floorProtected", true);
        var timeout = params.stream()
            .filter(p -> "validate_timeout".equals(p.get("name"))).findFirst().orElseThrow();
        assertThat(timeout).containsEntry("floorProtected", false);
    }

    @Test
    void apply_refuses_a_floor_protected_param() throws Exception {
        var resp = postJson("/api/recipes/" + RECIPE + "/tune/apply",
            MAPPER.writeValueAsString(Map.of(
                "param", "max_dead_pct", "value", 90, "min", 0, "max", 100)));
        assertThat(resp).containsEntry("applied", false)
            .containsEntry("refusal", "FLOOR_PROTECTED");
    }

    @Test
    void apply_refuses_an_out_of_bounds_value() throws Exception {
        var resp = postJson("/api/recipes/" + RECIPE + "/tune/apply",
            MAPPER.writeValueAsString(Map.of(
                "param", "validate_timeout", "value", 999, "min", 5, "max", 30)));
        assertThat(resp).containsEntry("applied", false)
            .containsEntry("refusal", "OUT_OF_BOUNDS");
    }

    @Test
    void apply_accepts_a_bounded_tunable_param_and_it_becomes_the_effective_default() throws Exception {
        var apply = postJson("/api/recipes/" + RECIPE + "/tune/apply",
            MAPPER.writeValueAsString(Map.of(
                "param", "validate_timeout", "value", 12, "min", 5, "max", 30,
                "updatedBy", "integration-test")));
        assertThat(apply).containsEntry("applied", true);

        // The override now shows as the effective value in stats.
        var stats = getJson("/api/recipes/" + RECIPE + "/tune/stats");
        @SuppressWarnings("unchecked")
        var params = (List<Map<String, Object>>) stats.get("params");
        var timeout = params.stream()
            .filter(p -> "validate_timeout".equals(p.get("name"))).findFirst().orElseThrow();
        assertThat(timeout).containsEntry("overridden", true);
        assertThat(timeout.get("effective").toString()).contains("12");
    }

    @Test
    void stats_for_unknown_recipe_404s() throws Exception {
        var resp = http.send(HttpRequest.newBuilder(
                URI.create(baseUrl + "/api/recipes/no-such-recipe/tune/stats")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void seedTerminal(QueuedRecipe.Status status, String message) {
        var now = Instant.now();
        var row = new QueuedRecipe(UUID.randomUUID().toString(), RECIPE, Map.of(),
            "seed", QueuedRecipe.TriggerSource.CRON, now, now, now, status,
            null, CadenceTier.WARMUP, 0, "run-x", message);
        var queue = new SqlRecipeQueue(jdbcUrl);
        queue.enqueue(row);
        queue.markAttempted(row.id(), now);
        queue.markCompleted(row.id(), status, now, CadenceTier.WARMUP, 0, "run-x", message);
    }

    private Map<String, Object> getJson(String path) throws Exception {
        var resp = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return MAPPER.readValue(resp.body(), MAP);
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
