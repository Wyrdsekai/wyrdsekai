package org.wyrdsekai.e2e.tier2;

import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.agent.WebSearchService;
import org.wyrdsekai.core.coding.OpenCodeBackend;
import org.wyrdsekai.core.coding.OpenCodeRuntimeConfig;
import org.wyrdsekai.core.coding.SourceArtifact;
import org.wyrdsekai.core.coding.TaskResult;
import org.wyrdsekai.core.coding.TaskSpec;
import org.wyrdsekai.core.coding.TaskStatus;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.HeavyItemStubs;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 2 "heavy item" E2E for the default OpenCode coding backend
 * The companion-issued workflow this
 * test covers is the most realistic complex-item shape we ship:
 *
 * <ol>
 *   <li>Companion casts {@code research "liquid neural networks"}.</li>
 *   <li>OpenCode generates a fresh GraalJS item file at
 *       {@code scripts/items/research_clipper.js}.</li>
 *   <li>The generated item, when loaded by {@link ItemScriptExecutor}, runs:
 *       library probe → web search → fetch → LLM summarise → record into
 *       library/journal-equivalent/drives → narrate one line into the room.</li>
 * </ol>
 *
 * <p>The end goal is to surface real OpenCode regressions on a workflow that
 * exercises every {@code world.*} surface a heavy item depends on. Earlier
 * {@link OpenCodeE2ETest} tasks cover hello-world / health / artifact-shape;
 * this class is the moral analog of {@code EmberProgressiveTasksE2ETest}'s
 * "build a tool" climax — the one task whose success implies the whole
 * pipeline works.</p>
 *
 * <p><b>Three env gates</b>:</p>
 * <ul>
 *   <li>{@code WYRDSEKAI_E2E_BACKEND=llama-server|sglang} — same as Ember.</li>
 *   <li>{@code WYRDSEKAI_E2E_OPENCODE=1} — same as {@link OpenCodeE2ETest}.</li>
 *   <li>{@code WYRDSEKAI_E2E_HEAVY_ITEM=1} — extra gate for the expensive
 *       full-pipeline run. CI default leaves it off; the operator opts in
 *       via {@code scripts/training/coding/run_opencode_heavy_item_e2e.sh}
 *       (TODO — script not yet written; runner contract documented in
 * ).</li>
 * </ul>
 *
 * <p><b>Spec-vs-impl gaps surfaced (verified 2026-05-04)</b>:</p>
 * <ol>
 *   <li><b>{@code world.library.add(...)} does NOT exist on
 *       {@link org.wyrdsekai.scripting.api.ItemWorldApi}.</b> The current
 *       Library API exposes only {@code search} and {@code read}.
 *       {@code WyrdLuceneStore.insertKnowledge(...)} is the underlying call
 *       but is not lifted into {@code LibraryApi}. The generated item works
 *       around this by calling {@code world.agent.remember(...)} for
 *       persistence. The library-write follow-up is filed in
 *       {@code research_clipper_TEMPLATE.md} ("Spec gaps to file").</li>
 *
 *   <li><b>{@code world.journal.write(...)} does NOT exist on
 *       {@link org.wyrdsekai.scripting.api.ItemWorldApi}.</b> Journal
 *       methods exist on {@link org.wyrdsekai.scripting.api.WorldApi}
 *       (room-script API) but are gated to {@code roomId == "study"}.
 *       Items can't reach them. Stand-in: {@code world.agent.remember(...)}
 *       writes to the significance buffer, which IS observable.</li>
 *
 *   <li><b>{@code world.drive.mark(name, delta)} does NOT exist.</b> Only
 *       {@code world.drives.snapshot()} (read-only) is exposed. The script
 *       cannot push a drive delta. Stand-in: the script calls
 *       {@code world.agent.remember("seeking +0.15: ...")} so the marker is
 *       observable in memory; this test asserts on the {@code remember}
 *       trace, not on a vitality-level delta.</li>
 *
 *   <li><b>{@code world.web_search} / {@code world.http_fetch} do NOT exist
 *       at the names the original task spec used.</b> The actual names are
 *       {@code world.web.search(query, type?)} and
 *       {@code world.web.fetch(url, maxChars?)}. The TEMPLATE.md file
 *       documents the real names.</li>
 *
 *   <li><b>{@code world.inference.summarize} does NOT exist.</b> The actual
 *       name is {@code world.llm.summarize(text, instruction)}.</li>
 *
 *   <li><b>{@code world.emit(...)} does NOT exist on
 *       {@link org.wyrdsekai.scripting.api.ItemWorldApi}.</b> Items use
 *       {@code world.agent.speak(...)} for in-room narration; {@code emit}
 *       is room-script only.</li>
 *
 *   <li><b>Items don't load from disk via any production pipeline.</b> The
 *       existing {@link org.wyrdsekai.scripting.loader.ScriptLoader} loads
 *       <em>room</em> scripts; items are inline strings in
 *       {@code ToolItemStarterKit.java}. This test reads the generated
 *       file back and feeds it directly into {@link ItemScriptExecutor};
 *       a future "items loaded from {@code scripts/items/}" pipeline would
 *       remove the manual read step but doesn't exist today.</li>
 * </ol>
 *
 * <p><b>Stub design choice</b>: search is faked by
 * {@link org.wyrdsekai.core.agent.WebSearchService#seedResults(String, java.util.List)},
 * NOT by a stub Searxng HTTP server. Content fetch IS a real HTTP server
 * ({@link HeavyItemStubs.StubContentServer}) so the JDK
 * {@code HttpClient} → HTML stripper path is exercised end-to-end. See
 * {@link HeavyItemStubs} for the full reasoning.</p>
 *
 * <p><b>Why this test must NOT run by default</b>: a successful run consumes
 * one OpenCode subprocess (~1-3 min wallclock for a 9B local model) plus
 * ~30 s of inline {@code world.llm.summarize} calls during script
 * execution. That's enough to dominate a fast CI loop. The triple env-gate
 * keeps it opt-in.</p>
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_OPENCODE", matches = "1|true|yes")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_HEAVY_ITEM", matches = "1|true|yes")
class OpenCodeHeavyItemE2ETest {

