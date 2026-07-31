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
import org.wyrdsekai.core.recipe.RecipeEnrollment;
import org.wyrdsekai.core.recipe.RecipeEnrollmentStore;
import org.wyrdsekai.core.recipe.SqlRecipeQueue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track-C C6 — boot a Javalin app with only the
 * {@link RecipesRoutes} registered, seed a temp SQLite recipe store,
 * and exercise each endpoint the {@code wyrd recipes} CLI calls.
 *
 * <p>Single-purpose: pins down the shape of every response the bash
 * CLI parses (list / status / log / pause / resume), and ensures the
 * pause/resume writes actually round-trip back through {@code
 * RecipeEnrollmentStore.find}.</p>
 */
class RecipesRoutesIntegrationTest {

    @TempDir
    Path tmp;

    private Javalin app;
    private HttpClient http;
    private String baseUrl;
    private String jdbcUrl;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP =
        new TypeReference<>() {};

    @BeforeEach
    void setUp() {
        jdbcUrl = "jdbc:sqlite:" + tmp.resolve("recipes.db").toAbsolutePath();
        app = Javalin.create(cfg -> RecipesRoutes.register(cfg, jdbcUrl)).start(0);
        baseUrl = "http://localhost:" + app.port();
        http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    @Test
    void list_empty_returns_zero_rows() throws Exception {
        var json = getJson("/api/recipes");
        assertThat(json).containsEntry("count", 0);
        assertThat((List<?>) json.get("rows")).isEmpty();
    }

    @Test
    void list_includes_paused_rows() throws Exception {
        var enrollStore = new RecipeEnrollmentStore(jdbcUrl);
        enrollStore.upsert(new RecipeEnrollment(
            "retrain-classifier-head", "did:wyrd:companion-a",
            CadenceTier.WARMUP, 0, Instant.now(), true,
            Set.of("task_present.misroute")));
        enrollStore.upsert(new RecipeEnrollment(
            "paused-recipe", "did:wyrd:companion-b",
            CadenceTier.SETTLING, 4, Instant.now(), false,
            Set.of()));

        var json = getJson("/api/recipes");
        assertThat(json).containsEntry("count", 2);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) json.get("rows");
        assertThat(rows).extracting(r -> r.get("recipeId"))
            .containsExactlyInAnyOrder(
                "retrain-classifier-head", "paused-recipe");
        var paused = rows.stream()
            .filter(r -> "paused-recipe".equals(r.get("recipeId")))
            .findFirst().orElseThrow();
        assertThat(paused).containsEntry("enabled", false);
        assertThat(paused).containsEntry("cadenceTier", "SETTLING");
    }

