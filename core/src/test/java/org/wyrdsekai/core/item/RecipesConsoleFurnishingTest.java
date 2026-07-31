package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.recipe.CadenceTier;
import org.wyrdsekai.core.recipe.QueuedRecipe;
import org.wyrdsekai.core.recipe.RecipeEnrollment;
import org.wyrdsekai.core.recipe.RecipeEnrollmentStore;
import org.wyrdsekai.core.recipe.SqlRecipeQueue;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track-C C7 — tier-2 integration test: the
 * {@code recipes_console} scripted Study furnishing renders the recipe
 * scheduler surface (enrollments + recent runs) against a real SQLite
 * recipe DB.
 *
 * <p>Wires: temp jdbcUrl → seed enrollments + completed runs →
 * ScriptedItemLoader picks up {@code scripts/items/recipes_console.js} →
 * ItemScriptExecutor invokes with a TestProvider that delegates the new
 * {@code recipeEnrolled()} + {@code recipeRecentRuns(limit)} surfaces
 * straight to {@link RecipeEnrollmentStore} and {@link SqlRecipeQueue}
 * — the exact path {@link
 * org.wyrdsekai.core.item.ItemWorldApiProviderImpl} uses in prod, only
 * inlined here so the test doesn't need the full provider boot.</p>
 */
class RecipesConsoleFurnishingTest {

    @TempDir
    Path tmp;

    private ScriptedItemLoader loader;
    private ItemScriptExecutor executor;
    private String jdbcUrl;
    private RecipeEnrollmentStore enrollStore;
    private SqlRecipeQueue queue;

    @BeforeEach
    void setUp() {
        loader = ScriptedItemLoader.get();
        var fromCore = Paths.get("..", "scripts", "items");
        var fromRoot = Paths.get("scripts", "items");
        var dir = Files.isDirectory(fromCore) ? fromCore : fromRoot;
        loader.setSearchDirs(List.of(dir));
        loader.reloadAll();

        executor = new ItemScriptExecutor();
        jdbcUrl = "jdbc:sqlite:" + tmp.resolve("recipes-console.db").toAbsolutePath();
        enrollStore = new RecipeEnrollmentStore(jdbcUrl);
        queue = new SqlRecipeQueue(jdbcUrl);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.close();
        loader.setSearchDirs(List.of());
        loader.reloadAll();
    }

    @Test
    void script_loads_and_passes_18_embodiment_validation() {
        // enforces a present `embodiment` block at
        // ScriptedItemLoader load time — a malformed script would not
        // make it into the registry. So if the loader gives us back a
        // definition at all, the script is §18-compliant.
        var def = loader.get("recipes_console");
        assertThat(def).isPresent();
        assertThat(def.get().manifest().version()).isEqualTo("1.0.0");
        // Belt-and-suspenders: the raw script source declares an
        // embodiment block. If a future refactor accidentally drops it
        // the loader test above would also catch the regression.
        assertThat(def.get().scriptSource()).contains("embodiment:");
    }

    @Test
    void render_with_empty_provider_shows_no_enrollments_message() {
        // When the recipe bridge is wired but the queue/enrollment stores
        // are empty (no household enrollments yet), the furnishing must
        // render an empty-state message rather than blow up.
        var def = loader.get("recipes_console").orElseThrow();
        var result = executor.execute(def.itemId(), def.scriptSource(),
            Map.of(), new EmptyProvider());

        assertThat(result.get("ok")).isEqualTo(true);
        var narrative = (String) result.get("narrative");
        assertThat(narrative).contains("Enrolled recipes (0):");
        assertThat(narrative).contains("no enrollments");
        assertThat(narrative).contains("Recent completed runs (0 of last 10):");
    }

