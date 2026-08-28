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
import org.wyrdsekai.core.coding.OpenHandsBackend;
import org.wyrdsekai.core.coding.OpenHandsEventAdapter;
import org.wyrdsekai.core.coding.OpenHandsRuntimeConfig;
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
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemManifest;
import org.wyrdsekai.scripting.api.ItemManifestParser;
import org.wyrdsekai.scripting.api.ItemManifestValidator;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 2 E2E tests for the OpenHands coding backend — the analog of
 * {@link OpenCodeE2ETest} for the OpenHands V1 Agent Server adapter
 * (; live-verified V1 Agent Server v1.19.1
 * 2026-05-05 — see {@link OpenHandsBackend} class doc for the lifecycle).
 *
 * <p>Progressive: each {@code @Test} escalates capability. Foundation
 * (health, simple submit) is exercised first; full pipeline (companion
 * dispatch through workshop room → ZoneBroadcast → SourceArtifact
 * placement) lands at the end.</p>
 *
 * <p><b>Two env gates</b>:
 * <ul>
 *   <li>{@code WYRDSEKAI_E2E_BACKEND=llama|llama-server|sglang|llama-drive}
 *       — same as Ember/OpenCode. Skips the whole class when no inference
 *       backend is wired.</li>
 *   <li>{@code WYRDSEKAI_E2E_OPENHANDS=1} — extra gate so households
 *       without an OpenHands agent-server running don't see the suite
 *       fail. Set by {@code scripts/training/coding/run_openhands_e2e.sh}
 *       after it has booted the agent-server container and verified its
 *       {@code /health} endpoint responds.</li>
 * </ul>
 *
 * <p>Run: {@code WYRDSEKAI_E2E_BACKEND=llama-server WYRDSEKAI_E2E_OPENHANDS=1
 * ./gradlew :e2e-test:test -PincludeTags=e2e --tests "*OpenHandsE2ETest"}</p>
 *
 * <p><b>Env vars consulted</b>:
 * <ul>
 *   <li>{@code WYRDSEKAI_OPENHANDS_AGENT_SERVER_URL} — base URL of the
 *       agent-server container (default {@code http://localhost:8002},
 *       matching the {@code openhands-agent-server} service in
 *       docker-compose.e2e.yml).</li>
 *   <li>{@code WYRDSEKAI_INFERENCE_URL} — base URL of the llama-server
 *       backing the agent's LLM (default falls back to
 *       {@code E2eTestSupport.inferenceUrl()}). Forwarded via
 *       {@code agent.llm.base_url} on every create-conversation call.</li>
 *   <li>{@code WYRDSEKAI_OPENHANDS_LLM_MODEL} — model name for
 *       {@code agent.llm.model} (default
 *       {@code openai/wyrdsekai-3.5-9b-v5-q4km} — note the
 *       litellm {@code openai/} prefix routing local llama-server through
 *       the OpenAI-compatible provider).</li>
 *   <li>{@code WYRDSEKAI_MODEL} — name passed in the warmup ChatRequest
 *       (default {@code wyrdsekai-3.5-9b-v5-q4km} — matches
 *       prod). Distinct from {@code WYRDSEKAI_OPENHANDS_LLM_MODEL} since
 *       the warmup goes direct to llama-server without litellm in the
 *       path.</li>
 * </ul>
 *
 * <p><b>Spec-vs-impl gaps as of 2026-05-05</b>:
 * <ul>
 *   <li>{@code scripts/rooms/workshop.js} does not yet have an
 *       {@code openhands} narration branch — {@code dispatchCoding()}
 *       falls back to CodeZaiku with a console warning when
 *       {@code pickBackend()} returns "openhands". Tasks 5 + 9 + 10
 *       (workshop-room narration / dispatch / full pipeline) WILL
 *       therefore fail their workshop-narration assertions until the
 *       script gains an OpenHands branch (substring "openhands"
 *       case-insensitive in the enter / dispatch narration). Tracking:
 *       once the script is updated, the task-9 needles in
 *       {@link #waitForAnyProse} should match without the test changing.</li>
 *   <li>Same {@code world.zoneCommand} host-hook gap as OpenCode — task
 *       10 stubs the SourceArtifact-placed-in-room leg via direct
 *       {@link OpenHandsBackend#submitTask} until the host hook lands.</li>
 * </ul>
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND",
    matches = "sglang|llama-server|llama|llama-drive")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_OPENHANDS", matches = "1|true|yes")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpenHandsE2ETest {

    /**
     * Per-task wallclock — OpenHands runs the agent loop locally over a
     * 9B model, real tasks need room. Matches OpenCode's budget so the
     * two suites can be compared apples-to-apples.
     */
    private static final Duration TASK_TIMEOUT = Duration.ofMinutes(10);

    /** Companion to test — Wyrd is the default spawned by TestServerBootstrap. */
    private static final String COMPANION = "Wyrd";

    /**
     * Model name used in the warmup ChatRequest (direct to llama-server,
     * no litellm in the path). Matches the prod GGUF naming.
     */
    private static final String MODEL = System.getenv()
        .getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v5-q4km");

    /**
     * Model name pushed into {@code agent.llm.model} on every
     * create-conversation call. The {@code openai/} prefix is the
     * litellm convention for routing through the OpenAI-compatible
     * provider (which is what llama-server speaks). Without the prefix,
     * litellm tries to resolve the bare name against its own provider
     * registry and fails.
     */
    private static final String OPENHANDS_LLM_MODEL = System.getenv()
        .getOrDefault("WYRDSEKAI_OPENHANDS_LLM_MODEL",
            "openai/wyrdsekai-3.5-9b-v5-q4km");

    /**
     * Base URL of the agent-server container. Defaults to :8002 to match
     * the {@code openhands-agent-server} service in docker-compose.e2e.yml
     * (chosen to avoid colliding with prod openhands probes on :8001 or
     * the standard :8000 default — that's the in-container port).
     */
    private static final String AGENT_SERVER_URL = System.getenv()
        .getOrDefault("WYRDSEKAI_OPENHANDS_AGENT_SERVER_URL",
            "http://localhost:8002");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        // Same dual-inference shape as Ember/OpenCode — 9B drive on :8200
        // (or :8083 in e2e drive profile) + 4B voice on :8201. OpenHands
        // points at the drive backend for its provider (OpenAI-compatible
        // /v1/chat/completions, exposed by llama-server at /v1).
        var dual = E2eTestSupport.setupDualInference(E2eTestSupport.backendType());

        // Warm the drive backend so the first task isn't blocked on
        // model load — same shape as Ember's setUp.
        System.out.println("[OpenHandsE2E] Warming up...");
        try {
            var warmupReq = new InferenceClient.ChatRequest(MODEL,
                List.of(new InferenceClient.ChatMessage("user", "hi")), 16, 0.0);
            dual.backends().get(0).chatCompletion(warmupReq)
                .get(Duration.ofSeconds(120).toMillis(),
                    TimeUnit.MILLISECONDS);
            System.out.println("[OpenHandsE2E] " + MODEL + " warm.");
        } catch (Exception e) {
            System.out.println("[OpenHandsE2E] Warmup failed (non-fatal): "
                + e.getMessage());
        }

        // Add a Workshop room as an extra seed so tasks 5/9/10 have somewhere
        // to dispatch. The script loader picks up scripts/rooms/workshop.js
        // automatically (see TestServerBootstrap.resolveScriptDir).
        var workshopSeed = new ZoneGuardian.RoomSeed("workshop", "The Workshop",
            "Tool racks line the walls. A large workbench dominates the center.",
            List.of(new Exit("east", "nexus", "The Nexus")),
            List.of());

        // Nexus override — make `go east` from nexus actually reach the
        // workshop. The foundation nexus has east → terminal, which means
        // `sendSay("nexus", "go east")` lands in terminal and workshop.js
        // never loads (its onEnter never fires) — verified live 2026-05-06.
        // TestServerBootstrap merges seeds last-wins by roomId, so this
        // entry replaces the foundation's nexus.
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

        // OpenHands defaults to enabled=false in OpenHandsRuntimeConfig, so
        // CodingBackendBootstrap.init() skips its registration when
        // CoreServices boots. Register the live backend in the global
        // BackendRegistry explicitly so workshop.js's
        // world.codingBackendAvailable("openhands") probe returns true and
        // the room script's OpenHands narration branch fires (tasks 5/9/10).
        // The OpenCode sibling test gets this for free because OpenCode's
        // config defaults enabled=true. Idempotent: register() ignores
        // duplicates.
        var registry = BackendRegistry.get();
        registry.register(liveBackend());
        registry.register(new OpenHandsEventAdapter());

        // CodingBackendBootstrap registers a CodingNamespaceHandler in
        // LocalCommandRouter for every backend it sees AT INIT TIME — but
        // we register OpenHands above AFTER CoreServices bootstrap ran in
        // TestServerBootstrap, so the loop already missed it. Without this
        // explicit register, `world.zoneCommand("openhands.create", ...)`
        // from workshop.js would surface as "unknown_namespace" instead of
        // dispatching through the handler. Idempotent — hasHandler() check
        // means a re-run during @BeforeAll on the same JVM is safe.
        var router = LocalCommandRouter.get();
        if (!router.hasHandler(OpenHandsBackend.NAME)) {
            router.register(OpenHandsBackend.NAME,
                new CodingNamespaceHandler(
                    OpenHandsBackend.NAME, registry));
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
     * Build an OpenHands backend pointed at the test's agent-server +
     * llama-server pair. Encodes the four V1.19.1 reconciliation gotchas:
     * <ol>
     *   <li>{@code llm_model} must be present (V1's pydantic validation
     *       rejects {@code agent.llm: {}} with a 500). The
     *       {@code openai/} prefix routes through litellm's
     *       OpenAI-compatible provider.</li>
     *   <li>{@code llm_api_key} must be set when {@code llm_base_url} +
     *       {@code llm_model} are sent per-call — litellm disables the
     *       env-var fallback for per-call overrides. Local llama-server
     *       ignores the value but litellm requires <i>some</i> string;
     *       the literal {@code "not-required"} is the documented stub.</li>
     *   <li>The agent-server's {@code /health} endpoint returns the
     *       literal string {@code "OK"} with 200 OK — <b>not</b>
     *       {@code /api/health} (that returns 404 against v1.19.1).
     *       Adapter handles this internally.</li>
     *   <li>Tag keys must match {@code ^[a-z0-9]+$} — adapter uses
     *       {@code taskid}, {@code tasktype}, {@code submittedby},
     *       {@code provider}, {@code authmode}. No underscores or
     *       dashes. Tag values are unrestricted.</li>
     * </ol>
     */
    private static OpenHandsBackend liveBackend() {
        var inferenceUrl = System.getenv().getOrDefault(
            "WYRDSEKAI_INFERENCE_URL",
            E2eTestSupport.inferenceUrl(E2eTestSupport.backendType()));
        // The agent-server runs INSIDE a Docker container, so a host-side
        // `localhost` doesn't resolve to the host's llama-drive — it
        // resolves to the container itself. Swap to `host.docker.internal`
        // (Linux: requires the `--add-host=host.docker.internal:host-gateway`
        // entry in docker-compose.e2e.yml, which is already present on
        // the openhands-agent-server service). Without this rewrite the
        // SDK retries 5× then surfaces `litellm.InternalServerError:
        // Connection error` — observed live 2026-05-06.
        inferenceUrl = inferenceUrl
            .replace("://localhost:", "://host.docker.internal:")
            .replace("://127.0.0.1:", "://host.docker.internal:");
        // OpenHands wants the /v1 base URL (litellm OAI-compatible
        // shape); the inference URL we get from E2eTestSupport is the
        // host:port — append /v1 to match the OAI provider contract.
        var llmBaseUrl = inferenceUrl.endsWith("/v1") ? inferenceUrl
            : inferenceUrl + "/v1";

        var cfg = new OpenHandsRuntimeConfig(
            true,                                       // enabled
            AGENT_SERVER_URL,                           // agent_server_url
            OpenHandsRuntimeConfig.DEFAULT_DOCKER_IMAGE, // docker_image (informational)
            OpenHandsRuntimeConfig.DEFAULT_MAX_RAM_GB,
            OpenHandsRuntimeConfig.DEFAULT_MAX_DISK_GB,
            // Tighter wallclock per-task in E2E so a hung run doesn't
            // eat the whole TASK_TIMEOUT budget.
            8,                                          // max_wallclock_min
            OpenHandsRuntimeConfig.DEFAULT_PROVIDER,    // default_provider ("local")
            // 180s per-request (was 60s — task10's items-as-tools prompt
            // produces a long events page + slow agent_final_response on
            // the 9B; 60s hit a HttpTimeoutException mid-poll. 180s still
            // sits inside the 8-min wallclock cap).
            Duration.ofSeconds(180),                    // request_timeout
            llmBaseUrl,                                 // llm_base_url -> agent.llm.base_url
            OPENHANDS_LLM_MODEL,                        // llm_model -> agent.llm.model
            "not-required",                             // llm_api_key -> agent.llm.api_key (litellm stub)
            OpenHandsRuntimeConfig.DEFAULT_MAX_ITERATIONS,
            OpenHandsRuntimeConfig.DEFAULT_STUCK_DETECTION,
            OpenHandsRuntimeConfig.DEFAULT_WORKING_DIR,
            // native_tool_calling=false: small models (9B) reliably
            // mis-escape JSON tool-call args when file content has
            // apostrophes / embedded quotes. SDK's NonNativeToolCallingMixin
            // emits text-based tool calls, dodging the entire failure mode.
            false);
        return new OpenHandsBackend(cfg, oauthResolver());
    }

    /** Resolver that always returns a live OAuth session — bypasses Key Chest. */
    private static AuthResolver oauthResolver() {
        return name -> new AuthMode.OAuthSession();
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
        // Foundation: the OpenHands V1 Agent Server is reachable and its
        // /health endpoint returns 200. If this fails, every subsequent
        // task that opens a conversation will fail too — surfaces as a
        // single clean failure instead of N flaky timeouts.
        //
        // Note: agent-server health is independent of the LLM endpoint's
        // health — the agent-server can be up while llama-server is down
        // (later tasks would then fail on conversation execution_status =
        // "error"). The runner script verifies both backends before
        // setting WYRDSEKAI_E2E_OPENHANDS=1.
        var backend = liveBackend();
        var healthy = backend.healthCheck()
            .get(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
        assertTrue(healthy,
            "OpenHands /health probe must succeed before running E2E. "
                + "Boot the agent-server with `docker compose -f "
                + "docker/docker-compose.e2e.yml --profile openhands up -d "
                + "openhands-agent-server` (or via run_openhands_e2e.sh). "
                + "WYRDSEKAI_E2E_OPENHANDS=1 was set but the agent-server "
                + "at " + AGENT_SERVER_URL + " is not responding.");
    }

    // ── Task 2 ──────────────────────────────────────────────────────

    @Test @Order(2)
    void task2_simple_submit() throws Exception {
        // Submit a one-shot trivial task and assert the backend completes
        // it cleanly. The V1 lifecycle (create → run → poll events →
        // final response → delete) is exercised end-to-end here for the
        // first time.
        var backend = liveBackend();
        var spec = TaskSpec.create("did:key:e2e", "code",
            "Write a single-line Python script that prints 'hello from openhands'. "
                + "Output it as a file named hello.py in the current directory.");

        TaskResult result = backend.submitTask(spec)
            .get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertEquals(TaskStatus.SUCCEEDED, result.status(),
            "OpenHands must complete a trivial hello-world task. Summary: "
                + result.summary());
        assertEquals("openhands", result.backend());
        assertTrue(result.artifactIds() != null && !result.artifactIds().isEmpty(),
            "Task must produce at least one artifact id.");

        var artifacts = backend.artifactsFor(result.taskId().toString()).toList();
        assertFalse(artifacts.isEmpty(),
            "artifactsFor must surface the produced SourceArtifact.");
        assertInstanceOf(SourceArtifact.class, artifacts.get(0));
    }

    // ── Task 3 ──────────────────────────────────────────────────────

    @Test @Order(3)
    void task3_artifact_metadata() throws Exception {
        // Same task shape as task2 but assert on the artifact metadata
        // contract. OpenHands doesn't commit, so gitRef is allowed to be
        // null; backendMetadata must carry source/backend/agent_server_url
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
        assertEquals("openhands", src.backend());
        assertNotNull(src.taskId(), "taskId must be set on the artifact");
        // gitRef intentionally allowed to be null — OpenHands doesn't commit.
        assertNotNull(src.backendMetadata(), "backendMetadata block must exist");
        assertEquals("openhands", src.backendMetadata().get("source"));
        assertEquals("openhands", src.backendMetadata().get("backend"));
        // The reconciled adapter pins agent_server_url + provider in
        // metadata for traceability.
        assertEquals(AGENT_SERVER_URL,
            src.backendMetadata().get("agent_server_url"));
        assertEquals(OpenHandsRuntimeConfig.DEFAULT_PROVIDER,
            src.backendMetadata().get("provider"));
    }

    // ── Task 4 ──────────────────────────────────────────────────────

    @Test @Order(4)
    void task4_event_adapter() {
        // Pure-Java unit-style. OpenHandsEventAdapter.translateEvent has
        // to produce a SourceArtifact (and optional BuildArtifact sibling)
        // whose shape CodingTaskItemBridge can place in a room. No
        // agent-server or llama required — this catches regressions
        // where the adapter contract drifts away from what the bridge
        // expects.
        //
        // Uses the legacy "complete" flatten shape rather than the V1
        // ConversationStateUpdateEvent shape, since CodingTaskItemBridge
        // currently consumes the flattened payload (the V1 raw events
        // arrive on the WS-replacement polling loop and are folded into
        // the SourceArtifact by OpenHandsBackend.parseArtifacts before
        // the adapter sees them). Both shapes are accepted — see
        // OpenHandsEventAdapter.isTerminalShape().
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "task-e2e-4");
        data.put("workspace", "/tmp/e2e-test-workspace");
        data.put("status", "complete");
        data.put("agentVersion", "1.19.1");
        var files = data.putArray("files");
        files.add("README.md");
        files.add("src/main.py");
        // Sibling build artifact — exercise the __sibling_build path.
        var build = data.putObject("build");
        build.put("status", "success");
        build.put("testsPassed", 3);
        build.put("testsFailed", 0);

        var event = new AgentEvent.ZoneBroadcast(
            "openhands",
            "workshop",
            new S2CMessage.ZoneResponse(0L, UUID.randomUUID().toString(),
                "openhands", "ok", data, List.of()),
            Instant.now());

        var adapter = new OpenHandsEventAdapter();
        CodingArtifact artifact = adapter.translateEvent(event);

        assertNotNull(artifact,
            "task_completed must translate to a non-null artifact");
        assertInstanceOf(SourceArtifact.class, artifact);
        var src = (SourceArtifact) artifact;
        assertEquals("openhands", src.backend());
        assertEquals("task-e2e-4", src.taskId());
        assertEquals("/tmp/e2e-test-workspace", src.workspacePath());
        assertEquals(2, src.files().size());

        // CodingTaskItemBridge looks for __sibling_build under
        // backendMetadata. Pin that contract.
        var sibling = src.backendMetadata().get("__sibling_build");
        assertInstanceOf(BuildArtifact.class, sibling);
        var buildArtifact = (BuildArtifact) sibling;
        assertEquals("openhands", buildArtifact.backend());
        assertEquals("success", buildArtifact.status());
        assertEquals(3, buildArtifact.testsPassed());

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
        // workshop.js script consults world.codingBackendFor + then
        // narrates the dispatch. We assert the observable side: the
        // workshop emits its OpenHands-routed narration when the policy
        // picks "openhands", and the backend selection policy did not
        // bail out to "no backend".
        //
        // GAP (2026-05-05): scripts/rooms/workshop.js does NOT yet have
        // an "openhands" branch. dispatchCoding() falls back to
        // codezaiku with a console warning when pickBackend() returns
        // "openhands". This test will therefore fail its narration
        // assertion until workshop.js is updated. That update is OUT OF
        // SCOPE for the OpenHands adapter reconciliation — tracked
        // separately. Once added, the substring needles below should
        // match without changing the test.
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

            // The `explore` verb routes through workshop.js's doExplore →
            // dispatchCoding(taskType="explore"), which the policy script's
            // looksLikeExplore() heuristic uses to promote OpenHands over
            // the default fallback chain. With `code`, the policy correctly
            // picks OpenCode for a routine "hello world" task and the
            // OpenHands narration branch never fires.
            ws.sendSay("workshop", "explore the repo and write a hello world script");

            // Expect the workshop room to emit OpenHands-routed
            // narration. Substring check rather than equality so the
            // test doesn't churn on minor wording tweaks.
            String routed = waitForAnyProse(ws, "openhands", "OpenHands",
                "agent-server", "sandbox", "iteration");
            assertNotNull(routed, "Workshop should narrate OpenHands dispatch when "
                + "world.codingBackendFor returns 'openhands'. Likely fail "
                + "until scripts/rooms/workshop.js learns an OpenHands branch.");
            // TODO(zoneCommand): once world.zoneCommand("openhands.create", ...)
            // is wired in WorldApi, assert the resulting SourceArtifact
            // appears in the room as a takeable codex item.
        }
    }

    // ── Task 6 ──────────────────────────────────────────────────────

    @Test @Order(6)
    void task6_selection_policy_picks_openhands() {
        // Drive the GraalJS policy script directly — no inference, no
        // workshop, just verify that the explore-leaning heuristic in
        // scripts/policy/coding-backend.js promotes "openhands" over the
        // standard fallback chain when the task description screams
        // "explore".
        //
        // The detailed scenario coverage lives in unit tests under
        // core/test/.../coding-backend.policy.test.js (the analogous
        // Part D suite); this test is the smoke check that the
        // explore-heuristic survives a fresh policy-script load without
        // a regression.
        var pickedExplore = pickBackend(
            List.of("openhands", "opencode", "codezaiku"),
            List.of("codezaiku", "opencode", "openhands"),
            "explore", "survey the unfamiliar repo and report its layout");
        assertEquals("openhands", pickedExplore,
            "Explore-flavored tasks must promote OpenHands over the chain. "
                + "If this fails, the looksLikeExplore() heuristic in "
                + "scripts/policy/coding-backend.js drifted.");

        // Sanity: codezaiku wins the chain for non-explore work — pins
        // the "OpenHands is opt-in for explore, not the override" intent.
        var pickedNonExplore = pickBackend(
            List.of("codezaiku", "opencode", "openhands"),
            List.of("codezaiku", "opencode", "openhands"),
            "code", "write a tiny utility function");
        assertEquals("codezaiku", pickedNonExplore,
            "Non-explore tasks must keep the standard fallback order — "
                + "codezaiku wins when present.");
    }

    // ── Task 7 ──────────────────────────────────────────────────────

    @Test @Order(7)
    void task7_auth_missing_short_circuit() throws Exception {
        // Disabled config: submit must short-circuit to FAILED with a
        // disabled-style summary. No REST call, no agent-server contact,
        // no stale future. This is the "auth missing" / "agent-server
        // disabled" shape — the host's mailbox notification is wired
        // separately via the TaskResult LOGIN_REQUIRED status code (SPEC
        // §4.4 dual-auth resolution).
        var disabled = OpenHandsRuntimeConfig.defaults();  // enabled=false by default
        var backend = new OpenHandsBackend(disabled, oauthResolver());

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

        // AuthMissing path — enabled config + missing resolver → also FAILED.
        var enabled = enabledConfig();
        var missingResolver = (AuthResolver) name -> new AuthMode.AuthMissing(
            name, "wyrd setup openhands", "not configured for E2E");
        var backendMissing = new OpenHandsBackend(enabled, missingResolver);
        var resultMissing = backendMissing.submitTask(
                TaskSpec.create("did:key:e2e", "code", "anything"))
            .get(5, TimeUnit.SECONDS);
        assertEquals(TaskStatus.FAILED, resultMissing.status());
        assertTrue(resultMissing.summary().contains("LOGIN_REQUIRED"),
            "AuthMissing must surface the LOGIN_REQUIRED prefix. Got: "
                + resultMissing.summary());
    }

    // ── Task 8 ──────────────────────────────────────────────────────

    @Test @Order(8)
    void task8_concurrent_tasks() throws Exception {
        // Two tasks submitted in quick succession must both complete
        // with distinct task ids and isolated artifact lists. Catches
        // regressions where the in-memory artifactCache gets
        // cross-pollinated by a shared thread-local or an unbounded Map
        // merge. Also exercises that V1 conversation IDs are unique
        // per-create — a bug here would surface as artifact-id collision
        // even if both runs technically succeed.
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

        // Cross-pollination check — task1's artifacts must not appear
        // under task2's id and vice-versa. Compare by artifactId.
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
        // Pure room-script test: the Workshop room narrates an OpenHands
        // mention when world.codingBackendAvailable returns true for
        // "openhands". No inference, no companion thinking — just walk
        // into the room and read what's narrated.
        //
        // GAP (2026-05-05): scripts/rooms/workshop.js's onEnter() does
        // NOT yet have an OpenHands branch. The current narration only
        // mentions CodeZaiku and OpenCode. This test will therefore
        // fail until the script gains an OpenHands enter-narration
        // branch (substring "openhands" / "OpenHands" / "agent-server"
        // / "sandbox"). Tracked as out-of-scope follow-up.
        try (var ws = connect()) {
            ws.sendGo("nexus", "east");

            // Look for OpenHands-specific narration. We accept any of
            // several plausible needles so the test doesn't churn on
            // wording — the script can introduce an "agent-server room"
            // metaphor, an "iteration sandbox" metaphor, or just the
            // bare backend name, and this test will still match.
            String narration = waitForAnyProse(ws, "openhands", "OpenHands",
                "agent-server", "sandbox", "iteration", "autonomous");
            assertNotNull(narration,
                "Workshop room must mention OpenHands in its enter-narration "
                    + "when world.codingBackendAvailable('openhands') is true. "
                    + "If this fails, either (a) OpenHands isn't registered in "
                    + "BackendRegistry, or (b) the workshop.js narration was "
                    + "rewritten without an OpenHands branch — see "
                    + "scripts/rooms/workshop.js onEnter(). The 2026-05-05 "
                    + "version of workshop.js does NOT have an OpenHands branch; "
                    + "this assertion is expected to fail until that gap closes.");
        }
    }

    // ── Task 10 ─────────────────────────────────────────────────────

    @Test @Order(10)
    void task10_full_pipeline() throws Exception {
        // Full pipeline (items-as-tools, 2026-05-06):
        //   1. companion in Workshop with drives set
        //   2. submit a task via the live OpenHandsBackend (mirrors what
        //      workshop.js's world.zoneCommand("openhands.create",...)
        //      would do — exercises tools wiring + ITEMS_AS_TOOLS_PREAMBLE
        //      + workspace mount in one shot)
        //   3. agent (with file_editor + terminal + task_tracker tools)
        // writes <name>.js matching the shape
        //   4. on completion, OpenHandsEventAdapter translates to a
        //      SourceArtifact with files list pointing into the host
        //      bind-mount workspace
        //   5. assert: at least one .js file landed on the host fs and
        //      it parses as an items-as-tools manifest. This proves the
        //      tool wiring + preamble + workspace mount chain end-to-end.
        //
        // Workshop dispatch + RoomObject placement is exercised by the
        // existing 4-test RoomActorCodingItemTest unit suite (it uses
        // the real router + bridge with a fake backend). This task
        // validates the OTHER half — that the live agent actually
        // produces correctly-shaped output with the real model.
        try (var ws = connect()) {
            ws.sendGo("nexus", "east");
            for (int i = 0; i < 3; i++) {
                try { ws.waitForProse(Duration.ofSeconds(5)); }
                catch (ConditionTimeoutException e) { break; }
            }

            // Step 1: workshop narrates OpenHands availability. EXPECTED
            // TO FAIL until workshop.js gains an OpenHands branch.
            ws.sendSay("workshop", "look");
            String look = waitForAnyProse(ws, "openhands", "OpenHands",
                "agent-server", "sandbox");
            assertNotNull(look, "Workshop look must surface OpenHands signage. "
                + "Likely fail until workshop.js update.");

            // Step 2: dispatch a multi-file task via the player command
            // path. Today this exercises the workshop's pickBackend() →
            // narrate path; once world.zoneCommand wires through, the
            // same line will trigger a real submission.
            ws.sendSay("workshop", "explore the layout of this repo and "
                + "report what you find in a file called REPORT.md");
            String dispatched = waitForAnyProse(ws, "agent-server",
                "OpenHands sandbox", "iteration loop", "model begins");
            assertNotNull(dispatched,
                "Workshop must narrate OpenHands dispatch on `code`/`explore` "
                + "command. Likely fail until workshop.js update.");

            // Step 3: live items-as-tools chain — submit a task asking
            // for an item-shaped .js, then verify the agent produced a
            // file matching the shape. This is
            // what `world.zoneCommand("openhands.create",...)` from
            // workshop.js does in the full path; we exercise the same
            // backend method directly so a workshop.js outage doesn't
            // mask a backend regression.
            var backend = liveBackend();
            var taskPrompt =
                "Build a music_pulse item that, given an optional "
                + "`params.genre` (defaults to 'pop'), uses world.web.search "
                + "to find what's currently popular in that genre, picks the "
                + "top 1-3 artists from the results, uses world.web.fetch on "
                + "the most informative result page for each artist, then "
                + "uses world.llm.summarize to produce a short prose digest "
                + "covering: who the artists are, their notable recent songs, "
                + "and any pattern across them. The item MUST declare "
                + "capabilities including \"web.search\", \"web.fetch\", and "
                + "\"llm.summarize\". Return "
                + "{ ok: true, summary: <prose digest>, sources: [<urls>] }.";

            // Locate the workspace bind root.
            var workspaceMount = System.getenv()
                .getOrDefault("WYRDSEKAI_OPENHANDS_WORKSPACE_MOUNT", "");
            var hostRoot = workspaceMount.contains(":")
                ? Path.of(workspaceMount.substring(workspaceMount.indexOf(':') + 1))
                : Path.of("data", "openhands-workspace");
            assertTrue(Files.isDirectory(hostRoot),
                "Workspace mount root must be host-readable: " + hostRoot);

            // 9B agent output is stochastic on a complex prompt — sometimes
            // it produces the music_pulse research item, sometimes it
            // produces a thinner item that doesn't use search/fetch/summarize.
            // Retry up to 3 times; on each attempt scan the workspace for a
            // file whose manifest declares the research capabilities the
            // prompt asks for. Each attempt tracks files newer than its
            // start instant so we don't pick up artifacts from earlier
            // tasks (task2/task5/task9).
            ItemManifest goodManifest = null;
            Path matchedFile = null;
            String src = null;
            TaskResult lastResult = null;
            int maxAttempts = 3;
            for (int attempt = 1; attempt <= maxAttempts && goodManifest == null; attempt++) {
                long submitMillis = System.currentTimeMillis();
                var spec = TaskSpec.create("did:key:e2e", "code", taskPrompt);
                CompletableFuture<TaskResult> fut = backend.submitTask(spec);
                lastResult = fut.get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

                // Scan workspace for .js files newer than this attempt's
                // start (allow 5s slack for clock skew between host & container).
                var freshFiles = new ArrayList<Path>();
                try (var stream = Files.list(hostRoot)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".js"))
                        .forEach(p -> {
                            try {
                                long mtime = Files.getLastModifiedTime(p).toMillis();
                                if (mtime >= submitMillis - 5000L) freshFiles.add(p);
                            } catch (Exception _) { /* ignore */ }
                        });
                }

                // Among fresh files, find one declaring the research caps.
                for (var p : freshFiles) {
                    var s = Files.readString(p);
                    var m = ItemManifestParser.parse(s);
                    if (m == null) continue;
                    var v = ItemManifestValidator.validate(m);
                    if (!v.valid()) continue;
                    var caps = m.capabilities() == null ? List.<String>of() : m.capabilities();
                    boolean hasResearchCaps = caps.contains("web.search")
                        && caps.contains("web.fetch")
                        && (caps.contains("llm.summarize") || caps.contains("llm.analyze")
                            || caps.contains("llm.complete"));
                    if (hasResearchCaps) {
                        goodManifest = m;
                        matchedFile = p;
                        src = s;
                        break;
                    }
                }
            }
            assertNotNull(goodManifest,
                "After " + maxAttempts + " attempts, no .js file in workspace "
                + "declared the research capabilities (web.search + web.fetch "
                + "+ llm.summarize/analyze/complete). Last task status: "
                + (lastResult == null ? "<none>" : lastResult.status()
                    + " — " + lastResult.summary())
                + ". The 9B may be substituting a thinner item; consider "
                + "increasing maxAttempts or strengthening the prompt's "
                + "MUST-use language.");
            assertFalse(goodManifest.name().isBlank(),
                "manifest.name must be non-blank — bridge keys items by it");

            // Sanity check on the file contents themselves.
            assertTrue(src.contains("function invoke"),
                "items-as-tools script must define invoke(). File: "
                + matchedFile);
            assertTrue(src.contains("exports.manifest"),
                "items-as-tools script must declare exports.manifest. File: "
                + matchedFile);

            // ─── REAL EXECUTION: invoke the agent's item against live
            // services (Searxng for web.search, prod-style HTTP for
            // web.fetch, e2e voice backend for llm.summarize). This is
            // the *real* end-to-end proof — agent generates an item,
            // the executor runs it, and we get a non-empty digest back.
            var voiceUrl = System.getenv().getOrDefault(
                "WYRDSEKAI_E2E_VOICE_URL", "http://localhost:8201");
            var searxngUrl = System.getenv().getOrDefault(
                "WYRDSEKAI_SEARXNG_URL", "http://localhost:8888");
            var liveProvider = new LiveResearchProvider(searxngUrl, voiceUrl);

            try (var executor = new ItemScriptExecutor()) {
                var caps = ItemCapabilitySet.from(goodManifest);
                var invokeParams = new HashMap<String, Object>();
                invokeParams.put("genre", "pop");

                Map<String, Object> invokeResult = executor.execute(
                    goodManifest.name(), src, invokeParams, liveProvider, caps);

                System.out.println("[task10] === LIVE EXECUTION RESULT ===");
                System.out.println("[task10] item: " + goodManifest.name()
                    + "  file: " + matchedFile.getFileName());
                System.out.println("[task10] ok=" + invokeResult.get("ok")
                    + "  error=" + invokeResult.get("error"));
                System.out.println("[task10] counters: search="
                    + liveProvider.searchCalls.get()
                    + "  fetch=" + liveProvider.fetchCalls.get()
                    + "  summarize=" + liveProvider.summarizeCalls.get());
                var sourcesObj = invokeResult.get("sources");
                System.out.println("[task10] sources: " + sourcesObj);
                var summaryPreview = invokeResult.get("summary");
                if (summaryPreview instanceof String s) {
                    System.out.println("[task10] summary (" + s.length() + " chars):");
                    System.out.println("[task10]   " + s.replace("\n", "\n[task10]   "));
                } else {
                    System.out.println("[task10] summary: " + summaryPreview);
                }
                System.out.println("[task10] === END LIVE EXECUTION RESULT ===");

                assertNotNull(invokeResult, "executor.execute must return a result map");
                assertNull(invokeResult.get("error"),
                    "Item invocation must not error. Got: " + invokeResult);
                assertEquals(Boolean.TRUE, invokeResult.get("ok"),
                    "Item must report ok:true. Result: " + invokeResult);
                var summary = invokeResult.get("summary");
                assertNotNull(summary, "Result must include a 'summary' field");
                assertTrue(summary instanceof String && !((String) summary).isBlank(),
                    "summary must be a non-empty string. Got: " + summary);
                assertTrue(((String) summary).length() > 50,
                    "summary should be substantive (>50 chars), got "
                        + ((String) summary).length() + " chars: " + summary);

                // The provider counters prove the chain actually ran:
                // search → fetch → summarize all hit real services.
                assertTrue(liveProvider.searchCalls.get() >= 1,
                    "Item must have called world.web.search at least once");
                assertTrue(liveProvider.fetchCalls.get() >= 1,
                    "Item must have called world.web.fetch at least once");
                assertTrue(liveProvider.summarizeCalls.get() >= 1,
                    "Item must have called world.llm.summarize at least once");
            }
        }
    }

    /**
     * Live-services {@link org.wyrdsekai.scripting.api.ItemWorldApiProvider}
     * for task10's items-as-tools execution. Backs:
     * <ul>
     *   <li>{@code world.web.search} → Searxng JSON API</li>
     *   <li>{@code world.web.fetch} → raw HTTP GET, HTML stripped</li>
     *   <li>{@code world.llm.summarize} → llama-server /v1/chat/completions</li>
     * </ul>
     * Counters track how many times each surface was invoked so the test
     * can assert the chain actually fired.
     */
    static final class LiveResearchProvider
            implements ItemWorldApiProvider {
        private final String searxngUrl;
        private final String voiceUrl;
        private final HttpClient http;
        final AtomicInteger searchCalls =
            new AtomicInteger();
        final AtomicInteger fetchCalls =
            new AtomicInteger();
        final AtomicInteger summarizeCalls =
            new AtomicInteger();

        LiveResearchProvider(String searxngUrl, String voiceUrl) {
            this.searxngUrl = searxngUrl.replaceAll("/$", "");
            this.voiceUrl = voiceUrl.replaceAll("/$", "");
            this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        }

        @Override
        public List<Map<String, Object>> searchKnowledge(String query, int limit) {
            return List.of();
        }

        @Override
        public Map<String, Object> readKnowledgeChunk(String chunkId) {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> webSearch(String query, String type, int limit) {
            searchCalls.incrementAndGet();
            try {
                var encoded = URLEncoder.encode(query,
                    StandardCharsets.UTF_8);
                var uri = URI.create(searxngUrl + "/search?q="
                    + encoded + "&format=json");
                var req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "wyrdsekai-e2e/1.0")
                    .GET().build();
                var resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) return List.of();
                var json = new ObjectMapper().readTree(resp.body());
                var results = json.path("results");
                var out = new ArrayList<Map<String, Object>>();
                int n = Math.min(limit, results.size());
                for (int i = 0; i < n; i++) {
                    var r = results.get(i);
                    var m = new HashMap<String, Object>();
                    m.put("title", r.path("title").asText(""));
                    m.put("url", r.path("url").asText(""));
                    m.put("snippet", r.path("content").asText(""));
                    out.add(m);
                }
                return out;
            } catch (Exception e) {
                System.err.println("[LiveResearchProvider] webSearch failed: " + e.getMessage());
                return List.of();
            }
        }

        @Override
        public String webFetch(String url, int maxChars) {
            fetchCalls.incrementAndGet();
            try {
                var req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "wyrdsekai-e2e/1.0")
                    .GET().build();
                var resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    return "[error] HTTP " + resp.statusCode() + " for " + url;
                }
                var body = resp.body();
                var text = body.replaceAll("(?is)<script.*?</script>", " ")
                    .replaceAll("(?is)<style.*?</style>", " ")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("&amp;", "&")
                    .replaceAll("\\s+", " ")
                    .trim();
                int cap = Math.min(Math.max(maxChars, 200), 16000);
                return text.length() > cap ? text.substring(0, cap) : text;
            } catch (Exception e) {
                return "[error] " + e.getMessage();
            }
        }

        @Override
        public List<Map<String, Object>> queryOracle(String topic, String analysisType) {
            return List.of();
        }

        @Override
        public String llmSummarize(String text, String instruction) {
            summarizeCalls.incrementAndGet();
            try {
                var system = instruction != null && !instruction.isBlank()
                    ? instruction : "Summarize the key points concisely.";
                var payload = new LinkedHashMap<String, Object>();
                payload.put("model", "wyrdsekai-voice");
                payload.put("messages", List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", text == null ? "" : text)));
                payload.put("max_tokens", 400);
                payload.put("temperature", 0.4);
                var json = new ObjectMapper().writeValueAsString(payload);
                var req = HttpRequest.newBuilder(
                        URI.create(voiceUrl + "/v1/chat/completions"))
                    .timeout(Duration.ofMinutes(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
                var resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    return "[error] HTTP " + resp.statusCode() + ": " + resp.body();
                }
                var tree = new ObjectMapper().readTree(resp.body());
                return tree.path("choices").path(0)
                    .path("message").path("content").asText("");
            } catch (Exception e) {
                return "[error] " + e.getMessage();
            }
        }

        @Override
        public String llmAnalyze(String text, String prompt) {
            return llmSummarize(text, prompt);
        }

        @Override
        public void agentSpeak(String text) { /* no-op for tests */ }

        @Override
        public void agentRemember(String content) { /* no-op for tests */ }

        @Override
        public void agentTell(String target, String message) { /* no-op for tests */ }

        @Override
        public List<Map<String, Object>> inventoryList() { return List.of(); }

        @Override
        public Map<String, Object> inventoryUse(String itemId,
                                                  Map<String, Object> params, int depth) {
            return Map.of("error", "inventory.use not wired in test provider");
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
     * Build an enabled-defaults OpenHandsRuntimeConfig pointed at the
     * test's agent-server URL but with no LLM override (so AuthMissing
     * paths short-circuit before any agent.llm validation).
     */
    private static OpenHandsRuntimeConfig enabledConfig() {
        var d = OpenHandsRuntimeConfig.defaults();
        return new OpenHandsRuntimeConfig(
            true, AGENT_SERVER_URL, d.dockerImage(),
            d.maxRamGb(), d.maxDiskGb(), d.maxWallclockMin(),
            d.defaultProvider(), d.requestTimeout(),
            d.llmBaseUrl(), d.llmModel(), d.llmApiKey(),
            d.maxIterations(), d.stuckDetection(), d.defaultWorkingDir(),
            d.nativeToolCalling());
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
                    "LOCAL_HEAVY");
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
        return BackendTier.LOCAL_HEAVY;
    }

    @SuppressWarnings("unused")
    private static BackendRegistry unusedRegistryReference() {
        return BackendRegistry.get();
    }
}
