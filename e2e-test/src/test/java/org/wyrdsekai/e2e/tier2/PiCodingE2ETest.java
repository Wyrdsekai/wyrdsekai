package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.coding.AuthMode;
import org.wyrdsekai.core.coding.AuthResolver;
import org.wyrdsekai.core.coding.PiCodingBackend;
import org.wyrdsekai.core.coding.PiCodingRuntimeConfig;
import org.wyrdsekai.core.coding.SourceArtifact;
import org.wyrdsekai.core.coding.TaskResult;
import org.wyrdsekai.core.coding.TaskSpec;
import org.wyrdsekai.core.coding.TaskStatus;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemManifest;
import org.wyrdsekai.scripting.api.ItemManifestParser;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tier 2 E2E for the {@link PiCodingBackend} — pi.dev's
 * {@code @mariozechner/pi-coding-agent} (
 * Phase 2f, May 2026).
 *
 * <p>Pi's killer feature for our purposes: it can route to a local
 * llama-server via {@code ~/.pi/agent/models.json}, so the default
 * test path is <em>free</em> — no cloud billing. Cloud providers
 * (Anthropic, OpenAI, Google, …) are still selectable via env config.</p>
 *
 * <p>Two env gates:
 * <ul>
 *   <li>{@code WYRDSEKAI_E2E_PI=1} — opts the suite in. Set by
 *       {@code scripts/training/coding/run_pi_coding_e2e.sh} after it
 *       has located the {@code pi} binary on PATH and seeded
 *       {@code ~/.pi/agent/models.json} with the local llama-drive.</li>
 *   <li>{@code WYRDSEKAI_PI_MODEL} — the model alias to pass to pi.
 *       Default is {@code 9b-v5-q4km} (our local model).
 *       Override to {@code sonnet}/{@code haiku}/{@code gpt-4o}/etc.
 *       to hit the cloud — pair with the matching API key env var.</li>
 *   <li>{@code WYRDSEKAI_PI_PROVIDER} — provider name. Default is
 *       {@code local} (our llama-drive). Switch to {@code anthropic},
 *       {@code openai}, etc. for cloud routing.</li>
 *   <li>{@code WYRDSEKAI_PI_API_KEY} — optional API key. When set, it
 *       flows in via the {@link AuthMode.ApiKey} path.</li>
 * </ul></p>
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_PI", matches = "1|true|yes")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PiCodingE2ETest {

    private static final Duration TASK_TIMEOUT = Duration.ofMinutes(5);

    private static final String MODEL = System.getenv()
        .getOrDefault("WYRDSEKAI_PI_MODEL", "9b-v5-q4km");
    private static final String PROVIDER = System.getenv()
        .getOrDefault("WYRDSEKAI_PI_PROVIDER", "local");

    static AuthResolver envAuthResolver() {
        return name -> {
            // 1. Explicit pi-specific key
            var k = System.getenv("WYRDSEKAI_PI_API_KEY");
            if (k != null && !k.isBlank()) return new AuthMode.ApiKey(k);
            // 2. Per-provider keys (pi maps these to the right provider
            //    on its end; we just hand it whichever matches the
            //    chosen provider).
            switch (PROVIDER.toLowerCase()) {
                case "anthropic" -> {
                    k = System.getenv("ANTHROPIC_API_KEY");
                    if (k != null && !k.isBlank()) return new AuthMode.ApiKey(k);
                }
                case "openai" -> {
                    k = System.getenv("OPENAI_API_KEY");
                    if (k != null && !k.isBlank()) return new AuthMode.ApiKey(k);
                }
                case "google" -> {
                    k = System.getenv("GOOGLE_API_KEY");
                    if (k != null && !k.isBlank()) return new AuthMode.ApiKey(k);
                }
                case "groq" -> {
                    k = System.getenv("GROQ_API_KEY");
                    if (k != null && !k.isBlank()) return new AuthMode.ApiKey(k);
                }
                case "local" -> {
                    // llama-server is unauthenticated on localhost — pi
                    // still wants a non-empty value when its provider
                    // entry declares apiKey. Any sentinel works.
                    return new AuthMode.ApiKey("local-llama");
                }
                default -> { /* fall through to OAuth check */ }
            }
            // 3. OAuth opt-in
            var oauth = System.getenv("WYRDSEKAI_E2E_PI_USE_OAUTH");
            if ("1".equals(oauth) || "true".equalsIgnoreCase(oauth)) {
                return new AuthMode.OAuthSession();
            }
            return new AuthMode.AuthMissing(name,
                "set WYRDSEKAI_PI_API_KEY (or per-provider key, or "
                    + "WYRDSEKAI_E2E_PI_USE_OAUTH=1)",
                "no auth env wired for PiCodingE2ETest");
        };
    }

    private static boolean authAvailable() {
        if (System.getenv("WYRDSEKAI_PI_API_KEY") instanceof String s && !s.isBlank()) return true;
        switch (PROVIDER.toLowerCase()) {
            case "local" -> { return true; }  // llama-server is keyless
            case "anthropic" -> {
                var k = System.getenv("ANTHROPIC_API_KEY");
                if (k != null && !k.isBlank()) return true;
            }
            case "openai" -> {
                var k = System.getenv("OPENAI_API_KEY");
                if (k != null && !k.isBlank()) return true;
            }
            case "google" -> {
                var k = System.getenv("GOOGLE_API_KEY");
                if (k != null && !k.isBlank()) return true;
            }
            case "groq" -> {
                var k = System.getenv("GROQ_API_KEY");
                if (k != null && !k.isBlank()) return true;
            }
        }
        var oauth = System.getenv("WYRDSEKAI_E2E_PI_USE_OAUTH");
        return "1".equals(oauth) || "true".equalsIgnoreCase(oauth);
    }

    private static PiCodingBackend liveBackend() {
        var cfg = new PiCodingRuntimeConfig(
            true,
            PiCodingRuntimeConfig.DEFAULT_EXECUTABLE,
            MODEL,
            PROVIDER,
            true,
            TASK_TIMEOUT,
            List.of()
        );
        return new PiCodingBackend(cfg, envAuthResolver());
    }

    // ── Task 1 ──────────────────────────────────────────────────────

    @Test @Order(1)
    void task1_backend_health() throws Exception {
        var backend = liveBackend();
        var healthy = backend.healthCheck()
            .get(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);
        assertTrue(healthy,
            "pi --version probe must succeed before running E2E. "
                + "Install: npm i -g @mariozechner/pi-coding-agent. "
                + "WYRDSEKAI_E2E_PI=1 was set but the binary is missing.");
    }

    // ── Task 2 ──────────────────────────────────────────────────────

    @Test @Order(2)
    void task2_simple_submit() throws Exception {
        assumeTrue(authAvailable(),
            "Skipping live submit — no auth env wired for provider " + PROVIDER + ".");

        var backend = liveBackend();
        var spec = TaskSpec.create("did:key:pi-e2e", "code",
            "Reply with exactly the string OK and nothing else.");
        var fut = backend.submitTask(spec);
        var result = fut.get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertNotNull(result, "submitTask must produce a TaskResult");
        System.out.println("[pi task2] provider=" + PROVIDER + " model=" + MODEL
            + "  status=" + result.status()
            + "  durationMs=" + result.durationMs()
            + "  cu=" + result.cuConsumed()
            + "  summary=" + result.summary());

        if (result.status() == TaskStatus.FAILED
                && result.summary() != null
                && result.summary().contains("LOGIN_REQUIRED")) {
            throw new AssertionError("Auth resolver returned LOGIN_REQUIRED "
                + "even though authAvailable() said true. " + result.summary());
        }
        assertEquals(TaskStatus.SUCCEEDED, result.status(),
            "Expected SUCCEEDED, got " + result.status() + " — " + result.summary());
        assertNotNull(result.summary(), "result.summary must be non-null");
        assertTrue(result.summary().toUpperCase().contains("OK"),
            "Expected 'OK' in summary, got: " + result.summary());
    }

    // ── Task 3 ──────────────────────────────────────────────────────

    @Test @Order(3)
    void task3_items_as_tools_shape() throws Exception {
        assumeTrue(authAvailable(),
            "Skipping items-as-tools task — no auth env wired.");

        // Seed a temp workspace and pass it via spec.workspaceHint() so
        // pi's write/edit tools land the artifact somewhere we can scan
        // afterwards. Mirrors the OpenHands E2E task10 contract: the
        // model writes to disk, the test verifies on disk.
        var workspace = Files.createTempDirectory("pi-e2e-task3-");
        long submitMillis = System.currentTimeMillis();
        try {
            var taskPrompt = "Write a single Wyrdsekai item-as-tool to a file "
                + "named `echo_item.js` in the current working directory. "
                + "manifest fields: name=\"echo_item\", version=\"1.0.0\", "
                + "no capabilities, author=\"did:key:e2e\". "
                + "invoke(params) must return "
                + "{ ok: true, summary: \"echoed: \" + (params.text || \"\") }. "
                + "Use the write tool. The file MUST contain "
                + "`exports.manifest = { … }` and `function invoke(params)`.";

            var backend = liveBackend();
            var spec = new TaskSpec(
                UUID.randomUUID(),
                "did:key:pi-e2e",
                "code",
                taskPrompt,
                workspace.toString(),
                List.of(),
                0L,
                null);
            var fut = backend.submitTask(spec);
            var result = fut.get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            System.out.println("[pi task3] provider=" + PROVIDER + " model=" + MODEL
                + "  status=" + result.status()
                + "  durationMs=" + result.durationMs()
                + "  workspace=" + workspace);
            assertEquals(TaskStatus.SUCCEEDED, result.status(),
                "Expected SUCCEEDED, got " + result.status() + " — " + result.summary());

            // Scan the workspace for fresh .js files declaring an
            // items-as-tools manifest. Same picker shape as the
            // OpenHands E2E task10. The 9B drive hardcodes
            // {@code /workspace/} into its tool calls (legacy from
            // OpenHands bind-mount training), so we also scan there.
            var scanDirs = new ArrayList<Path>();
            scanDirs.add(workspace);
            var hardcodedWorkspace = Path.of("/workspace");
            if (Files.isDirectory(hardcodedWorkspace)) {
                scanDirs.add(hardcodedWorkspace);
            }
            Path matched = null;
            String body = null;
            for (var dir : scanDirs) {
                if (matched != null) break;
                try (var stream = Files.walk(dir)) {
                    for (var p : (Iterable<Path>) stream::iterator) {
                        if (!p.getFileName().toString().endsWith(".js")) continue;
                        long mtime = Files.getLastModifiedTime(p).toMillis();
                        if (mtime < submitMillis - 5000L) continue;
                        var s = Files.readString(p);
                        if (s.contains("exports.manifest")
                                && (s.contains("function invoke") || s.contains("invoke ="))) {
                            matched = p;
                            body = s;
                            break;
                        }
                    }
                }
            }
            System.out.println("[pi task3] matched file: " + matched
                + "  bytes=" + (body == null ? 0 : body.length()));
            // Snapshot the artifact to a stable path for human review,
            // since the workspace is deleted in finally{}.
            if (matched != null && body != null) {
                var snapshot = Path.of("/tmp/pi_e2e_echo_item.js");
                try {
                    Files.writeString(snapshot, body);
                    System.out.println("[pi task3] snapshot copied to: " + snapshot);
                } catch (Exception e) {
                    System.out.println("[pi task3] snapshot failed: " + e.getMessage());
                }
            }
            assertNotNull(matched,
                "Pi must write a .js file in the workspace declaring "
                + "exports.manifest + invoke. Workspace: " + workspace
                + ". Reply summary was:\n" + result.summary());
            assertTrue(body.contains("exports.manifest"),
                "Items-as-tools artifact must declare exports.manifest. Got:\n" + body);
            assertTrue(body.contains("function invoke") || body.contains("invoke ="),
                "Items-as-tools artifact must define invoke. Got:\n" + body);
        } finally {
            try (var stream = Files.walk(workspace)) {
                stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); }
                                    catch (Exception _) { /* ignore */ } });
            } catch (Exception _) { /* ignore */ }
        }
    }

    // ── Task 4: full pipeline (mirrors OpenHands task10_full_pipeline) ──

    @Test @Order(4)
    void task4_full_pipeline() throws Exception {
        assumeTrue(authAvailable(),
            "Skipping full-pipeline task — no auth env wired.");

        // Same proof bar as OpenHands: agent generates a music_pulse-shape
        // research item, executor runs it via a live LiveResearchProvider
        // (Searxng → HTTP fetch → llm.summarize). End-to-end items-as-tools.
        var workspace = Files.createTempDirectory("pi-e2e-task4-");
        long submitMillis = System.currentTimeMillis();
        try {
            var taskPrompt = "Write a Wyrdsekai item-as-tool to a file "
                + "`music_pulse.js` in the current working directory. "
                + "manifest fields: name=\"music_pulse\", version=\"1.0.0\", "
                + "author=\"did:key:e2e\", "
                + "capabilities=[\"web.search\", \"web.fetch\", \"llm.summarize\"]. "
                + "invoke(params) must take params.genre (string), call "
                + "world.web.search('popular '+params.genre+' artists', 'general', 5), "
                + "iterate the top 3 results calling world.web.fetch(url, 8000), "
                + "concatenate fetched content into a digest, then call "
                + "world.llm.summarize(digest, 'Summarize these '+params.genre+' artists'). "
                + "Return { ok: true, summary, sources: [urls] }. "
                + "Use the write tool. The file MUST contain "
                + "`exports.manifest = { … }` and `function invoke(params)`.";

            var backend = liveBackend();
            var spec = new TaskSpec(
                UUID.randomUUID(),
                "did:key:pi-e2e",
                "code",
                taskPrompt,
                workspace.toString(),
                List.of(),
                0L,
                null);
            var fut = backend.submitTask(spec);
            var result = fut.get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            System.out.println("[pi task4] provider=" + PROVIDER + " model=" + MODEL
                + "  status=" + result.status()
                + "  durationMs=" + result.durationMs()
                + "  workspace=" + workspace);
            assertEquals(TaskStatus.SUCCEEDED, result.status(),
                "Expected SUCCEEDED, got " + result.status() + " — " + result.summary());

            // Locate the generated artifact (workspace + /workspace fallback).
            var scanDirs = new ArrayList<Path>();
            scanDirs.add(workspace);
            var hardcodedWorkspace = Path.of("/workspace");
            if (Files.isDirectory(hardcodedWorkspace)) {
                scanDirs.add(hardcodedWorkspace);
            }
            Path matched = null;
            String src = null;
            ItemManifest manifest = null;
            for (var dir : scanDirs) {
                if (matched != null) break;
                try (var stream = Files.walk(dir)) {
                    for (var p : (Iterable<Path>) stream::iterator) {
                        if (!p.getFileName().toString().endsWith(".js")) continue;
                        long mtime = Files.getLastModifiedTime(p).toMillis();
                        if (mtime < submitMillis - 5000L) continue;
                        var s = Files.readString(p);
                        var m = ItemManifestParser.parse(s);
                        if (m == null) continue;
                        var caps = m.capabilities() == null
                            ? List.<String>of() : m.capabilities();
                        boolean research = caps.contains("web.search")
                            && caps.contains("web.fetch")
                            && (caps.contains("llm.summarize")
                                || caps.contains("llm.analyze")
                                || caps.contains("llm.complete"));
                        if (research) {
                            matched = p;
                            src = s;
                            manifest = m;
                            break;
                        }
                    }
                }
            }
            assertNotNull(matched,
                "Pi must write an items-as-tools .js file with research "
                + "capabilities. Reply summary was:\n" + result.summary());

            // Snapshot for review (matches task3's pattern).
            try {
                Files.writeString(
                    Path.of("/tmp/pi_e2e_music_pulse.js"), src);
                System.out.println("[pi task4] artifact snapshot: /tmp/pi_e2e_music_pulse.js"
                    + "  bytes=" + src.length());
            } catch (Exception _) { /* ignore */ }

            // Execute against live services — same proof as OpenHands task10.
            var voiceUrl = System.getenv().getOrDefault(
                "WYRDSEKAI_E2E_VOICE_URL", "http://localhost:8201");
            var searxngUrl = System.getenv().getOrDefault(
                "WYRDSEKAI_SEARXNG_URL", "http://localhost:8888");
            var liveProvider = new OpenHandsItemReplayTest.ReplayProvider(searxngUrl, voiceUrl);

            try (var executor = new ItemScriptExecutor()) {
                var caps = ItemCapabilitySet.from(manifest);
                var invokeParams = new HashMap<String, Object>();
                invokeParams.put("genre", "pop");

                long t0 = System.currentTimeMillis();
                Map<String, Object> invokeResult = executor.execute(
                    manifest.name(), src, invokeParams, liveProvider, caps);
                long elapsed = System.currentTimeMillis() - t0;

                System.out.println("[pi task4] === LIVE EXECUTION RESULT (" + elapsed + "ms) ===");
                System.out.println("[pi task4] ok=" + invokeResult.get("ok")
                    + "  error=" + invokeResult.get("error"));
                System.out.println("[pi task4] counters: search="
                    + liveProvider.searchCalls.get()
                    + "  fetch=" + liveProvider.fetchCalls.get()
                    + "  summarize=" + liveProvider.summarizeCalls.get());
                System.out.println("[pi task4] sources: " + invokeResult.get("sources"));
                var summary = invokeResult.get("summary");
                if (summary instanceof String s) {
                    System.out.println("[pi task4] summary chars=" + s.length());
                }

                assertEquals(Boolean.TRUE, invokeResult.get("ok"),
                    "Item must report ok:true. Result: " + invokeResult);
                assertNotNull(summary, "Result must include 'summary'");
                assertTrue(summary instanceof String && ((String) summary).length() > 50,
                    "summary should be substantive (>50 chars). Got: " + summary);
                assertTrue(liveProvider.searchCalls.get() >= 1,
                    "world.web.search should have fired");
                assertTrue(liveProvider.fetchCalls.get() >= 1,
                    "world.web.fetch should have fired");
                assertTrue(liveProvider.summarizeCalls.get() >= 1,
                    "world.llm.summarize should have fired");
            }
        } finally {
            try (var stream = Files.walk(workspace)) {
                stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); }
                                    catch (Exception _) { /* ignore */ } });
            } catch (Exception _) { /* ignore */ }
        }
    }

    // ── Diverse-shape probes (task5-8) ──────────────────────────────
    // Each pushes a different fragility surface beyond task4's linear
    // research chain. Stub providers replace LiveResearchProvider so we
    // can assert on the exact tool calls the agent's code makes.

    /**
     * Submit a task with workspace + scan for a research-flavored .js
     * shaped to declare {@code expectedCaps}. Returns (manifest, src)
     * on success; throws otherwise.
     */
    private record GeneratedItem(
        ItemManifest manifest,
        String src,
        Path matchedFile,
        TaskResult result) {}

    private GeneratedItem generateItem(String fileName, String expectedItemName,
                                        List<String> requiredCaps,
                                        String taskPrompt) throws Exception {
        var workspace = Files.createTempDirectory("pi-e2e-shape-");
        long submitMillis = System.currentTimeMillis();
        var backend = liveBackend();
        var spec = new TaskSpec(
            UUID.randomUUID(),
            "did:key:pi-e2e",
            "code",
            taskPrompt,
            workspace.toString(),
            List.of(),
            0L,
            null);
        var fut = backend.submitTask(spec);
        var result = fut.get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertEquals(TaskStatus.SUCCEEDED, result.status(),
            "Expected SUCCEEDED, got " + result.status() + " — " + result.summary());

        var scanDirs = new ArrayList<Path>();
        scanDirs.add(workspace);
        var hardcodedWorkspace = Path.of("/workspace");
        if (Files.isDirectory(hardcodedWorkspace)) {
            scanDirs.add(hardcodedWorkspace);
        }
        Path matched = null;
        String src = null;
        ItemManifest manifest = null;
        for (var dir : scanDirs) {
            if (matched != null) break;
            try (var stream = Files.walk(dir)) {
                for (var p : (Iterable<Path>) stream::iterator) {
                    if (!p.getFileName().toString().endsWith(".js")) continue;
                    long mtime = Files.getLastModifiedTime(p).toMillis();
                    if (mtime < submitMillis - 5000L) continue;
                    var s = Files.readString(p);
                    var m = ItemManifestParser.parse(s);
                    if (m == null) continue;
                    var caps = m.capabilities() == null
                        ? List.<String>of() : m.capabilities();
                    boolean hasAll = true;
                    for (var req : requiredCaps) {
                        if (!caps.contains(req)) { hasAll = false; break; }
                    }
                    if (hasAll) {
                        matched = p;
                        src = s;
                        manifest = m;
                        break;
                    }
                }
            }
        }
        assertNotNull(matched,
            "No items-as-tools artifact with required capabilities " + requiredCaps
            + " found. Reply summary:\n" + result.summary());
        // Snapshot for review.
        try {
            Files.writeString(
                Path.of("/tmp/" + fileName), src);
        } catch (Exception _) { /* ignore */ }
        return new GeneratedItem(manifest, src, matched, result);
    }

    // ── Task 5: inventory dispatch ──────────────────────────────────

    @Test @Order(5)
    void task5_inventory_dispatch() throws Exception {
        assumeTrue(authAvailable(), "no auth");

        var taskPrompt = "Write item `find_and_use` to a file `find_and_use.js` "
            + "in the current working directory. exports.manifest must declare "
            + "name=\"find_and_use\", version=\"1.0.0\", author=\"did:key:e2e\", "
            + "capabilities=[\"inventory.list\", \"inventory.use\"]. "
            + "function invoke(params) takes params.name (string) and "
            + "params.useArgs (object). It MUST: "
            + "(1) call var items = world.inventory.list(); "
            + "(2) iterate items to find the one where item.name === params.name; "
            + "(3) if found, call var r = world.inventory.use(item.id, params.useArgs) "
            + "and return { ok: true, used: item.id, result: r }; "
            + "(4) if not found return { ok: false, error: \"not found\" }. "
            + "Use the write tool.";

        var item = generateItem("pi_e2e_find_and_use.js", "find_and_use",
            List.of("inventory.list", "inventory.use"), taskPrompt);

        var calls = new AtomicInteger();
        var lastUseId = new AtomicReference<String>();
        var lastUseParams = new AtomicReference<Map<String, Object>>();
        var stub = new StubItemProvider() {
            @Override public List<Map<String, Object>> inventoryList() {
                return List.of(
                    Map.of("id", "abc-001", "name", "echoer"),
                    Map.of("id", "xyz-002", "name", "sender"),
                    Map.of("id", "def-003", "name", "finder"));
            }
            @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) {
                calls.incrementAndGet();
                lastUseId.set(id);
                lastUseParams.set(p);
                return Map.of("ok", true, "echoed", p == null ? "" : String.valueOf(p.get("text")));
            }
        };

        try (var executor = new ItemScriptExecutor()) {
            var caps = ItemCapabilitySet.from(item.manifest);
            var p = new HashMap<String, Object>();
            p.put("name", "echoer");
            p.put("useArgs", Map.of("text", "hi there"));
            var res = executor.execute(item.manifest.name(), item.src, p, stub, caps);
            System.out.println("[pi task5] result=" + res
                + "  inventoryUse calls=" + calls.get()
                + "  lastUseId=" + lastUseId.get()
                + "  lastUseParams=" + lastUseParams.get());

            assertEquals(Boolean.TRUE, res.get("ok"),
                "Expected ok:true. Got: " + res);
            assertEquals("abc-001", res.get("used"),
                "Expected dispatch to abc-001 (echoer). Got: " + res);
            assertTrue(calls.get() >= 1, "world.inventory.use should fire");
            assertEquals("abc-001", lastUseId.get(),
                "use must be called with the matched item's id");
        }
    }

    // ── Task 6: state via agent.remember ────────────────────────────

    @Test @Order(6)
    void task6_state_with_remember() throws Exception {
        assumeTrue(authAvailable(), "no auth");

        var taskPrompt = "Write item `note_recorder` to a file `note_recorder.js` "
            + "in the current working directory. exports.manifest must declare "
            + "name=\"note_recorder\", version=\"1.0.0\", author=\"did:key:e2e\", "
            + "capabilities=[\"agent.remember\"]. "
            + "function invoke(params) takes params.text (string) and MUST: "
            + "(1) call world.agent.remember(params.text); "
            + "(2) return { ok: true, recorded: params.text }. "
            + "Use the write tool.";

        var item = generateItem("pi_e2e_note_recorder.js", "note_recorder",
            List.of("agent.remember"), taskPrompt);

        var captured = new CopyOnWriteArrayList<String>();
        var stub = new StubItemProvider() {
            @Override public void agentRemember(String content) { captured.add(content); }
        };

        try (var executor = new ItemScriptExecutor()) {
            var caps = ItemCapabilitySet.from(item.manifest);
            for (var note : List.of("alpha", "beta", "gamma")) {
                var p = Map.<String, Object>of("text", note);
                var res = executor.execute(item.manifest.name(), item.src, p, stub, caps);
                assertEquals(Boolean.TRUE, res.get("ok"),
                    "invoke(" + note + ") must return ok:true. Got: " + res);
                assertEquals(note, res.get("recorded"),
                    "invoke must echo recorded text. Got: " + res);
            }
            System.out.println("[pi task6] captured=" + captured);
            assertEquals(List.of("alpha", "beta", "gamma"), captured,
                "All three remember() calls must persist in order");
        }
    }

    // ── Task 7: error handling ──────────────────────────────────────

    @Test @Order(7)
    void task7_error_handling() throws Exception {
        assumeTrue(authAvailable(), "no auth");

        var taskPrompt = "Write item `safe_fetch` to a file `safe_fetch.js` "
            + "in the current working directory. exports.manifest must declare "
            + "name=\"safe_fetch\", version=\"1.0.0\", author=\"did:key:e2e\", "
            + "capabilities=[\"web.fetch\"]. "
            + "function invoke(params) takes params.url (string) and MUST: "
            + "(1) call var r = world.web.fetch(params.url, 1000); "
            + "(2) if r is empty OR r begins with the substring \"[error]\", "
            + "return { ok: false, error: r || \"empty\" }; "
            + "(3) otherwise return { ok: true, content: r }. "
            + "Use the write tool.";

        var item = generateItem("pi_e2e_safe_fetch.js", "safe_fetch",
            List.of("web.fetch"), taskPrompt);

        var stub = new StubItemProvider() {
            @Override public String webFetch(String url, int max) {
                if (url != null && url.contains("fail")) return "[error] HTTP 503 ServerDown";
                return "<html><body>OK page</body></html>";
            }
        };

        try (var executor = new ItemScriptExecutor()) {
            var caps = ItemCapabilitySet.from(item.manifest);

            // Happy path
            var good = executor.execute(item.manifest.name(), item.src,
                Map.of("url", "https://good.example/"), stub, caps);
            System.out.println("[pi task7] good path: " + good);
            assertEquals(Boolean.TRUE, good.get("ok"),
                "Happy path must return ok:true. Got: " + good);
            assertTrue(String.valueOf(good.get("content")).contains("OK page"),
                "content must contain page body. Got: " + good);

            // Error path
            var bad = executor.execute(item.manifest.name(), item.src,
                Map.of("url", "https://fail.example/"), stub, caps);
            System.out.println("[pi task7] error path: " + bad);
            assertEquals(Boolean.FALSE, bad.get("ok"),
                "Error path must return ok:false. Got: " + bad);
            assertTrue(String.valueOf(bad.get("error")).contains("503"),
                "error field must surface the 503. Got: " + bad);
        }
    }

    // ── Task 8: composition (chain two items via inventory.use) ─────

    @Test @Order(8)
    void task8_composition() throws Exception {
        assumeTrue(authAvailable(), "no auth");

        var taskPrompt = "Write item `pipeline` to a file `pipeline.js` "
            + "in the current working directory. exports.manifest must declare "
            + "name=\"pipeline\", version=\"1.0.0\", author=\"did:key:e2e\", "
            + "capabilities=[\"inventory.use\"]. "
            + "function invoke(params) takes params.src (string item id), "
            + "params.transform (string item id), and params.input (object). "
            + "It MUST: "
            + "(1) call var raw = world.inventory.use(params.src, params.input); "
            + "(2) call var out = world.inventory.use(params.transform, raw); "
            + "(3) return { ok: true, raw: raw, transformed: out }. "
            + "Use the write tool.";

        var item = generateItem("pi_e2e_pipeline.js", "pipeline",
            List.of("inventory.use"), taskPrompt);

        var callOrder = new CopyOnWriteArrayList<String>();
        var stub = new StubItemProvider() {
            @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) {
                callOrder.add(id);
                if ("source-001".equals(id)) {
                    return Map.of("data", "raw-payload", "from", "source-001");
                }
                if ("transform-002".equals(id)) {
                    return Map.of("data", "transformed-payload",
                        "wrapped", p == null ? "null" : String.valueOf(p.get("data")));
                }
                return Map.of("ok", false, "error", "unknown id " + id);
            }
        };

        try (var executor = new ItemScriptExecutor()) {
            var caps = ItemCapabilitySet.from(item.manifest);
            var p = new HashMap<String, Object>();
            p.put("src", "source-001");
            p.put("transform", "transform-002");
            p.put("input", Map.of("trigger", "go"));
            var res = executor.execute(item.manifest.name(), item.src, p, stub, caps);
            System.out.println("[pi task8] result=" + res + "  callOrder=" + callOrder);

            assertEquals(Boolean.TRUE, res.get("ok"),
                "Composition must return ok:true. Got: " + res);
            assertEquals(List.of("source-001", "transform-002"), callOrder,
                "inventory.use must fire in order src→transform. Got: " + callOrder);
            // Transformed result threading: transform was given source's
            // output as input; check that the wrapped data shows up.
            var transformed = res.get("transformed");
            assertTrue(transformed instanceof Map,
                "transformed must be a Map. Got: " + transformed);
            @SuppressWarnings("unchecked")
            var t = (Map<String, Object>) transformed;
            assertEquals("raw-payload", t.get("wrapped"),
                "transform must have received source's data field. Got: " + transformed);
        }
    }

    // ── Task 9: large aggregator (~50+ lines, 4 capabilities) ───────

    @Test @Order(9)
    void task9_large_aggregator() throws Exception {
        assumeTrue(authAvailable(), "no auth");

        var taskPrompt = "Write a SUBSTANTIAL Wyrdsekai item-as-tool to a file "
            + "`research_aggregator.js` in the current working directory. "
            + "exports.manifest must declare: name=\"research_aggregator\", "
            + "version=\"1.0.0\", author=\"did:key:e2e\", "
            + "capabilities=[\"web.search\", \"web.fetch\", \"llm.analyze\", \"llm.summarize\"]. "
            + "function invoke(params) takes params.topic (string) and params.maxSources (number). "
            + "It MUST execute this multi-step pipeline: "
            + "STEP 1: results = world.web.search(params.topic, 'general', 10). "
            + "STEP 2: pick the top params.maxSources results (use Math.min). "
            + "STEP 3: for each picked result, call world.web.fetch(result.url, 4000) "
            + "and store the raw content alongside the result. "
            + "STEP 4: for each fetched piece, call analysis = world.llm.analyze(content, "
            + "'Extract 2-3 key claims about ' + params.topic) and store. "
            + "STEP 5: build a digest string: concat(\"Source N: title\\n\" + analysis) for each. "
            + "STEP 6: finalSummary = world.llm.summarize(digest, "
            + "'Write a 3-sentence overview of these findings about ' + params.topic). "
            + "STEP 7: return { ok: true, topic: params.topic, "
            + "sources: [{url, title, analysis}, ...], summary: finalSummary, "
            + "sourceCount: <number of sources actually processed> }. "
            + "Use the write tool. The .js file must be substantial (50+ lines).";

        var item = generateItem("pi_e2e_research_aggregator.js", "research_aggregator",
            List.of("web.search", "web.fetch", "llm.analyze", "llm.summarize"),
            taskPrompt);
        System.out.println("[pi task9] artifact bytes=" + item.src.length()
            + "  lines=" + item.src.split("\n").length);

        var searchCalls = new AtomicInteger();
        var fetchCalls = new AtomicInteger();
        var analyzeCalls = new AtomicInteger();
        var summarizeCalls = new AtomicInteger();

        var stub = new StubItemProvider() {
            @Override public List<Map<String, Object>> webSearch(String q, String type, int n) {
                searchCalls.incrementAndGet();
                return List.of(
                    Map.of("title", "Source One", "url", "https://a.example/1", "snippet", "s1"),
                    Map.of("title", "Source Two", "url", "https://b.example/2", "snippet", "s2"),
                    Map.of("title", "Source Three", "url", "https://c.example/3", "snippet", "s3"),
                    Map.of("title", "Source Four", "url", "https://d.example/4", "snippet", "s4"));
            }
            @Override public String webFetch(String url, int max) {
                fetchCalls.incrementAndGet();
                return "Fetched body for " + url + " — sample content about the topic.";
            }
            @Override public String llmAnalyze(String text, String prompt) {
                analyzeCalls.incrementAndGet();
                return "Analyzed: 2-3 key claims extracted from this source.";
            }
            @Override public String llmSummarize(String text, String inst) {
                summarizeCalls.incrementAndGet();
                return "Final 3-sentence overview synthesizing all sources.";
            }
        };

        try (var executor = new ItemScriptExecutor()) {
            var caps = ItemCapabilitySet.from(item.manifest);
            var p = Map.<String, Object>of("topic", "neural networks", "maxSources", 3);
            var res = executor.execute(item.manifest.name(), item.src, p, stub, caps);
            System.out.println("[pi task9] result-keys=" + res.keySet()
                + "  search=" + searchCalls.get() + " fetch=" + fetchCalls.get()
                + " analyze=" + analyzeCalls.get() + " summarize=" + summarizeCalls.get());

            assertEquals(Boolean.TRUE, res.get("ok"),
                "Aggregator must return ok:true. Got: " + res);
            assertEquals(1, searchCalls.get(), "search should fire exactly once");
            assertEquals(3, fetchCalls.get(), "fetch should fire maxSources(=3) times");
            assertEquals(3, analyzeCalls.get(), "analyze should fire once per fetched source");
            assertEquals(1, summarizeCalls.get(), "summarize should fire exactly once at the end");

            var summary = res.get("summary");
            assertNotNull(summary, "summary must be present");
            assertTrue(summary instanceof String && ((String) summary).contains("3-sentence"),
                "summary must be the llm.summarize result. Got: " + summary);
            var sources = res.get("sources");
            assertTrue(sources instanceof List<?> && ((List<?>) sources).size() == 3,
                "sources must be a list of 3 entries. Got: " + sources);
        }
    }

    // ── Task 10: multi-capability workflow ──────────────────────────

    @Test @Order(10)
    void task10_multi_capability_workflow() throws Exception {
        assumeTrue(authAvailable(), "no auth");

        var taskPrompt = "Write a SUBSTANTIAL Wyrdsekai item-as-tool to a file "
            + "`curator.js` in the current working directory. "
            + "exports.manifest must declare: name=\"curator\", version=\"1.0.0\", "
            + "author=\"did:key:e2e\", "
            + "capabilities=[\"web.search\", \"inventory.use\", \"agent.remember\", \"llm.summarize\"]. "
            + "function invoke(params) takes params.topic (string) and params.tagItemId (string) — "
            + "tagItemId is the id of an inventory item that adds tags to text. "
            + "The pipeline: "
            + "STEP 1: results = world.web.search(params.topic, 'general', 5). Validate non-empty. "
            + "STEP 2: build a digest = top 3 result titles concatenated with newlines. "
            + "STEP 3: tagged = world.inventory.use(params.tagItemId, { text: digest }) — "
            + "this returns an object with a .tags array and .annotated string. "
            + "STEP 4: for each tag in tagged.tags, call world.agent.remember(\"tag:\" + tag). "
            + "STEP 5: overview = world.llm.summarize(tagged.annotated, "
            + "'Summarize the curated findings'). "
            + "STEP 6: return { ok: true, topic, tags: tagged.tags, "
            + "remembered: tagged.tags.length, summary: overview, sourceCount: results.length }. "
            + "Use the write tool. The .js file must be substantial (50+ lines).";

        var item = generateItem("pi_e2e_curator.js", "curator",
            List.of("web.search", "inventory.use", "agent.remember", "llm.summarize"),
            taskPrompt);
        System.out.println("[pi task10] artifact bytes=" + item.src.length()
            + "  lines=" + item.src.split("\n").length);

        var searchCalls = new AtomicInteger();
        var inventoryCalls = new AtomicInteger();
        var rememberCalls = new CopyOnWriteArrayList<String>();
        var summarizeCalls = new AtomicInteger();
        var lastTagInput = new AtomicReference<String>();

        var stub = new StubItemProvider() {
            @Override public List<Map<String, Object>> webSearch(String q, String type, int n) {
                searchCalls.incrementAndGet();
                return List.of(
                    Map.of("title", "Mythos Atlas", "url", "https://a.example/1"),
                    Map.of("title", "Soul Compendium", "url", "https://b.example/2"),
                    Map.of("title", "Resonance Codex", "url", "https://c.example/3"),
                    Map.of("title", "Echo Catalogue", "url", "https://d.example/4"),
                    Map.of("title", "Wyrd Reader", "url", "https://e.example/5"));
            }
            @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) {
                inventoryCalls.incrementAndGet();
                if (p != null && p.get("text") instanceof String s) lastTagInput.set(s);
                return Map.of(
                    "tags", List.of("mythic", "embodied", "resonant"),
                    "annotated", "[mythic][embodied][resonant] " + (p == null ? "" : p.get("text")));
            }
            @Override public void agentRemember(String content) { rememberCalls.add(content); }
            @Override public String llmSummarize(String text, String inst) {
                summarizeCalls.incrementAndGet();
                return "Curated overview: 3 themes — mythic, embodied, resonant.";
            }
        };

        try (var executor = new ItemScriptExecutor()) {
            var caps = ItemCapabilitySet.from(item.manifest);
            var p = Map.<String, Object>of("topic", "soul resonance", "tagItemId", "tagger-001");
            var res = executor.execute(item.manifest.name(), item.src, p, stub, caps);
            System.out.println("[pi task10] result-keys=" + res.keySet()
                + "  search=" + searchCalls.get()
                + " inventoryUse=" + inventoryCalls.get()
                + " remembers=" + rememberCalls.size()
                + " summarize=" + summarizeCalls.get());
            System.out.println("[pi task10] remembered=" + rememberCalls);

            assertEquals(Boolean.TRUE, res.get("ok"),
                "Curator must return ok:true. Got: " + res);
            assertEquals(1, searchCalls.get(), "search fires once");
            assertEquals(1, inventoryCalls.get(), "tag-item fires once via inventory.use");
            assertEquals(3, rememberCalls.size(),
                "remember should fire once per tag (3 tags returned). Got: " + rememberCalls);
            assertEquals(1, summarizeCalls.get(), "summarize fires once at the end");

            // Each remembered string should be the prefixed "tag:..." form
            for (var r : rememberCalls) {
                assertTrue(r.startsWith("tag:"),
                    "Each remember call must use the 'tag:' prefix. Got: " + r);
            }
            var tags = res.get("tags");
            assertTrue(tags instanceof List<?> && ((List<?>) tags).size() == 3,
                "tags must be a list of 3. Got: " + tags);
        }
    }
}