    @Test
    void render_renders_enrollments_runs_and_welfare_block() {
        var did = "did:wyrd:companion-x";
        enrollStore.upsert(new RecipeEnrollment(
            "retrain-classifier-head", did, CadenceTier.SETTLING, 3,
            Instant.now(), true,
            Set.of("task_present.misroute")));
        enrollStore.upsert(new RecipeEnrollment(
            "paused-recipe", did, CadenceTier.WARMUP, 0,
            Instant.now(), false, Set.of()));

        var completedAt = Instant.now().minus(Duration.ofMinutes(5));
        for (int i = 0; i < 2; i++) {
            var id = "run-" + i;
            queue.enqueue(QueuedRecipe.newEntry(id,
                "retrain-classifier-head", Map.of(), "cron tick",
                QueuedRecipe.TriggerSource.CRON, did,
                CadenceTier.SETTLING, 3));
            queue.markAttempted(id, completedAt.minusSeconds(60));
            queue.markCompleted(id, QueuedRecipe.Status.SUCCEEDED,
                completedAt.plusSeconds(i), CadenceTier.SETTLING,
                3 + i, "rid-" + i, "ok");
        }
        var pendingId = "run-pending";
        queue.enqueue(QueuedRecipe.newEntry(pendingId,
            "retrain-classifier-head", Map.of(), "cron tick",
            QueuedRecipe.TriggerSource.CRON, did,
            CadenceTier.SETTLING, 3));

        var def = loader.get("recipes_console").orElseThrow();
        var provider = new StoreProvider(jdbcUrl);
        var result = executor.execute(def.itemId(), def.scriptSource(),
            Map.of(), provider);

        assertThat(result.get("ok")).isEqualTo(true);
        var narrative = (String) result.get("narrative");
        assertThat(narrative).contains("Enrolled recipes (2):");
        assertThat(narrative).contains("retrain-classifier-head");
        assertThat(narrative).contains("paused-recipe");
        assertThat(narrative).contains("cadence=SETTLING");
        assertThat(narrative).contains("queue=1");           // pending row counted
        assertThat(narrative).contains("last=SUCCEEDED");
        assertThat(narrative).contains("Recent completed runs (2 of last 10):");
        assertThat(narrative).contains("status=SUCCEEDED");
        assertThat(narrative).contains("Active welfare blocks:");
        assertThat(narrative).contains("paused-recipe (did=" + did + ") — paused");
    }

    @Test
    void runs_only_mode_renders_just_the_runs_section() {
        var did = "did:wyrd:companion-y";
        enrollStore.upsert(new RecipeEnrollment(
            "retrain-classifier-head", did, CadenceTier.WARMUP, 0,
            Instant.now(), true, Set.of()));
        var id = "run-only";
        queue.enqueue(QueuedRecipe.newEntry(id,
            "retrain-classifier-head", Map.of(), null,
            QueuedRecipe.TriggerSource.AGENT, did,
            CadenceTier.WARMUP, 0));
        queue.markAttempted(id, Instant.now().minusSeconds(60));
        queue.markCompleted(id, QueuedRecipe.Status.FAILED,
            Instant.now(), CadenceTier.WARMUP, 0, "rid", "gate failed");

        var def = loader.get("recipes_console").orElseThrow();
        var result = executor.execute(def.itemId(), def.scriptSource(),
            Map.of("text", "runs"), new StoreProvider(jdbcUrl));

        var narrative = (String) result.get("narrative");
        assertThat(narrative).doesNotContain("Enrolled recipes");
        assertThat(narrative).contains("Recent completed runs (1 of last 10):");
        assertThat(narrative).contains("status=FAILED");
        assertThat(narrative).contains("source=AGENT");
    }

    @Test
    void enrolled_only_mode_renders_just_the_enrollments_section() {
        enrollStore.upsert(new RecipeEnrollment(
            "retrain-classifier-head", "did:x", CadenceTier.WARMUP, 0,
            Instant.now(), true, Set.of()));

        var def = loader.get("recipes_console").orElseThrow();
        var result = executor.execute(def.itemId(), def.scriptSource(),
            Map.of("text", "enrolled"), new StoreProvider(jdbcUrl));

        var narrative = (String) result.get("narrative");
        assertThat(narrative).contains("Enrolled recipes (1):");
        assertThat(narrative).doesNotContain("Recent completed runs");
    }

