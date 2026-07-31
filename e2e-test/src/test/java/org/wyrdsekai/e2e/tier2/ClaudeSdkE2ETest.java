package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.coding.AuthMode;
import org.wyrdsekai.core.coding.AuthResolver;
import org.wyrdsekai.core.coding.ClaudeSdkBackend;
import org.wyrdsekai.core.coding.ClaudeSdkRuntimeConfig;
import org.wyrdsekai.core.coding.SourceArtifact;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tier 2 E2E for the Claude Code SDK coding backend
 * ({@link ClaudeSdkBackend}, / Phase 2e).
 * Mirrors {@link OpenCodeE2ETest} but trimmed to what the cloud-paid
 * tier exercises: subprocess wire-up, auth resolution, and
 * items-as-tools shape compliance.
 *
 * <p>Two env gates so a missing claude binary or missing API key
 * doesn't cause noisy CI failures:
 * <ul>
 *   <li>{@code WYRDSEKAI_E2E_CLAUDE_SDK=1} — opts the suite in.
 *       Set by {@code scripts/training/coding/run_claude_sdk_e2e.sh}
 *       after it has located the {@code claude} binary on PATH.</li>
 *   <li>{@code ANTHROPIC_API_KEY} OR
 *       {@code WYRDSEKAI_E2E_CLAUDE_USE_OAUTH=1} — picks the auth path.
 *       Without either, individual tests {@code Assumptions.assumeTrue}
 *       skip with a clean reason.</li>
 * </ul></p>
 *
 * <p>This is the cloud-paid tier; every run touches Anthropic's billing.
 * Tasks are deliberately tiny to keep the cost-per-CI-run negligible
 * (haiku-tier model, ~30 tokens out per task).</p>
 *
 * <p>Run: {@code WYRDSEKAI_E2E_CLAUDE_SDK=1 ANTHROPIC_API_KEY=sk-ant-…
 * ./gradlew :e2e-test:test -PincludeTags=e2e --tests "*ClaudeSdkE2ETest"}</p>
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_CLAUDE_SDK", matches = "1|true|yes")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClaudeSdkE2ETest {

    /** Per-task wallclock — Anthropic round-trips are usually quick. */
    private static final Duration TASK_TIMEOUT = Duration.ofMinutes(3);

    /**
     * Default test model — haiku-tier so CI cost stays trivial. Override
     * via {@code WYRDSEKAI_CLAUDE_SDK_MODEL} (e.g. {@code "sonnet"} for
     * a quick eyeball comparison).
     */
    private static final String MODEL = System.getenv()
        .getOrDefault("WYRDSEKAI_CLAUDE_SDK_MODEL", "haiku");

    /** Environment-driven auth resolver — picks ApiKey or OAuth, else AuthMissing. */
    static AuthResolver envAuthResolver() {
        return name -> {
            var apiKey = System.getenv("ANTHROPIC_API_KEY");
            if (apiKey != null && !apiKey.isBlank()) {
                return new AuthMode.ApiKey(apiKey);
            }
            var oauth = System.getenv("WYRDSEKAI_E2E_CLAUDE_USE_OAUTH");
            if ("1".equals(oauth) || "true".equalsIgnoreCase(oauth)) {
                return new AuthMode.OAuthSession();
            }
            return new AuthMode.AuthMissing(name,
                "set ANTHROPIC_API_KEY or WYRDSEKAI_E2E_CLAUDE_USE_OAUTH=1",
                "no auth env wired for ClaudeSdkE2ETest");
        };
    }

    private static boolean authAvailable() {
        var k = System.getenv("ANTHROPIC_API_KEY");
        if (k != null && !k.isBlank()) return true;
        var o = System.getenv("WYRDSEKAI_E2E_CLAUDE_USE_OAUTH");
        return "1".equals(o) || "true".equalsIgnoreCase(o);
    }

    private static ClaudeSdkBackend liveBackend() {
        // Wallclock cap of 8 min covers task4's 6-min ceiling with buffer
        // for subprocess startup + JSON parse. Smaller tasks (1/2/3) finish
        // well before this so it isn't a tightening risk for them.
        // Tool permissions + CWD are now handled by ClaudeSdkBackend
        // automatically when spec.workspaceHint() is set — no extraFlags
        // workaround needed.
        var cfg = new ClaudeSdkRuntimeConfig(
            true,
            ClaudeSdkRuntimeConfig.DEFAULT_EXECUTABLE,
            MODEL,
            true,
            Duration.ofMinutes(8),
            List.of()
        );
        return new ClaudeSdkBackend(cfg, envAuthResolver());
    }

    // ── Task 1 ──────────────────────────────────────────────────────

    @Test @Order(1)
    void task1_backend_health() throws Exception {
        // Foundation: the `claude` binary is reachable. No auth needed —
        // `--version` doesn't make a network call. If this fails, every
        // subsequent task is doomed; surface it as one clean failure.
        var backend = liveBackend();
        var healthy = backend.healthCheck()
            .get(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);
        assertTrue(healthy,
            "claude --version probe must succeed before running E2E. "
                + "Install with `npm i -g @anthropic-ai/claude-code` (or "
                + "any path that puts a `claude` binary on PATH). "
                + "WYRDSEKAI_E2E_CLAUDE_SDK=1 was set but the binary is "
                + "missing.");
    }

    // ── Task 2 ──────────────────────────────────────────────────────

    @Test @Order(2)
    void task2_simple_submit() throws Exception {
        assumeTrue(authAvailable(),
            "Skipping live submit — no ANTHROPIC_API_KEY or "
                + "WYRDSEKAI_E2E_CLAUDE_USE_OAUTH=1 in env.");

        var backend = liveBackend();
        var spec = TaskSpec.create("did:key:claude-sdk-e2e", "code",
            "Reply with exactly the string OK and nothing else.");
        var fut = backend.submitTask(spec);
        var result = fut.get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertNotNull(result, "submitTask must produce a TaskResult");
        System.out.println("[claude-sdk task2] status=" + result.status()
            + "  durationMs=" + result.durationMs()
            + "  cu=" + result.cuConsumed()
            + "  summary=" + result.summary());

        // SUCCEEDED is the happy path; FAILED with LOGIN_REQUIRED is the
        // clean signal that auth wiring lost the ApiKey at the resolver
        // boundary — surface it explicitly so a future regression is
        // obvious in the failure message.
        if (result.status() == TaskStatus.FAILED
                && result.summary() != null
                && result.summary().contains("LOGIN_REQUIRED")) {
            throw new AssertionError("Auth resolver returned LOGIN_REQUIRED "
                + "even though authAvailable() said true. Check that the "
                + "ApiKey path didn't get swallowed: " + result.summary());
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

        // Tiny prompt — every paid backend runs ITEMS_AS_TOOLS_PREAMBLE
        // through Claude before this user description, so we don't need
        // to repeat the contract. Just ask for one trivial item.
        var taskPrompt = "Write a single Wyrdsekai item-as-tool named "
            + "`echo_item` (version 1.0.0, no capabilities, author "
            + "did:key:e2e) whose `invoke(params)` returns "
            + "{ ok: true, summary: \"echoed: \" + (params.text || \"\") }. "
            + "Output ONLY the .js file contents — no commentary.";

        var backend = liveBackend();
        var spec = TaskSpec.create("did:key:claude-sdk-e2e", "code", taskPrompt);
        var fut = backend.submitTask(spec);
        var result = fut.get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        System.out.println("[claude-sdk task3] status=" + result.status()
            + "  durationMs=" + result.durationMs()
            + "  artifacts=" + result.artifactIds().size());
        assertEquals(TaskStatus.SUCCEEDED, result.status(),
            "Expected SUCCEEDED, got " + result.status() + " — " + result.summary());

        // Claude SDK returns the response body inside result.summary;
        // the parsed SourceArtifact (if any) records the workspacePath
        // + files list, while the actual file content lands on disk.
        // For a shape check, summary is enough — it carries the JS body.
        var artifacts = backend.artifactsFor(result.taskId().toString())
            .toList();
        if (!artifacts.isEmpty() && artifacts.get(0) instanceof SourceArtifact src) {
            System.out.println("[claude-sdk task3] artifact workspacePath="
                + src.workspacePath() + "  files=" + src.files());
        }
        var body = result.summary() == null ? "" : result.summary();
        System.out.println("[claude-sdk task3] summary chars=" + body.length());
        assertTrue(body.contains("exports.manifest"),
            "Items-as-tools output must declare exports.manifest. Got:\n" + body);
        assertTrue(body.contains("function invoke") || body.contains("invoke ="),
            "Items-as-tools output must define invoke. Got:\n" + body);
    }

    // ── Task 4: full pipeline (mirrors PiCodingE2ETest.task4 / OpenHands task10) ──

    @Test @Order(4)
    void task4_full_pipeline() throws Exception {
        assumeTrue(authAvailable(),
            "Skipping full-pipeline task — no auth env wired.");

        // Same proof bar as pi/OpenHands: agent generates a research item,
        // ItemScriptExecutor runs it via LiveResearchProvider against
        // real Searxng/HTTP/voice. End-to-end items-as-tools.
        var workspace = Files.createTempDirectory("claude-sdk-task4-");
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
                + "The file MUST contain `exports.manifest = { … }` and "
                + "`function invoke(params)`.";

            var backend = liveBackend();
            var spec = new TaskSpec(
                UUID.randomUUID(),
                "did:key:claude-sdk-e2e",
                "code",
                taskPrompt,
                workspace.toString(),
                List.of(),
                0L,
                null);
            // task4 wraps the items-as-tools preamble + a research-shape
            // prompt — much heavier than task2/3. Haiku needs more wallclock
            // here than the default 3-min cap allows.
            var task4Timeout = Duration.ofMinutes(6);
            var fut = backend.submitTask(spec);
            var result = fut.get(task4Timeout.toMillis(), TimeUnit.MILLISECONDS);

            System.out.println("[claude-sdk task4] status=" + result.status()
                + "  durationMs=" + result.durationMs()
                + "  cu=" + result.cuConsumed()
                + "  workspace=" + workspace);
            assertEquals(TaskStatus.SUCCEEDED, result.status(),
                "Expected SUCCEEDED, got " + result.status() + " — " + result.summary());

            // Locate the generated artifact. Same scan strategy as pi:
            // workspace + /workspace fallback (claude's training also
            // hardcodes /workspace/ in tool-call args), plus an inline
            // fallback for when claude prose-pastes the JS instead.
            // ClaudeSdkBackend now honors spec.workspaceHint() as
            // subprocess CWD, so the file should land in `workspace`.
            // Keep /workspace as a fallback for the rare case the model
            // hardcodes that path despite the CWD.
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
                // Cap walk depth to 2 — the JVM CWD scan would otherwise
                // crawl the entire build/ tree (thousands of files) on
                // every miss.
                try (var stream = Files.walk(dir, 2)) {
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
            // Fall back to inline body in result.summary if no file found
            // (claude-sdk subprocess sometimes returns the JS inline).
            if (matched == null) {
                var inline = result.summary();
                if (inline != null && inline.contains("exports.manifest")) {
                    // Strip markdown fences if present.
                    var cleaned = inline.replaceAll("(?s)```[a-zA-Z]*\\n", "")
                                        .replaceAll("```", "");
                    var m = ItemManifestParser.parse(cleaned);
                    if (m != null) {
                        var caps = m.capabilities() == null
                            ? List.<String>of() : m.capabilities();
                        if (caps.contains("web.search")
                                && caps.contains("web.fetch")
                                && (caps.contains("llm.summarize")
                                    || caps.contains("llm.analyze"))) {
                            src = cleaned;
                            manifest = m;
                        }
                    }
                }
            }
            assertNotNull(manifest,
                "Claude SDK must produce an items-as-tools script with research "
                + "caps (file in workspace OR inline in summary). Got summary:\n"
                + result.summary());

            // Snapshot for review.
            try {
                Files.writeString(
                    Path.of("/tmp/claude_e2e_music_pulse.js"), src);
                System.out.println("[claude-sdk task4] artifact snapshot: "
                    + "/tmp/claude_e2e_music_pulse.js  bytes=" + src.length());
            } catch (Exception _) { /* ignore */ }

            // Execute against live services.
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

                System.out.println("[claude-sdk task4] === LIVE EXECUTION RESULT ("
                    + elapsed + "ms) ===");
                System.out.println("[claude-sdk task4] ok=" + invokeResult.get("ok")
                    + "  error=" + invokeResult.get("error"));
                System.out.println("[claude-sdk task4] counters: search="
                    + liveProvider.searchCalls.get()
                    + "  fetch=" + liveProvider.fetchCalls.get()
                    + "  summarize=" + liveProvider.summarizeCalls.get());
                System.out.println("[claude-sdk task4] sources: " + invokeResult.get("sources"));
                var summary = invokeResult.get("summary");
                if (summary instanceof String s) {
                    System.out.println("[claude-sdk task4] summary chars=" + s.length());
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
}