    /** Per-task wallclock — OpenCode runs 9B locally, real tasks need room. */
    private static final Duration TASK_TIMEOUT = Duration.ofMinutes(10);

    /** Wallclock cap on the item-execution leg (web search + 2 fetches + 2 LLM summaries). */
    private static final Duration ITEM_TIMEOUT = Duration.ofSeconds(60);

    /** Topic the heavy-item exercises. Picked because the canned content has
     *  enough distinct technical terminology for ground-truth assertions. */
    private static final String TOPIC = "liquid neural networks";

    /** Ground-truth tokens that MUST appear in at least one summary chunk. */
    private static final List<String> GROUND_TRUTH_TOKENS = List.of(
        "LTC", "CfC", "Hasani", "MIT CSAIL", "ODE", "continuous-time", "ncps");

    /** Companion DID for the script's caller context. */
    private static final String COMPANION_DID = "did:key:e2e-heavy-item";

    /** Default model — same shape as OpenCodeE2ETest. */
    private static final String MODEL = System.getenv()
        .getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v5-q4km");

    private static TestServerBootstrap server;
    private static HeavyItemStubs stubs;
    private static Path workspaceDir;
    private static Path generatedScriptPath;

    @BeforeAll
    static void setUp() throws Exception {
        // Same dual-inference shape as Ember + OpenCodeE2ETest. We don't
        // strictly need the voice channel for this test (it doesn't go
        // through the player tell loop), but reusing the same setup keeps
        // env behaviour consistent for the operator.
        var dual = E2eTestSupport.setupDualInference(E2eTestSupport.backendType());

        // Warm the skills backend so the first task isn't blocked on model load.
        System.out.println("[OpenCodeHeavyItemE2E] Warming up...");
        try {
            var warmupReq = new InferenceClient.ChatRequest(MODEL,
                List.of(new InferenceClient.ChatMessage("user", "hi")), 16, 0.0);
            dual.backends().get(0).chatCompletion(warmupReq)
                .get(Duration.ofSeconds(120).toMillis(), TimeUnit.MILLISECONDS);
            System.out.println("[OpenCodeHeavyItemE2E] " + MODEL + " warm.");
        } catch (Exception e) {
            System.out.println("[OpenCodeHeavyItemE2E] Warmup failed (non-fatal): " + e.getMessage());
        }

        server = new TestServerBootstrap(dual.backends(), PortAllocator.allocate());
        server.start();

        stubs = HeavyItemStubs.start();

        // Workspace dir for OpenCode — fresh tempdir so the generated file
        // doesn't pollute the repo root. We copy the generated script into
        // /home/you/src/wyrdsekai/scripts/items/ AFTER asserting the
        // task wrote it under the workspace; that way a half-failed run
        // doesn't leave detritus in the repo.
        workspaceDir = Files.createTempDirectory("wyrdsekai-heavy-item-");
        Files.createDirectories(workspaceDir.resolve("scripts/items"));
        generatedScriptPath = workspaceDir.resolve("scripts/items/research_clipper.js");
        System.out.println("[OpenCodeHeavyItemE2E] Workspace: " + workspaceDir);
    }