    // ── Test providers ─────────────────────────────────────────────

    /**
     * Tiny stub provider that satisfies the 10 abstract methods on
     * {@link ItemWorldApiProvider}. None of them are exercised by the
     * {@code recipes_console} script (which only calls
     * {@code world.recipe.enrolled()} + {@code recentRuns()}), but
     * Java needs them to compile the implementation classes.
     */
    static class StubProvider implements ItemWorldApiProvider {
        @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String type, int n) { return List.of(); }
        @Override public String webFetch(String url, int n) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String topic, String type) { return List.of(); }
        @Override public String llmSummarize(String text, String instr) { return ""; }
        @Override public String llmAnalyze(String text, String prompt) { return ""; }
        @Override public void agentSpeak(String text) {}
        @Override public void agentRemember(String content) {}
        @Override public void agentTell(String target, String msg) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) {
            return Map.of("error", "not_supported_in_test");
        }
    }

    /** Default no-op provider — recipe bridge methods return empty (graceful degrade). */
    static class EmptyProvider extends StubProvider {}

    /**
     * Provider that mirrors {@link
     * org.wyrdsekai.core.item.ItemWorldApiProviderImpl#recipeEnrolled} and
     * {@code recipeRecentRuns} by reading the real stores. Keeps the test
     * independent of the full provider boot (no Pekko, no LLM router, no
     * Lucene). The shape returned MUST match prod 1:1 — see
     * {@code ItemWorldApiProviderImpl.recipeEnrolled} for the canonical
     * field set.
     */
    static class StoreProvider extends StubProvider {
        private final String jdbcUrl;

        StoreProvider(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }

        @Override
        public List<Map<String, Object>> recipeEnrolled() {
            var enrollStore = new RecipeEnrollmentStore(jdbcUrl);
            var queue = new SqlRecipeQueue(jdbcUrl);
            var rows = new ArrayList<Map<String, Object>>();
            for (var e : enrollStore.listAll()) {
                var qrows = queue.findByRecipe(e.recipeId(), e.agentDid());
                QueuedRecipe lastTerminal = null;
                int pending = 0;
                for (var q : qrows) {
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
                    row.put("lastRunAt", lastTerminal.completedAt() == null ? null
                        : lastTerminal.completedAt().toString());
                    row.put("nextFireEstimate", lastTerminal.completedAt() == null ? null
                        : lastTerminal.completedAt().plus(e.cadenceTier().period()).toString());
                }
                rows.add(row);
            }
            return rows;
        }

        @Override
        public List<Map<String, Object>> recipeRecentRuns(int limit) {
            var queue = new SqlRecipeQueue(jdbcUrl);
            int cap = limit <= 0 ? 10 : Math.min(limit, 100);
            var combined = new ArrayList<QueuedRecipe>();
            combined.addAll(queue.listByStatus(QueuedRecipe.Status.SUCCEEDED));
            combined.addAll(queue.listByStatus(QueuedRecipe.Status.FAILED));
            combined.sort((a, b) -> {
                var aT = a.completedAt();
                var bT = b.completedAt();
                if (aT == null && bT == null) return 0;
                if (aT == null) return 1;
                if (bT == null) return -1;
                return bT.compareTo(aT);
            });
            var rows = new ArrayList<Map<String, Object>>();
            for (var q : combined) {
                if (rows.size() >= cap) break;
                var row = new LinkedHashMap<String, Object>();
                row.put("recipeId", q.recipeId());
                row.put("agentDid", q.agentDid());
                row.put("status", q.status().name());
                row.put("triggerSource", q.triggerSource().name());
                row.put("triggerReason", q.triggerReason());
                row.put("cadenceTier", q.cadenceTier().name());
                row.put("completedAt", q.completedAt() == null ? null
                    : q.completedAt().toString());
                row.put("message", q.message());
                rows.add(row);
            }
            return rows;
        }
    }
}