    @Test
    void list_summarizes_last_terminal_run_and_next_fire_estimate() throws Exception {
        var enrollStore = new RecipeEnrollmentStore(jdbcUrl);
        var queue = new SqlRecipeQueue(jdbcUrl);
        var did = "did:wyrd:companion-a";
        enrollStore.upsert(new RecipeEnrollment(
            "retrain-classifier-head", did, CadenceTier.WARMUP, 0,
            Instant.now(), true, Set.of()));
        var enq = Instant.now().minus(Duration.ofMinutes(20));
        var completedAt = Instant.now().minus(Duration.ofMinutes(5));
        var row = new QueuedRecipe(
            UUID.randomUUID().toString(),
            "retrain-classifier-head", Map.of(),
            "cron tick", QueuedRecipe.TriggerSource.CRON,
            enq, enq.plusSeconds(2), completedAt,
            QueuedRecipe.Status.SUCCEEDED, did,
            CadenceTier.WARMUP, 1, "run-1", "ok");
        queue.enqueue(row);
        queue.markAttempted(row.id(), row.attemptedAt());
        queue.markCompleted(row.id(), QueuedRecipe.Status.SUCCEEDED,
            completedAt, CadenceTier.WARMUP, 1, "run-1", "ok");

        var json = getJson("/api/recipes");
        assertThat(json).containsEntry("count", 1);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) json.get("rows");
        var only = rows.get(0);
        assertThat(only).containsEntry("recipeId", "retrain-classifier-head")
            .containsEntry("lastStatus", "SUCCEEDED")
            .containsEntry("queueDepth", 0);
        assertThat(only.get("nextFireEstimate")).asString()
            .startsWith(completedAt.plus(CadenceTier.WARMUP.period())
                .toString().substring(0, 10));
    }

    @Test
    void status_lists_enrollments_and_runs_for_one_recipe() throws Exception {
        var enrollStore = new RecipeEnrollmentStore(jdbcUrl);
        var queue = new SqlRecipeQueue(jdbcUrl);
        var did = "did:wyrd:companion-a";
        enrollStore.upsert(new RecipeEnrollment(
            "retrain-classifier-head", did, CadenceTier.SETTLING, 2,
            Instant.now(), true, Set.of()));
        var pending = QueuedRecipe.newEntry(UUID.randomUUID().toString(),
            "retrain-classifier-head", Map.of(), "tick",
            QueuedRecipe.TriggerSource.CRON, did,
            CadenceTier.SETTLING, 2);
        queue.enqueue(pending);

        var json = getJson("/api/recipes/retrain-classifier-head");
        assertThat(json).containsEntry("recipeId", "retrain-classifier-head");
        @SuppressWarnings("unchecked")
        var enrolls = (List<Map<String, Object>>) json.get("enrollments");
        assertThat(enrolls).hasSize(1);
        assertThat(enrolls.get(0)).containsEntry("agentDid", did)
            .containsEntry("cadenceTier", "SETTLING");
        @SuppressWarnings("unchecked")
        var runs = (List<Map<String, Object>>) json.get("runs");
        assertThat(runs).extracting(r -> r.get("status"))
            .contains("PENDING");
    }

    @Test
    void status_for_unknown_recipe_returns_empty_enrollments() throws Exception {
        var json = getJson("/api/recipes/nope");
        assertThat(json).containsEntry("recipeId", "nope");
        assertThat((List<?>) json.get("enrollments")).isEmpty();
        assertThat((List<?>) json.get("runs")).isEmpty();
    }

    @Test
    void log_returns_terminal_runs_newest_first() throws Exception {
        var queue = new SqlRecipeQueue(jdbcUrl);
        var did = "did:wyrd:companion-a";
        var enrollStore = new RecipeEnrollmentStore(jdbcUrl);
        enrollStore.upsert(new RecipeEnrollment(
            "retrain-classifier-head", did, CadenceTier.WARMUP, 0,
            Instant.now(), true, Set.of()));
        for (int i = 0; i < 3; i++) {
            var enq = Instant.now().minus(Duration.ofMinutes(40 - i * 10));
            var done = enq.plusSeconds(60);
            var id = "run-" + i;
            var row = QueuedRecipe.newEntry(id,
                "retrain-classifier-head", Map.of(), null,
                QueuedRecipe.TriggerSource.CRON, did,
                CadenceTier.WARMUP, i);
            queue.enqueue(row);
            queue.markAttempted(id, enq.plusSeconds(1));
            queue.markCompleted(id, QueuedRecipe.Status.SUCCEEDED,
                done, CadenceTier.WARMUP, i + 1, "rid-" + i, "ok");
        }

        var json = getJson("/api/recipes/retrain-classifier-head/log?limit=10");
        assertThat(json).containsEntry("recipeId", "retrain-classifier-head");
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) json.get("rows");
        assertThat(rows).hasSize(3);
        // Newest first.
        var first = (String) rows.get(0).get("completedAt");
        var last = (String) rows.get(2).get("completedAt");
        assertThat(first.compareTo(last)).isPositive();
    }

    @Test
    void pause_then_resume_round_trips_through_enrollment_store() throws Exception {
        var enrollStore = new RecipeEnrollmentStore(jdbcUrl);
        var did = "did:wyrd:companion-a";
        enrollStore.upsert(new RecipeEnrollment(
            "retrain-classifier-head", did, CadenceTier.WARMUP, 0,
            Instant.now(), true, Set.of()));

        var paused = postJson(
            "/api/recipes/retrain-classifier-head/pause?agentDid=" + did,
            "");
        assertThat(paused).containsEntry("status", "paused");
        assertThat(enrollStore.find("retrain-classifier-head", did)
            .orElseThrow().enabled()).isFalse();

        var resumed = postJson(
            "/api/recipes/retrain-classifier-head/resume?agentDid=" + did,
            "");
        assertThat(resumed).containsEntry("status", "resumed");
        assertThat(enrollStore.find("retrain-classifier-head", did)
            .orElseThrow().enabled()).isTrue();
    }

    @Test
    void pause_unknown_returns_not_found() throws Exception {
        var json = postJson("/api/recipes/never-existed/pause", "");
        assertThat(json).containsEntry("status", "not_found");
    }

    private Map<String, Object> getJson(String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return parse(resp.body());
    }

    private Map<String, Object> postJson(String path, String body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return parse(resp.body());
    }

    private static Map<String, Object> parse(String body) {
        try {
            return MAPPER.readValue(body, MAP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