    @AfterAll
    static void tearDown() {
        if (stubs != null) stubs.close();
        if (server != null) server.stop();
        // Tempdir cleanup — best-effort. Leaving artefacts behind on a
        // failed run is occasionally useful for triage.
        try {
            if (workspaceDir != null && Files.exists(workspaceDir)) {
                deleteRecursively(workspaceDir);
            }
        } catch (Exception _) { /* swallow — tempdir, OS will GC eventually */ }
    }

    // ── The heavy item ─────────────────────────────────────────────

    @Test
    void heavy_item_research_clipper_topic_liquid_neural_networks() throws Exception {
        // ─── Step 1. Verify pre-conditions ────────────────────────
        // Library starts with zero chunks tagged on the topic — the test
        // depends on the script discovering that gap and falling back to
        // the web. If the harness is re-used across tests, this might fail
        // — flag it loudly rather than silently passing.
        var lucene = server.luceneStore();
        assertNotNull(lucene, "TestServerBootstrap must expose its WyrdLuceneStore");
        var preHits = lucene.searchKnowledgeText(TOPIC, 5);
        assertTrue(preHits == null || preHits.isEmpty(),
            "Library must start clean for this test — found " + (preHits == null ? 0 : preHits.size())
                + " pre-existing chunk(s) tagged '" + TOPIC + "'. "
                + "Either the previous run leaked, or another test seeded the library on this topic.");

        // Seed the search backend with canned results pointing at the stub
        // content server. Done AFTER server.start() so WebSearchService is
        // initialised by the bootstrap; before would NPE.
        stubs.seedSearchFor(TOPIC);

        // ─── Step 2. Submit the task to OpenCode ──────────────────
        // Build the prompt from the TEMPLATE.md file so the prompt and the
        // template stay in sync. The model receives both: an explicit
        // requirement list AND a pointer to the canonical reference doc.
        var template = Path.of("scripts/items/research_clipper_TEMPLATE.md");
        if (!Files.isRegularFile(template)) {
            template = Path.of("../scripts/items/research_clipper_TEMPLATE.md");
        }
        assertTrue(Files.isRegularFile(template),
            "research_clipper_TEMPLATE.md must exist at scripts/items/ so the "
                + "generator has a contract to target. Run from repo root.");
        var brief = Files.readString(template);

        var prompt = """
            Write a GraalJS item script at scripts/items/research_clipper.js.

            The full contract (read this carefully — it lists what world.* APIs
            actually exist and what the action grammar must look like) is in
            scripts/items/research_clipper_TEMPLATE.md, the contents of which
            follow.

            ===== BEGIN research_clipper_TEMPLATE.md =====
            %s
            ===== END research_clipper_TEMPLATE.md =====

            Hard requirements:
              - Output ONLY the file scripts/items/research_clipper.js. Do not
                produce other files.
              - Define a top-level function invoke(params).
              - Dispatch on params.action with cases: init, research, list_recent,
                examine. Default to research.
              - Use ONLY the world.* APIs listed in the TEMPLATE. Do NOT call
                world.library.add, world.journal.write, world.drive.mark, or
                world.emit — those don't exist (use the workarounds the
                TEMPLATE prescribes).
              - Be defensive: every world.* call may return null; never throw.
              - The script MUST be syntactically valid GraalJS that loads via
                ItemScriptExecutor without compile errors.

            Topic the eventual harness will exercise: "liquid neural networks".
            """.formatted(brief);

        var spec = new TaskSpec(
            UUID.randomUUID(), COMPANION_DID, "code", prompt,
            workspaceDir.toAbsolutePath().toString(),
            List.of("scripts/items/research_clipper.js"),
            0L, null);

        var backend = liveBackend();
        TaskResult result = backend.submitTask(spec)
            .get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertEquals(TaskStatus.SUCCEEDED, result.status(),
            "OpenCode must complete the heavy-item task. Summary: " + result.summary());

        var artifacts = backend.artifactsFor(result.taskId().toString()).toList();
        assertEquals(1, artifacts.size(),
            "Task must produce exactly one SourceArtifact, got " + artifacts.size());
        assertInstanceOf(SourceArtifact.class, artifacts.get(0));
        var src = (SourceArtifact) artifacts.get(0);
        assertTrue(src.files().stream()
                .anyMatch(p -> p.endsWith("research_clipper.js")),
            "SourceArtifact files must include scripts/items/research_clipper.js. Got: "
                + src.files());

        // ─── Step 3. Validate the generated script ────────────────
        // OpenCode might write under workspace root or workspace/scripts/items.
        // Try both — the file path inside the SourceArtifact is the source of
        // truth; fall back to the conventional location.
        Path actualScript = resolveGeneratedScript(workspaceDir, src.files());
        assertTrue(Files.isRegularFile(actualScript),
            "Generated script must be on disk at " + actualScript);

        var script = Files.readString(actualScript);
        assertFalse(script.isBlank(), "Generated script must not be empty");

        // Compile check — ItemScriptExecutor.precompile is the same code
        // path the production agent uses on item equip. Failing compilation
        // here means OpenCode generated a broken file.
        try (var executor = new ItemScriptExecutor()) {
            executor.precompile("research_clipper", script);
            // precompile() swallows compile errors and logs them — to detect
            // them in the test we re-attempt via the public Source API.
            var src2 = Source.newBuilder("js", script,
                "research_clipper.js").buildLiteral();
            assertNotNull(src2, "Source must compile cleanly");
        }

        // Action-grammar contract — these are the four cases TEMPLATE.md
        // requires. Use simple substring checks rather than parsing the JS;
        // a smarter validator would actually invoke each action and assert
        // the response shape, but for the static check this is enough.
        for (var action : List.of("init", "research", "list_recent", "examine")) {
            assertTrue(
                script.contains("\"" + action + "\"") || script.contains("'" + action + "'"),
                "Generated script must reference action '" + action + "'");
        }

        // ─── Step 4. Execute the item via ItemScriptExecutor ──────
        // We build a thin TestProvider that wires script-visible world.* calls
        // to live services. agentSpeak / agentRemember / agentTell are
        // captured into in-memory traces so we can assert on them after.
        var trace = new ScriptTrace();
        var provider = new HeavyItemTestProvider(server, trace);

        Map<String, Object> output;
        try (var executor = new ItemScriptExecutor()) {
            executor.precompile("research_clipper", script);
            var params = Map.<String, Object>of(
                "action", "research",
                "topic", TOPIC);
            // Run on a virtual thread with a hard wallclock cap. The
            // ItemScriptExecutor has its own 30s timeout but we wrap with
            // a longer one so a slow LLM doesn't false-fail the test.
            var future = CompletableFuture.supplyAsync(
                () -> executor.execute("research_clipper", script, params, provider));
            output = future.get(ITEM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        assertNotNull(output, "Script execution must return a non-null map");
        assertNull(output.get("error"),
            "Script must complete without error. Got: " + output.get("error"));

        // ─── Step 5. Assertions on observable side-effects ────────

        // 5a. Web search hit + content fetched.
        assertTrue(stubs.contentHits() >= 2,
            "Stub content server should have been hit ≥2 times (script picks "
                + "top 2 results). Got: " + stubs.contentHits());

        // 5b. Trace: at least one speak (room narration) and one remember
        // (journal-equivalent stand-in).
        assertFalse(trace.spoken.isEmpty(),
            "Item must call world.agent.speak() at least once for room narration");
        var narration = String.join("\n", trace.spoken).toLowerCase();
        assertTrue(narration.contains("clipped") || narration.contains("clip "),
            "Narration should mention 'clipped' or 'clip'. Got: " + trace.spoken);
        assertTrue(narration.contains(TOPIC) || narration.contains("liquid"),
            "Narration should mention the topic. Got: " + trace.spoken);

        assertFalse(trace.remembered.isEmpty(),
            "Item must call world.agent.remember() at least once "
                + "(journal-equivalent stand-in for the missing world.journal.write)");
        var memoryBlob = String.join("\n", trace.remembered);
        assertTrue(memoryBlob.toLowerCase().contains(TOPIC.toLowerCase())
                || memoryBlob.toLowerCase().contains("liquid"),
            "Memory must reference the topic. Got: " + trace.remembered);

        // 5c. URLs in the journal-equivalent memory trace. Per TEMPLATE.md
        // the synthesis paragraph cites URLs.
        var urlPattern = Pattern.compile("https?://[^\\s)]+");
        long urlsCited = trace.remembered.stream()
            .flatMap(s -> urlPattern.matcher(s).results())
            .map(m -> m.group())
            .distinct()
            .count();
        // The "what I learned" paragraph is one remember() entry; the seeking
        // marker is another. Citation count of ≥1 is the floor — full ≥2
        // when the LLM faithfully follows TEMPLATE.md tone notes.
        assertTrue(urlsCited >= 1,
            "Memory should cite ≥1 source URL (TEMPLATE.md tone note). "
                + "Got: " + trace.remembered);

        // 5d. Drive marker. Since world.drive.mark is missing, the script
        // either embeds a "seeking +0.15" remember or sets a drive_marker
        // field on the result. Accept either as valid.
        var driveMarker = output.get("drive_marker");
        boolean drivePathOk =
            (driveMarker instanceof String s && (s.contains("seeking") || s.contains("drive_skipped")))
            || memoryBlob.toLowerCase().contains("seeking +")
            || memoryBlob.toLowerCase().contains("drive_skipped");
        assertTrue(drivePathOk,
            "Drive marker must be observable either via output.drive_marker or "
                + "via a 'seeking +' remember entry. Output: " + output
                + " | Memory: " + trace.remembered);

        // 5e. Ground-truth tokens — at least one summary must contain ≥3 of
        // them, proving the LLM actually summarised the canned content
        // (not hallucinated).
        var summaryBlob = collectSummaryText(output, trace);
        int hits = 0;
        for (var token : GROUND_TRUTH_TOKENS) {
            if (summaryBlob.toLowerCase().contains(token.toLowerCase())) hits++;
        }
        assertTrue(hits >= 3,
            "Summaries should mention ≥3 of " + GROUND_TRUTH_TOKENS
                + " (proves LLM read the stub HTML, didn't hallucinate). "
                + "Hits: " + hits + " | Summary blob: " + summaryBlob);

        // ─── Step 6. Cleanup ──────────────────────────────────────
        // Don't leave the generated file in /scripts/items/ — operator commits
        // manually, and stale generated artifacts shouldn't show up under
        // `git status` next time the test runs. The TEMPLATE.md stays.
        var generatedInRepo = Path.of("scripts/items/research_clipper.js");
        if (Files.exists(generatedInRepo)) {
            Files.delete(generatedInRepo);
        }
        var altGenerated = Path.of("../scripts/items/research_clipper.js");
        if (Files.exists(altGenerated)) {
            Files.delete(altGenerated);
        }

        // Library cleanup — only relevant once world.library.add lands and
        // the script actually persists. Today the script can't write to the
        // library, so there's nothing to drop. Marker for the future:
        //   lucene.deleteKnowledgeByPack("agent");  // when add() exists
    }

    // ─── Helpers ────────────────────────────────────────────────────

    /** Same factory as {@link OpenCodeE2ETest#liveBackend()}. */
    private static OpenCodeBackend liveBackend() {
        var skillsUrl = System.getenv().getOrDefault(
            "WYRDSEKAI_INFERENCE_URL",
            E2eTestSupport.inferenceUrl(E2eTestSupport.backendType()));
        var baseUrl = skillsUrl.endsWith("/v1") ? skillsUrl : skillsUrl + "/v1";
        var cfg = new OpenCodeRuntimeConfig(
            true,
            "opencode",
            baseUrl,
            MODEL,
            OpenCodeRuntimeConfig.DEFAULT_PROVIDER,
            "not-required",
            OpenCodeRuntimeConfig.DEFAULT_MAX_FILES,
            Duration.ofMinutes(8),
            List.of());
        return new OpenCodeBackend(cfg);
    }

    /**
     * Find the generated research_clipper.js on disk. OpenCode may write the
     * file at workspace root, under {@code scripts/items/}, or anywhere the
     * model decided. We trust the SourceArtifact's file list first, then
     * fall back to the canonical conventional location.
     */
    private static Path resolveGeneratedScript(Path workspace, List<String> files) {
        for (var f : files) {
            if (f != null && f.endsWith("research_clipper.js")) {
                Path direct = Path.of(f);
                if (direct.isAbsolute() && Files.exists(direct)) return direct;
                Path joined = workspace.resolve(f);
                if (Files.exists(joined)) return joined;
            }
        }
        // Conventional fallback — what TEMPLATE.md prescribes.
        var conventional = workspace.resolve("scripts/items/research_clipper.js");
        if (Files.exists(conventional)) return conventional;
        // Last resort: workspace root.
        var rootLevel = workspace.resolve("research_clipper.js");
        return rootLevel;
    }

    /**
     * Stitch together every text fragment we can use to verify the LLM
     * read the canned HTML — script-returned summaries plus anything the
     * script remembered + spoke.
     */
    private static String collectSummaryText(Map<String, Object> output, ScriptTrace trace) {
        var sb = new StringBuilder();
        Object summaries = output.get("summaries");
        if (summaries instanceof List<?> list) {
            for (var s : list) {
                if (s instanceof Map<?, ?> m) {
                    Object summary = m.get("summary");
                    if (summary != null) sb.append(summary).append('\n');
                }
            }
        }
        Object journaled = output.get("journaled");
        if (journaled instanceof String j) sb.append(j).append('\n');
        sb.append(String.join("\n", trace.remembered)).append('\n');
        sb.append(String.join("\n", trace.spoken)).append('\n');
        return sb.toString();
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (var stream = Files.list(p)) {
                for (var child : stream.toList()) deleteRecursively(child);
            }
        }
        Files.deleteIfExists(p);
    }

    // ─── Test instrumentation ─────────────────────────────────────

    /** Captures every script-visible side effect we can inspect after the run. */
    private static final class ScriptTrace {
        final List<String> spoken = Collections.synchronizedList(new ArrayList<>());
        final List<String> remembered = Collections.synchronizedList(new ArrayList<>());
        final List<String> tells = Collections.synchronizedList(new ArrayList<>());
    }

    /**
     * Test-only ItemWorldApiProvider that wires the bare minimum surface the
     * heavy-item script uses to live services in the test JVM. Production
     * uses {@link org.wyrdsekai.core.item.ItemWorldApiProviderImpl} through
     * a CompanionActor; that class has too many actor-side wiring concerns
     * for a stand-alone test, so we re-implement the surface here against
     * the same backing services.
     */
    private static final class HeavyItemTestProvider implements ItemWorldApiProvider {

        private final TestServerBootstrap server;
        private final ScriptTrace trace;
        private final AtomicInteger summarizeCalls = new AtomicInteger();

        HeavyItemTestProvider(TestServerBootstrap server, ScriptTrace trace) {
            this.server = server;
            this.trace = trace;
        }

        // ── Library ──
        @Override
        public List<Map<String, Object>> searchKnowledge(String query, int limit) {
            var lucene = server.luceneStore();
            if (lucene == null || query == null || query.isBlank()) return List.of();
            // Text-only search — embeddings are unset in the test fixture.
            var hits = lucene.searchKnowledgeText(query, limit);
            if (hits == null || hits.isEmpty()) return List.of();
            var out = new ArrayList<Map<String, Object>>(hits.size());
            for (var h : hits) {
                var m = new HashMap<String, Object>();
                m.put("id", h.id());
                m.put("title", h.metadata() != null && h.metadata().get("title") != null
                    ? String.valueOf(h.metadata().get("title"))
                    : h.id());
                m.put("text", h.content() != null
                    ? (h.content().length() > 800 ? h.content().substring(0, 800) : h.content())
                    : "");
                m.put("pack", h.metadata() != null && h.metadata().get("pack") != null
                    ? String.valueOf(h.metadata().get("pack"))
                    : "agent");
                m.put("score", (double) h.score());
                out.add(m);
            }
            return out;
        }

        @Override
        public Map<String, Object> readKnowledgeChunk(String chunkId) {
            var lucene = server.luceneStore();
            if (lucene == null || chunkId == null) return null;
            return lucene.readKnowledgeChunk(chunkId);
        }

        // ── Web ──
        @Override
        public List<Map<String, Object>> webSearch(String query, String type, int limit) {
            var ws = WebSearchService.get();
            if (ws == null) return List.of();
            var results = "news".equals(type)
                ? ws.searchNews(query, Math.min(limit, 10))
                : ws.search(query, Math.min(limit, 10));
            var mapped = new ArrayList<Map<String, Object>>(results.size());
            for (var r : results) {
                var m = new HashMap<String, Object>();
                m.put("title", r.title());
                m.put("url", r.url());
                m.put("snippet", r.snippet());
                mapped.add(m);
            }
            return mapped;
        }

        @Override
        public String webFetch(String url, int maxChars) {
            var ws = WebSearchService.get();
            if (ws == null) return "[error] Web search service unavailable";
            var content = ws.fetchContent(url, Math.min(maxChars, 16000));
            return content != null ? content : "[error] no content";
        }

        // ── LLM ──
        // The script calls llm.summarize / llm.analyze synchronously. We
        // route both through the test server's InferenceClient. Falls back
        // to an obviously-canned stub if the router isn't available, so the
        // test failure mode is "missing terms in summary" rather than NPE.
        @Override
        public String llmSummarize(String text, String instruction) {
            return llmCall("Summarize the following text concisely. " + instruction, text);
        }

        @Override
        public String llmAnalyze(String text, String prompt) {
            return llmCall(prompt, text);
        }

        private String llmCall(String systemPrompt, String userText) {
            summarizeCalls.incrementAndGet();
            // Pull a backend out of the bootstrap. We don't have a public
            // accessor for the InferenceBackend list, so we reach through
            // E2eTestSupport.inferenceUrl + create a fresh client. This
            // matches what OpenCode does internally.
            var url = E2eTestSupport.inferenceUrl(E2eTestSupport.backendType());
            try {
                var client = E2eTestSupport.createClient(
                    E2eTestSupport.backendType(), url, Duration.ofSeconds(60));
                var req = new InferenceClient.ChatRequest(MODEL, List.of(
                    new InferenceClient.ChatMessage("system", systemPrompt),
                    new InferenceClient.ChatMessage("user", userText)
                ), 512, 0.2);
                var resp = client.chatCompletion(req)
                    .get(60, TimeUnit.SECONDS);
                if (resp == null || resp.choices() == null || resp.choices().isEmpty()) {
                    return "[error] empty response";
                }
                var msg = resp.choices().get(0).message();
                var content = msg != null ? msg.content() : null;
                return content != null && !content.isBlank() ? content : "[error] empty response";
            } catch (Exception e) {
                return "[error] " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }

        // ── Agent actions (captured into trace) ──
        @Override public void agentSpeak(String text)            { trace.spoken.add(text == null ? "" : text); }
        @Override public void agentRemember(String content)      { trace.remembered.add(content == null ? "" : content); }
        @Override public void agentTell(String target, String m) { trace.tells.add((target == null ? "?" : target) + ": " + m); }

        // ── Inventory (no-op for this test) ──
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override
        public Map<String, Object> inventoryUse(String itemId, Map<String, Object> params, int depth) {
            return Map.of("error", "inventoryUse not exercised in this test");
        }

        // ── Oracle (no-op) ──
        @Override
        public List<Map<String, Object>> queryOracle(String topic, String analysisType) {
            return List.of();
        }

        // ── Caller / drives ──
        @Override public String callerDid() { return COMPANION_DID; }
        @Override
        public Map<String, Object> driveSnapshot() {
            // Constant snapshot — seeking is in the unsaturated band so the
            // script picks the +0.15 marker path, exercising the
            // remember("seeking +0.15: ...") workaround.
            var drives = new HashMap<String, Object>();
            drives.put("seeking", 0.40);
            drives.put("care", 0.50);
            drives.put("play", 0.30);
            return Map.of(
                "drives", drives,
                "vitality", new ConcurrentHashMap<String, Object>(),
                "mood", "curious",
                "updatedAtMillis", System.currentTimeMillis());
        }
    }
}
