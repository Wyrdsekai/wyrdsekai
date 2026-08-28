package org.wyrdsekai.e2e.tier2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.core.ConditionTimeoutException;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.AgentEvent;
import org.wyrdsekai.core.agent.LocalCommandRouter;
import org.wyrdsekai.core.coding.AuthMode;
import org.wyrdsekai.core.coding.AuthResolver;
import org.wyrdsekai.core.coding.BackendRegistry;
import org.wyrdsekai.core.coding.BackendTier;
import org.wyrdsekai.core.coding.BuildArtifact;
import org.wyrdsekai.core.coding.CodingArtifact;
import org.wyrdsekai.core.coding.CodingNamespaceHandler;
import org.wyrdsekai.core.coding.GooseBackend;
import org.wyrdsekai.core.coding.GooseEventAdapter;
import org.wyrdsekai.core.coding.GooseRuntimeConfig;
import org.wyrdsekai.core.coding.SourceArtifact;
import org.wyrdsekai.core.coding.TaskResult;
import org.wyrdsekai.core.coding.TaskSpec;
import org.wyrdsekai.core.coding.TaskStatus;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 2 E2E tests for the Goose coding backend — sibling of
 * {@link OpenCodeE2ETest} for the second of the Phase 2d trio
 * Goose is provider-pluggable
 * and MCP-native upstream; the adapter exercises the simpler
 * subprocess shape: {@code goose run --provider=<P> --task=<spec>
 * --format=json}.
 *
 * <p>Progressive: each {@code @Test} escalates capability. Foundation
 * (health, simple submit, adapter contract) is exercised first;
 * full pipeline (companion dispatch through workshop room → ZoneBroadcast
 * → SourceArtifact placement) lands at the end.</p>
 *
 * <p><b>Two env gates</b>:
 * <ul>
 *   <li>{@code WYRDSEKAI_E2E_BACKEND=llama|llama-server|sglang|llama-drive}
 *       — same as Ember + OpenCode. Skips the whole class when no
 *       inference backend is wired.</li>
 *   <li>{@code WYRDSEKAI_E2E_GOOSE=1} — extra gate so households without
 *       the Goose binary installed don't see the suite fail. Set by
 *       {@code scripts/training/coding/run_goose_e2e.sh} after it has
 *       provisioned the binary.</li>
 * </ul>
 *
 * <p>Run: {@code WYRDSEKAI_E2E_BACKEND=llama-server WYRDSEKAI_E2E_GOOSE=1
 * ./gradlew :e2e-test:test -PincludeTags=e2e --tests "*GooseE2ETest"}</p>
 *
 * <p><b>Local-provider posture</b> (default for tests): Goose's
 * {@code local} provider points the binary at an OpenAI-compatible
 * endpoint via {@code OPENAI_HOST} / {@code OPENAI_API_KEY} env vars
 * (set by the runner script). The {@link GooseBackend} adapter does
 * not inject those itself — they travel through process inheritance
 * from the runner shell into the JVM into the subprocess. The {@link
 * AuthResolver} returns an {@link AuthMode.OAuthSession} sentinel so
 * the adapter's "local needs no key" branch is taken without the
 * AuthMissing short-circuit firing.</p>
 *
 * <p><b>Spec-vs-impl gaps as of 2026-05-04</b>:
 * <ul>
 *   <li>{@code scripts/rooms/workshop.js} narrates OpenCode availability
 *       but has <b>no Goose branch yet</b> — tasks 5, 9, 10 are
 *       expected to fail until that script learns the Goose prose.
 *       Documented inline; not a regression in the adapter itself.</li>
 *   <li>{@code world.zoneCommand("goose.create", ...)} is not yet
 *       wired in {@code WorldApi.java}. Same situation as
 *       OpenCode — the SourceArtifact-placed-in-room leg remains a
 *       TODO in task 10.</li>
 * </ul>
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama|llama-drive")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_GOOSE", matches = "1|true|yes")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GooseE2ETest {

    /** Per-task wallclock — Goose runs 9B locally, real tasks need room. */
    private static final Duration TASK_TIMEOUT = Duration.ofMinutes(10);

    /** Companion to test — Wyrd is the default spawned by TestServerBootstrap. */
    private static final String COMPANION = "Wyrd";

    private static final String MODEL = System.getenv()
        .getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v5-q4km");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        // Same dual-inference shape as Ember + OpenCode — 9B drive on :8200
        // + 4B voice on :8201. Goose's `local` provider points at the same
        // OpenAI-compatible llama-server (OPENAI_HOST is set by the runner
        // script — the test JVM inherits it and the subprocess sees it
        // via DefaultProcessRunner's environment passthrough).
        var dual = E2eTestSupport.setupDualInference(E2eTestSupport.backendType());

        // Warm the skills backend so the first task isn't blocked on
        // model load — same shape as Ember's setUp.
        System.out.println("[GooseE2E] Warming up...");
        try {
            var warmupReq = new InferenceClient.ChatRequest(MODEL,
                List.of(new InferenceClient.ChatMessage("user", "hi")), 16, 0.0);
            dual.backends().get(0).chatCompletion(warmupReq)
                .get(Duration.ofSeconds(120).toMillis(),
                    TimeUnit.MILLISECONDS);
            System.out.println("[GooseE2E] " + MODEL + " warm.");
        } catch (Exception e) {
            System.out.println("[GooseE2E] Warmup failed (non-fatal): " + e.getMessage());
        }

        // Workshop room seed — same as OpenCode E2E. Tasks 5/9/10 dispatch
        // here. The script loader picks up scripts/rooms/workshop.js
        // automatically (see TestServerBootstrap.resolveScriptDir).
        var workshopSeed = new ZoneGuardian.RoomSeed("workshop", "The Workshop",
            "Tool racks line the walls. A large workbench dominates the center.",
            List.of(new Exit("east", "nexus", "The Nexus")),
            List.of());

        // Nexus override — make `go east` from nexus actually reach the
        // workshop. The foundation nexus has east → docks, which means
        // `sendSay("nexus", "go east")` lands in docks and workshop.js never
        // loads (its onEnter never fires). TestServerBootstrap merges seeds
        // last-wins by roomId, so this entry replaces the foundation's nexus.
        // Mirrors OpenHandsE2ETest's setup — without it tasks 5/9 silently
        // fail because the player never enters the Workshop.
        var nexusOverride = new ZoneGuardian.RoomSeed("nexus", "The Nexus",
            "A shimmering hub of connections — the heart of Wyrdsekai.",
            List.of(
                new Exit("east", "workshop", "The Workshop"),
                new Exit("south", "vault", "The Vault"),
                new Exit("west", "docks", "The Docks"),
                new Exit("north", "bridge", "The Bridge"),
                new Exit("in", "study", "The Study")),
            List.of());

        server = new TestServerBootstrap(dual.backends(),
            PortAllocator.allocate(),
            List.of(nexusOverride, workshopSeed));
        server.start();

        // Goose defaults to enabled=false in GooseRuntimeConfig, so
        // CodingBackendBootstrap.init() registers it only when
        // WYRDSEKAI_CODING_GOOSE_ENABLED=true is set. Register the live
        // backend explicitly in the global BackendRegistry so workshop.js's
        // world.codingBackendAvailable("goose") probe returns true regardless
        // of the ambient env — the room script's Goose narration branch fires
        // for tasks 5/9. Idempotent: register() ignores duplicates.
        var registry = BackendRegistry.get();
        registry.register(liveBackend());
        registry.register(new GooseEventAdapter());

        // CodingBackendBootstrap registers a CodingNamespaceHandler in
        // LocalCommandRouter for every backend it sees AT INIT TIME. We
        // register goose above AFTER CoreServices bootstrap ran, so the loop
        // may have missed it (when the env var wasn't set). Without this,
        // world.zoneCommand("goose.create", ...) from workshop.js surfaces as
        // "unknown_namespace" instead of dispatching. Idempotent via hasHandler().
        var router = LocalCommandRouter.get();
        if (!router.hasHandler(GooseBackend.NAME)) {
            router.register(GooseBackend.NAME,
                new CodingNamespaceHandler(
                    GooseBackend.NAME, registry));
        }
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
    }

    @BeforeEach
    void respawnCompanion() {
        if (server != null) server.respawnCompanion();
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    /**
     * Build a Goose backend pointed at the test's skills inference URL.
     * Uses the production binary path resolution (PATH lookup) so a missing
     * {@code goose} binary surfaces as a graceful health-check failure
     * rather than a confusing IOException mid-test.
     *
     * <p>Wired with provider {@code local} so the adapter does not require
     * a real provider API key — the runner script sets {@code OPENAI_HOST}
     * + {@code OPENAI_API_KEY=not-required} in the JVM env which the
     * subprocess inherits.</p>
     */
    private static GooseBackend liveBackend() {
        var cfg = new GooseRuntimeConfig(
            true,                                  // enabled
            "goose",                               // executablePath via PATH
            GooseRuntimeConfig.DEFAULT_PROVIDER,   // "openai" (post-reconciliation)
            GooseRuntimeConfig.DEFAULT_MODEL,      // 9B drive
            GooseRuntimeConfig.DEFAULT_BASE_URL,   // local llama-server
            // Tighter wallclock per-task in E2E so a hung run doesn't
            // eat the whole 10min budget.
            Duration.ofMinutes(8),
            List.of()
        );
        // Local provider doesn't need a real API key — but the AuthResolver
        // gate at GooseBackend:135 short-circuits to FAILED on AuthMissing.
        // Hand back an OAuthSession sentinel so the adapter's "no env
        // injection needed" branch is taken without tripping the gate.
        AuthResolver localOk = name -> new AuthMode.OAuthSession();
        return new GooseBackend(cfg, localOk);
    }

    /** Connect to the server, drain greeting, return the open ws client. */
    private TestWebSocketClient connect() throws Exception {
        var ws = TestWebSocketClient.connect(server.baseUrl());
        ws.waitForRoomState(Duration.ofSeconds(30));
        for (int i = 0; i < 5; i++) {
            try {
                var t = (i == 0) ? Duration.ofSeconds(30) : Duration.ofSeconds(5);
                ws.waitForProse(t);
            } catch (ConditionTimeoutException e) {
                break;
            }
        }
        return ws;
    }

    // ── Task 1 ──────────────────────────────────────────────────────

    @Test @Order(1)
    void task1_backend_health() throws Exception {
        // Foundation: Goose binary is reachable and the underlying
        // llama-server endpoint responds. If this fails, every subsequent
        // task that spawns the binary will fail too — surfaces as a single
        // clean failure instead of N flaky timeouts.
        var backend = liveBackend();
        var healthy = backend.healthCheck()
            .get(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
        assertTrue(healthy,
            "Goose --version probe must succeed before running E2E. "
                + "Install via the upstream curl-installer "
                + "(`curl -fsSL https://github.com/aaif-goose/goose/releases/"
                + "download/stable/download_cli.sh | bash`) or set PATH so "
                + "the binary is reachable. WYRDSEKAI_E2E_GOOSE=1 was set "
                + "but the binary is missing.");
    }

    // ── Task 2 ──────────────────────────────────────────────────────

    @Test @Order(2)
    void task2_simple_submit() throws Exception {
        // Submit a one-shot trivial task and assert the backend completes
        // it cleanly. Goose reads --task=<description> verbatim.
        var backend = liveBackend();
        var spec = TaskSpec.create("did:key:e2e", "code",
            "Write a single-line Python script that prints 'hello from goose'. "
                + "Output it as a file named hello.py in the current directory.");

        TaskResult result = backend.submitTask(spec)
            .get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertEquals(TaskStatus.SUCCEEDED, result.status(),
            "Goose must complete a trivial hello-world task. Summary: "
                + result.summary());
        assertEquals("goose", result.backend());
        assertTrue(result.artifactIds() != null && !result.artifactIds().isEmpty(),
            "Task must produce at least one artifact id.");

        var artifacts = backend.artifactsFor(result.taskId().toString()).toList();
        assertFalse(artifacts.isEmpty(), "artifactsFor must surface the produced SourceArtifact.");
        assertInstanceOf(SourceArtifact.class, artifacts.get(0));
    }

    // ── Task 3 ──────────────────────────────────────────────────────

    @Test @Order(3)
    void task3_artifact_metadata() throws Exception {
        // Same task shape as task2 but assert on the artifact metadata
        // contract. Goose doesn't always commit, so gitRef is allowed to
        // be null; backendMetadata must carry the source/backend
        // identification so downstream listeners (CodingTaskItemBridge,
        // study furnishings) can dispatch by namespace.
        var backend = liveBackend();
        var spec = TaskSpec.create("did:key:e2e", "code",
            "Create a file named scratch.txt containing the literal string OK.");

        var result = backend.submitTask(spec)
            .get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertEquals(TaskStatus.SUCCEEDED, result.status());
        var artifacts = backend.artifactsFor(result.taskId().toString()).toList();
        assertFalse(artifacts.isEmpty());

        var src = (SourceArtifact) artifacts.get(0);
        assertEquals("goose", src.backend());
        assertNotNull(src.taskId(), "taskId must be set on the artifact");
        // gitRef intentionally allowed to be null — Goose doesn't commit.
        assertNotNull(src.backendMetadata(), "backendMetadata block must exist");
        assertEquals("goose", src.backendMetadata().get("source"));
        assertEquals("goose", src.backendMetadata().get("backend"));
        assertEquals(GooseRuntimeConfig.DEFAULT_PROVIDER,
            src.backendMetadata().get("provider"),
            "backendMetadata.provider must reflect the configured Goose provider");
    }

    // ── Task 4 ──────────────────────────────────────────────────────

    @Test @Order(4)
    void task4_event_adapter() {
        // Pure-Java unit-style. GooseEventAdapter.translateEvent has to
        // produce a SourceArtifact (and optional BuildArtifact sibling)
        // whose shape CodingTaskItemBridge can place in a room. No llama
        // required — this catches regressions where the adapter contract
        // drifts away from what the bridge expects.
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "task-e2e-4");
        data.put("workspace", "/tmp/e2e-test-workspace");
        data.put("provider", GooseRuntimeConfig.DEFAULT_PROVIDER);
        data.put("status", "complete");
        var files = data.putArray("files");
        files.add("README.md");
        files.add("src/main.py");
        // Sibling build artifact — exercise the __sibling_build path.
        data.put("buildStatus", "success");
        data.put("testsPassed", 3);
        data.put("testsFailed", 0);

        var event = new AgentEvent.ZoneBroadcast(
            "goose",
            "workshop",
            new S2CMessage.ZoneResponse(0L, UUID.randomUUID().toString(),
                "goose", "ok", data, List.of()),
            Instant.now());

        var adapter = new GooseEventAdapter();
        CodingArtifact artifact = adapter.translateEvent(event);

        assertNotNull(artifact, "task_completed must translate to a non-null artifact");
        assertInstanceOf(SourceArtifact.class, artifact);
        var src = (SourceArtifact) artifact;
        assertEquals("goose", src.backend());
        assertEquals("task-e2e-4", src.taskId());
        assertEquals("/tmp/e2e-test-workspace", src.workspacePath());
        assertEquals(2, src.files().size());

        // CodingTaskItemBridge looks for __sibling_build under
        // backendMetadata. Pin that contract.
        var sibling = src.backendMetadata().get("__sibling_build");
        assertInstanceOf(BuildArtifact.class, sibling);
        var build = (BuildArtifact) sibling;
        assertEquals("goose", build.backend());
        assertEquals("success", build.status());
        assertEquals(3, build.testsPassed());

        // Negative cases — drift-protection on the namespace + event guards.
        assertNull(adapter.translateEvent(null));
        var wrongNs = new AgentEvent.ZoneBroadcast(
            "codezaiku", "workshop",
            new S2CMessage.ZoneResponse(0L, UUID.randomUUID().toString(),
                "codezaiku", "ok", data, List.of()),
            Instant.now());
        assertNull(adapter.translateEvent(wrongNs),
            "namespace mismatch must skip translation");
    }

    // ── Task 5 ──────────────────────────────────────────────────────

    @Test @Order(5)
    void task5_workshop_room_dispatch() throws Exception {
        // Companion enters Workshop, player says `code <task>`. The
        // workshop.js script consults world.codingBackendFor + then calls
        // world.zoneCommand("goose.create", ...). We assert the
        // observable side: the workshop emits its Goose-routed narration,
        // and the backend selection policy did not bail out to "no
        // backend".
        //
        // GAP: scripts/rooms/workshop.js does NOT yet have a Goose branch
        // in its onPlayerCommand handler — only OpenCode is narrated.
        // This test is expected to fail until the workshop script learns
        // about Goose. Marked TODO so the gap is visible in CI.
        try (var ws = connect()) {
            // Step into the Workshop room from the Nexus.
            ws.sendGo("nexus", "east");
            // Drain the room-arrival narration before sending the task tell.
            for (int i = 0; i < 3; i++) {
                try {
                    ws.waitForProse(Duration.ofSeconds(5));
                } catch (ConditionTimeoutException e) {
                    break;
                }
            }

            ws.sendSay("workshop", "code write a hello world script");

            // Expect the workshop room to emit the Goose-routed
            // narration — substring check rather than equality so the
            // test doesn't churn on minor wording tweaks.
            String routed = waitForAnyProse(ws, "goose", "Goose",
                "honking", "feathered", "weave");
            assertNotNull(routed, "Workshop should narrate Goose dispatch when "
                + "world.codingBackendFor returns 'goose'. "
                + "TODO(workshop.js): add a Goose branch to onPlayerCommand "
                + "so this assertion stops failing.");
            // TODO(zoneCommand): once world.zoneCommand("goose.create", ...)
            // is wired in WorldApi, assert the resulting SourceArtifact
            // appears in the room as a takeable codex item.
        }
    }

    // ── Task 6 ──────────────────────────────────────────────────────

    @Test @Order(6)
    void task6_selection_policy_picks_goose() {
        // Drive the GraalJS policy script directly — no inference, no
        // workshop, just verify that when CodeZaiku + OpenCode are
        // absent / unhealthy the policy script picks "goose" out of the
        // fallback chain.
        //
        // The detailed scenario coverage lives in
        // core/test/.../GoosePolicyTest.java; this test is the smoke
        // check that confirms the rule survives a fresh policy-script
        // load without a regression.
        var pickedWith = pickBackend(
            List.of("goose", "openhands"),
            List.of("goose", "openhands"),
            "code", "write a tiny utility function");
        assertEquals("goose", pickedWith,
            "When CodeZaiku + OpenCode are unavailable, Goose must win "
                + "the chain.");

        // Sanity: CodeZaiku wins when it IS available — pins the
        // "Goose is a tertiary fallback, not the override" intent.
        var pickedWithCodeZaiku = pickBackend(
            List.of("codezaiku", "goose"),
            List.of("codezaiku", "goose"),
            "code", "write a tiny utility function");
        assertEquals("codezaiku", pickedWithCodeZaiku,
            "CodeZaiku must keep priority when it's in the available chain.");
    }

    // ── Task 7 ──────────────────────────────────────────────────────

    @Test @Order(7)
    void task7_auth_missing_short_circuit() throws Exception {
        // Disabled config: submit must short-circuit to FAILED with a
        // disabled-style summary. No subprocess spawn, no crash, no
        // stale future. This is the "auth missing" / "binary disabled"
        // shape — the host's mailbox notification is wired separately
        // via the TaskResult LOGIN_REQUIRED status code (SPEC §4.4
        // dual-auth resolution).
        var disabled = new GooseRuntimeConfig(
            false, null, null, null, null, null, List.of());
        AuthResolver localOk = name -> new AuthMode.OAuthSession();
        var backend = new GooseBackend(disabled, localOk);

        var result = backend.submitTask(
                TaskSpec.create("did:key:e2e", "code", "anything"))
            .get(5, TimeUnit.SECONDS);

        assertEquals(TaskStatus.FAILED, result.status(),
            "Disabled backend must fail cleanly, not crash.");
        assertNotNull(result.summary());
        assertTrue(result.summary().toLowerCase().contains("disabled"),
            "Failure summary should explain 'disabled'. Got: " + result.summary());
        // The future MUST complete — sealed contract from CodingTaskBackend.
        // No artifact ids on a clean disabled-failure.
        assertTrue(result.artifactIds() == null || result.artifactIds().isEmpty());
    }

    // ── Task 8 ──────────────────────────────────────────────────────

    @Test @Order(8)
    void task8_concurrent_tasks() throws Exception {
        // Two tasks submitted in quick succession must both complete with
        // distinct task ids and isolated artifact lists. Catches regressions
        // where the in-memory artifactCache gets cross-pollinated by a
        // shared thread-local or an unbounded Map merge.
        var backend = liveBackend();
        var s1 = TaskSpec.create("did:key:e2e", "code",
            "Create file alpha.txt containing the word ALPHA.");
        var s2 = TaskSpec.create("did:key:e2e", "code",
            "Create file beta.txt containing the word BETA.");

        var f1 = backend.submitTask(s1);
        var f2 = backend.submitTask(s2);

        TaskResult r1 = f1.get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        TaskResult r2 = f2.get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertEquals(TaskStatus.SUCCEEDED, r1.status(), "task1: " + r1.summary());
        assertEquals(TaskStatus.SUCCEEDED, r2.status(), "task2: " + r2.summary());
        assertNotEquals(r1.taskId(), r2.taskId(),
            "Concurrent tasks must produce distinct task ids.");

        var a1 = backend.artifactsFor(r1.taskId().toString()).toList();
        var a2 = backend.artifactsFor(r2.taskId().toString()).toList();
        assertFalse(a1.isEmpty(), "task1 must have artifacts");
        assertFalse(a2.isEmpty(), "task2 must have artifacts");

        // Cross-pollination check — task1's artifacts must not appear under
        // task2's id and vice-versa. Compare by artifactId.
        var ids1 = a1.stream().map(CodingArtifact::artifactId).toList();
        var ids2 = a2.stream().map(CodingArtifact::artifactId).toList();
        for (var id : ids1) {
            assertFalse(ids2.contains(id),
                "Artifact " + id + " leaked from task1 into task2");
        }
    }

    // ── Task 9 ──────────────────────────────────────────────────────

    @Test @Order(9)
    void task9_room_narration() throws Exception {
        // Pure room-script test: the Workshop room narrates the Goose
        // workbench when world.codingBackendAvailable returns true for
        // "goose". No inference, no companion thinking — just walk into
        // the room and read what's narrated.
        //
        // GAP: scripts/rooms/workshop.js does NOT yet narrate Goose
        // availability. The script's onEnter() only checks for OpenCode +
        // CodeZaiku. This test is expected to fail until the script is
        // updated. Marked TODO so the gap is visible in CI.
        try (var ws = connect()) {
            ws.sendGo("nexus", "east");

            // Look for Goose-specific narration. Substring needles cover
            // the most likely phrasings the script will land on
            // ("brass goose", "Goose workbench", etc.).
            String narration = waitForAnyProse(ws, "goose", "Goose",
                "honking", "brass");
            assertNotNull(narration,
                "Workshop room must mention Goose in its enter-narration "
                    + "when world.codingBackendAvailable('goose') is true. "
                    + "If this fails, either (a) Goose isn't registered in "
                    + "BackendRegistry, or (b) workshop.js onEnter() has no "
                    + "Goose branch yet — see scripts/rooms/workshop.js. "
                    + "TODO(workshop.js): add a Goose narration branch.");
        }
    }

    // ── Task 10 ─────────────────────────────────────────────────────

    @Test @Order(10)
    void task10_full_pipeline() throws Exception {
        // Full pipeline:
        //   companion in Workshop with drives set → player says
        //   `code <multi-file task>` → workshop dispatches via
        //   world.zoneCommand → backend submits → emits ZoneBroadcast
        //   → GooseEventAdapter translates → CodingTaskItemBridge
        //   places SourceArtifact as RoomObject → companion narrates
        //   completion → `examine artifact` shows the file list.
        //
        // GAP: world.zoneCommand is not yet wired in WorldApi.java AND
        // workshop.js has no Goose branch. Until both land, this test
        // exercises everything UP TO the dispatch call — backend
        // selection, narration, task submission via the adapter
        // directly — and stubs the placement leg with a TODO.
        try (var ws = connect()) {
            ws.sendGo("nexus", "east");
            for (int i = 0; i < 3; i++) {
                try { ws.waitForProse(Duration.ofSeconds(5)); }
                catch (ConditionTimeoutException e) { break; }
            }

            // Step 1: workshop narrates Goose availability. The look-signage
            // lives in workshop.js onSay()'s "look" branch (RoomActor's
            // LookRoom does not invoke a script hook), so this must be a
            // say, not a sendLook.
            ws.sendSay("workshop", "look");
            String look = waitForAnyProse(ws, "goose", "Goose",
                "workbench", "brass");
            assertNotNull(look, "Workshop look must surface Goose signage. "
                + "TODO(workshop.js): add a Goose narration branch.");

            // Step 2: dispatch a multi-file task via the player command path.
            // Today this exercises the workshop's pickBackend() → narrate
            // path; once world.zoneCommand wires through, the same line
            // will trigger a real submission.
            ws.sendSay("workshop", "code create a small Python module with "
                + "two files: greet.py and main.py that uses it");
            String dispatched = waitForAnyProse(ws, "goose", "Goose",
                "weave", "honking");
            assertNotNull(dispatched,
                "Workshop must narrate Goose dispatch on `code` command. "
                    + "TODO(workshop.js): add a Goose dispatch branch.");

            // Step 3: direct backend smoke — confirm the same task shape
            // would round-trip through the Goose adapter once the host
            // wiring is in place. Skip this leg in the room (the
            // companion can't yet observe it) and run it through the
            // adapter so the test still surfaces a regression in the
            // submit→artifact path.
            var backend = liveBackend();
            var spec = TaskSpec.create("did:key:e2e", "code",
                "Create greet.py with a function greet(name) that returns "
                + "'hello, ' + name, and main.py that calls greet('world').");
            CompletableFuture<TaskResult> fut = backend.submitTask(spec);
            TaskResult result = fut.get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(TaskStatus.SUCCEEDED, result.status(),
                "Multi-file task must succeed end-to-end. Summary: " + result.summary());
            var artifacts = backend.artifactsFor(result.taskId().toString()).toList();
            assertFalse(artifacts.isEmpty());
            // TODO(zoneCommand): once the host hook is wired, assert the
            // SourceArtifact is placed in `workshop` as a RoomObject and
            // `examine codex-<id>` reveals the file list.
        }
    }

    // ─── Shared helpers ─────────────────────────────────────────────

    /**
     * Wait for any prose message containing one of the substring needles.
     * Returns the text of the first match, or null on timeout.
     */
    private static String waitForAnyProse(TestWebSocketClient ws, String... needles) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                var prose = ws.waitForProse(Duration.ofSeconds(10));
                var text = prose.path("text").asText("");
                var lower = text.toLowerCase();
                for (var needle : needles) {
                    if (lower.contains(needle.toLowerCase())) return text;
                }
            } catch (ConditionTimeoutException e) {
                // keep polling until deadline
            }
        }
        return null;
    }

    /**
     * Drive the GraalJS policy script directly. Mirrors the tiny harness
     * the Part D unit tests use, packed into one method for the in-suite
     * smoke check.
     */
    private static String pickBackend(List<String> available, List<String> fallback,
                                       String taskType, String description) {
        try (var jsCtx = Context.newBuilder("js")
                .allowHostAccess(HostAccess.newBuilder(
                        HostAccess.EXPLICIT)
                    .allowListAccess(true)
                    .allowMapAccess(true)
                    .allowArrayAccess(true)
                    .allowAccessAnnotatedBy(HostAccess.Export.class)
                    .build())
                .allowIO(false)
                .build()) {
            var path = Path.of("scripts", "policy", "coding-backend.js");
            if (!Files.isRegularFile(path)) {
                path = Path.of("..", "scripts", "policy", "coding-backend.js");
            }
            var src = Files.readString(path);
            jsCtx.eval(Source.newBuilder("js", src,
                "coding-backend.js").buildLiteral());

            var ctx = new LinkedHashMap<String, Object>();
            ctx.put("availableBackends", available);
            ctx.put("companionPreferences", null);
            ctx.put("householdPolicy", Map.of(
                "requireApprovalFor", List.of(),
                "autoApproveUnderCu", 0L,
                "weekdayOnlyPaidBackends", false));
            ctx.put("fallbackChain", fallback);
            ctx.put("defaultBackend", fallback.isEmpty() ? null : fallback.get(0));
            ctx.put("backendTier",
                (ProxyExecutable) args ->
                    "LOCAL_FREE");
            ctx.put("cuRemainingToday",
                (ProxyExecutable) args -> 1_000_000L);
            ctx.put("cuEstimate",
                (ProxyExecutable) args -> 0L);
            ctx.put("driveState", Map.of());

            var fn = jsCtx.getBindings("js").getMember("selectBackend");
            var raw = fn.execute("did:key:e2e", taskType, description, ctx);
            return raw == null || raw.isNull() ? null : raw.asString();
        } catch (Exception e) {
            throw new RuntimeException("policy script invocation failed", e);
        }
    }

    @SuppressWarnings("unused")
    private static InferenceBackend unusedInferenceTypeReference() {
        // Keep the import live for IDE assistance; the e2e harness uses
        // InferenceBackend through E2eTestSupport.setupDualInference.
        return null;
    }

    @SuppressWarnings("unused")
    private static BackendTier unusedTierTypeReference() {
        return BackendTier.LOCAL_FREE;
    }

    @SuppressWarnings("unused")
    private static BackendRegistry unusedRegistryReference() {
        return BackendRegistry.get();
    }
}
