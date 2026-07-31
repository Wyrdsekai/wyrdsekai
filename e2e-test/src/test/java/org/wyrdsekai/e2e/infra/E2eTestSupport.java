package org.wyrdsekai.e2e.infra;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assumptions;
import org.opentest4j.TestAbortedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.inference.ApiProvider;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared test support for E2E tiers 1-5.
 * Handles backend detection, external server discovery, and inference
 * backend creation — so individual test classes don't repeat this logic.
 *
 * <p>Backend selection via {@code WYRDSEKAI_E2E_BACKEND} env var:
 * {@code sglang} (default), {@code vllm}, {@code llama-server}, {@code claude}.
 *
 * <p>If a server is already running on the expected port (e.g., started
 * by e2e-test.sh), tests connect to it directly. Otherwise they attempt
 * to start their own instance.
 */
public final class E2eTestSupport {

    private static final Logger log = LoggerFactory.getLogger(E2eTestSupport.class);

    /**
     * Shared SGLang fixture — singleton across all test classes in the JVM.
     * Starting an SGLang container involves model loading which can take minutes,
     * so we keep it running and clean up via JVM shutdown hook.
     */
    private static final AtomicReference<InferenceServerFixture> sharedFixture =
        new AtomicReference<>();
    private static volatile boolean shutdownHookRegistered = false;

    private E2eTestSupport() {}

    /**
     * Release the shared inference fixture (if any) to free GPU memory.
     * Called by SharedLlamaPool before starting llama-server containers,
     * since SGLang and llama-server can't coexist on the same GPU.
     */
    public static void releaseSharedFixture() {
        var fixture = sharedFixture.getAndSet(null);
        if (fixture != null && fixture.isRunning()) {
            log.info("Releasing shared inference fixture to free GPU memory");
            fixture.stop();
        }
    }

    /** Currently configured backend type. */
    public static String backendType() {
        return System.getenv().getOrDefault("WYRDSEKAI_E2E_BACKEND", "sglang");
    }

    /** Default URL for the configured backend. */
    public static String inferenceUrl() {
        return inferenceUrl(backendType());
    }

    /**
     * Default URL for a given backend type.
     * Override with {@code WYRDSEKAI_INFERENCE_URL} env var to point at
     * a remote machine (e.g., vLLM running on a different GPU server).
     */
    public static String inferenceUrl(String backend) {
        var override = System.getenv("WYRDSEKAI_INFERENCE_URL");
        if (override != null && !override.isBlank()) {
            return override;
        }
        // Per-backend defaults — honor WYRDSEKAI_E2E_<SVC>_PORT env so the
        // harness can shift host ports to coexist with a live wyrdsekai mesh
        // on the same host. See DockerInfraExtension.envPort for details.
        return switch (backend) {
            case "sglang" -> DockerInfraExtension.sglangUrl();
            case "vllm" -> DockerInfraExtension.vllmUrl();
            case "llama-server", "llama" -> DockerInfraExtension.llamaServerUrl();
            // llama-drive is the dedicated E2E 9B drive-trained backend.
            // Separate port from llama-server so both can run concurrently
            // and so tests that explicitly need drive-trained behaviour
            // aren't silently served by a base model. See docker-compose.e2e.yml
            // service llama-drive (profile "drive").
            case "llama-drive", "drive" -> DockerInfraExtension.llamaDriveUrl();
            // (single-MLX): mlx_lm.server on
            // :8201 only — voice doubles as skills (small-model best-effort)
            // unless WYRDSEKAI_INFERENCE_URL points cross-zone to a 9B drive.
            // Dual-MLX (2026-05-28+): :8200 hosts the 9B drive, :8201 hosts
            // 4B voice. Auto-detect: if a local drive is up, prefer it for
            // skills; otherwise fall back to the voice-only :8201 default.
            case "mlx" -> isHealthy("http://localhost:8200")
                    ? "http://localhost:8200"
                    : "http://localhost:8201";
            default -> DockerInfraExtension.sglangUrl();
        };
    }

    /** Check if an external inference server is already healthy. */
    public static boolean isExternalServerHealthy() {
        return isHealthy(inferenceUrl());
    }

    /**
     * Check if a URL responds to GET /health with 200. mlx_lm.server (Phase 6
     * macOS voice runtime) has no /health endpoint — fall back to
     * /v1/models which both servers expose.
     */
    public static boolean isHealthy(String baseUrl) {
        var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
        for (var path : new String[] { "/health", "/v1/models" }) {
            try {
                var req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(2)).GET().build();
                var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) return true;
            } catch (Exception ignored) {
                // Try next probe path
            }
        }
        return false;
    }

    /**
     * Combined health + ctx-size readiness check. Throws {@link IllegalStateException}
     * if the inference backend isn't usable for E2E, with a message that tells
     * the test runner how to fix it. Use this in {@code @BeforeAll} instead of
     * calling {@code isHealthy} + {@code validateContextSize} separately.
     *
     * <p>On failure, the test class will abort its @BeforeAll with the
     * exception visible — rather than every individual test failing later
     * with cryptic in-flight inference errors.</p>
     */
    public static void assumeBackendReady(String backendType, String baseUrl) {
        if (!isHealthy(baseUrl)) {
            throw new TestAbortedException(
                backendType + " not running at " + baseUrl);
        }
        validateContextSize(baseUrl);
    }

    /**
     * Try to bring up a llama.cpp backend via {@code docker-compose.e2e.yml}.
     * Returns {@code true} when the URL ends up healthy. Use this from
     * {@code @BeforeAll} so a stale-environment run (containers stopped, GPU
     * unloaded) auto-recovers instead of silently SKIPping every test —
     * historically the SKIP-with-no-message symptom that made a 14/14 SKIPPED
     * matrix look like a JUnit-level filter bug. Mirrors the auto-start in
     * {@code TestServerBootstrap.ensureInferenceBackend}; callers that don't
     * use {@code TestServerBootstrap} (e.g. {@link #setupDualInference}) need
     * this so they can launch their own infra.
     *
     * <p>{@code maxWaitSecs} sizes the cold-start budget — 4B voice models
     * load in ~30-60s, 9B drive in ~90-180s, so use 90 / 180 respectively.</p>
     */
    public static boolean tryStartLlamaCppService(String url, String profile,
                                                  String service, int maxWaitSecs) {
        var isLocal = url.contains("localhost") || url.contains("127.0.0.1");
        if (!isLocal) {
            log.warn("Backend at {} is remote — cannot auto-start via docker-compose. "
                + "Either bring it up manually on the host serving that URL, or set "
                + "WYRDSEKAI_INFERENCE_URL=http://localhost:<port> to use local infra.", url);
            return false;
        }
        try {
            var compose = Path.of("docker", "docker-compose.e2e.yml");
            if (!compose.toFile().exists()) {
                compose = Path.of("../docker", "docker-compose.e2e.yml");
            }
            if (!compose.toFile().exists()) {
                log.warn("docker-compose.e2e.yml not found — cannot auto-start {}/{}",
                    profile, service);
                return false;
            }
            log.info("Auto-starting {}/{} via docker-compose (cold-start budget {}s)",
                profile, service, maxWaitSecs);
            var proc = new ProcessBuilder("docker", "compose", "-f",
                compose.toAbsolutePath().toString(),
                "--profile", profile, "up", "-d", service)
                .redirectErrorStream(true).start();
            proc.waitFor(60, TimeUnit.SECONDS);
            int iters = Math.max(1, maxWaitSecs / 2);
            for (int i = 0; i < iters; i++) {
                Thread.sleep(2000);
                if (isHealthy(url)) {
                    log.info("{}/{} healthy after {}s", profile, service, (i + 1) * 2);
                    return true;
                }
            }
            log.warn("{}/{} did not become healthy within {}s", profile, service, maxWaitSecs);
            return false;
        } catch (Exception e) {
            log.warn("Failed to auto-start {}/{}: {}", profile, service, e.getMessage());
            return false;
        }
    }

    /**
     * Minimum context size required for E2E tests. Memory + long-session tests
     * accumulate ~5K+ tokens of state across ~6 tests; 8K is the floor,
     * 16K recommended. Tests that blow past this were showing up as
     * {@code inference_fail} fallbacks ("*shimmers uncertainly…*") in
     * {@code fullSleepCycleMemoryPipeline} and similar, masquerading as
     * model-behavior failures when the actual cause was a 4K backend config.
     */
    public static final int REQUIRED_CTX_SIZE = 8192;

    /**
     * Validate that the inference backend has enough context window for the
     * E2E suite to run without hitting HTTP 400 overflows mid-test. Fails
     * fast at {@code @BeforeAll} time with an actionable error pointing at
     * the container restart command, rather than letting individual tests
     * fail with cryptic fallback prose deep in a 20-minute suite run.
     *
     * <p>Queries {@code /props.default_generation_settings.n_ctx} (llama-server
     * runtime config, not the model's trained max). Only applies to
     * llama-server backends; SGLang has different introspection.</p>
     *
     * @param baseUrl the inference backend URL
     * @throws IllegalStateException if ctx is too small or unreachable for llama-server backends
     */
    public static void validateContextSize(String baseUrl) {
        var backend = System.getenv().getOrDefault("WYRDSEKAI_E2E_BACKEND", "");
        // llama-server, llama (alias), and llama-drive (9B dedicated) all run
        // llama.cpp and expose /props. SGLang/vLLM have different
        // introspection endpoints — skip validation there.
        if (!"llama-server".equals(backend) && !"llama".equals(backend)
                && !"llama-drive".equals(backend) && !"drive".equals(backend)) {
            return;
        }
        try {
            var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build();
            var req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/props"))
                .timeout(Duration.ofSeconds(3)).GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException(
                    "llama-server at " + baseUrl + " does not expose /props (HTTP "
                        + resp.statusCode() + ") — cannot validate context size. "
                        + "Update the container image or disable ctx validation.");
            }
            var tree = Json.mapper().readTree(resp.body());
            var nCtx = tree.path("default_generation_settings").path("n_ctx").asInt(0);
            if (nCtx == 0) {
                // Field missing — llama-server version may not expose it. Warn and proceed.
                System.err.println("[E2E] warn: could not read n_ctx from " + baseUrl
                    + "/props — skipping validation");
                return;
            }
            if (nCtx < REQUIRED_CTX_SIZE) {
                // If we're pointing at a localhost instance of the dedicated E2E
                // drive service, the preferred fix is to recreate via compose
                // (so config drift is fixed at source). For remote/shared
                // containers the operator has to restart manually.
                var isLocal = baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1");
                String fixHint;
                if (isLocal) {
                    fixHint = "Fix: recreate the test inference container via compose.\n"
                        + "  docker compose -f docker/docker-compose.e2e.yml --profile drive down llama-drive && \\\n"
                        + "    LLAMA_DRIVE_CTX_SIZE=16384 docker compose -f docker/docker-compose.e2e.yml \\\n"
                        + "      --profile drive up -d llama-drive\n"
                        + "If you're on the `llama` profile (4B laptop model) instead, substitute\n"
                        + "`llama-server` for `llama-drive` and `LLAMA_CTX_SIZE` for `LLAMA_DRIVE_CTX_SIZE`.";
                } else {
                    fixHint = "Fix: the remote inference backend has a too-small context window.\n"
                        + "Restart it with --ctx-size 16384 (or larger). Example for a shared\n"
                        + "9B-drive container on a remote host:\n"
                        + "  docker stop wyrdsekai-9b-drive && docker rm wyrdsekai-9b-drive && \\\n"
                        + "    docker run -d --name wyrdsekai-9b-drive --gpus all \\\n"
                        + "    -v /home/you/src/wyrdsekai/data/models:/models \\\n"
                        + "    -p 8200:8080 ghcr.io/ggml-org/llama.cpp:server-cuda \\\n"
                        + "    --model /models/wyrdsekai-3.5-9b-v5-q4km.gguf --host 0.0.0.0 --port 8080 \\\n"
                        + "    -ngl 99 --ctx-size 16384 --jinja --flash-attn on \\\n"
                        + "    --temp 0.7 --top-p 0.8 --repeat-penalty 1.05 --reasoning off -n 512\n"
                        + "Better long-term: migrate to the dedicated `drive` compose profile\n"
                        + "(docker/docker-compose.e2e.yml service llama-drive) so tests own their infra.";
                }
                throw new IllegalStateException(
                    "Inference backend at " + baseUrl + " has --ctx-size " + nCtx
                        + ", which is below the E2E minimum of " + REQUIRED_CTX_SIZE + ".\n"
                        + "Memory and long-session tests will fail with \"*shimmers uncertainly…*\" fallback\n"
                        + "once accumulated state exceeds " + nCtx + " tokens.\n\n"
                        + fixHint);
            }
            System.out.println("[E2E] Inference backend ctx-size OK: " + nCtx + " (≥ " + REQUIRED_CTX_SIZE + ")");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // Network errors etc. — don't block tests on validation failure; individual
            // test setup still calls isHealthy() which is the hard gate.
            System.err.println("[E2E] warn: ctx-size validation could not reach " + baseUrl
                + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
    }

    /**
     * Create an InferenceClient with the correct ApiProvider for the backend type.
     * SGLang/vLLM get chat_template_kwargs for thinking control via the OpenAI provider.
     */
    public static InferenceClient createClient(String backendType, String url, Duration timeout) {
        var provider = new ApiProvider.OpenAI(backendType);
        return new InferenceClient(url, null, timeout, provider);
    }

    /**
     * Create an InferenceBackend connected to the external inference server.
     * Assumes server is already running on the default port.
     */
    public static InferenceBackend externalBackend(String name, int priority) {
        var type = backendType();
        var client = createClient(type, inferenceUrl(type), Duration.ofSeconds(120));
        return createBackend(type, name, client, priority);
    }

    /**
     * Create an InferenceBackend for a given URL and type.
     */
    public static InferenceBackend createBackend(String type, String name,
                                                  InferenceClient client, int priority) {
        return switch (type) {
            case "sglang" -> new InferenceBackend.SGLang(name, client, priority, List.of());
            case "vllm" -> new InferenceBackend.VLLM(name, client, priority, List.of());
            default -> new InferenceBackend.LlamaServer(name, client, priority, List.of(), null);
        };
    }

    /**
     * Assume an external inference server is available.
     * If not, skip the test with a useful message.
     */
    public static void assumeExternalInferenceAvailable() {
        var type = backendType();
        var url = inferenceUrl(type);
        Assumptions.assumeTrue(isHealthy(url),
            "No " + type + " inference server at " + url +
            " — start via: ./e2e-test.sh --engine " + type);
    }

    /**
     * Set up inference for a test — prefer external server, fallback to starting one.
     * Returns the backend. Docker-based fixtures (SGLang, etc.) are shared across
     * test classes to avoid repeated model loading (which can take minutes).
     * The shared fixture is cleaned up via a JVM shutdown hook.
     */
    public static SetupResult setupInference(String name) throws Exception {
        var type = backendType();
        var url = inferenceUrl(type);

        if (isHealthy(url)) {
            log.info("Using external {} at {}", type, url);
            var backend = externalBackend(name, 10);
            return new SetupResult(backend, null, false);
        }

        // For Docker-based backends, reuse a shared fixture across test classes
        var existing = sharedFixture.get();
        if (existing != null && existing.isRunning()) {
            log.info("Reusing shared {} fixture for {}", type, name);
            var backend = existing.createBackend(name, 10);
            return new SetupResult(backend, existing, true);
        }

        // Start a new fixture
        InferenceServerFixture fixture = switch (type) {
            case "llama-server", "llama" -> {
                LlamaServerFixture.assumeAvailable();
                LlamaServerFixture.assumeModelAvailable(NodeProfile.LAPTOP);
                yield new LlamaServerFixture(
                    ModelManager.modelPath(NodeProfile.LAPTOP),
                    PortAllocator.allocate(), 4096);
            }
            case "sglang" -> {
                SGLangServerFixture.assumeAvailable();
                yield new SGLangServerFixture("Qwen/Qwen3-8B", 8000);
            }
            default -> {
                Assumptions.assumeTrue(false,
                    "Backend '" + type + "' requires external server " +
                    "(start via: ./e2e-test.sh --engine " + type + ")");
                yield null; // unreachable
            }
        };

        fixture.start();
        sharedFixture.set(fixture);
        registerShutdownHook();

        var backend = fixture.createBackend(name, 10);
        return new SetupResult(backend, fixture, true);
    }

    private static synchronized void registerShutdownHook() {
        if (!shutdownHookRegistered) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                var fixture = sharedFixture.getAndSet(null);
                if (fixture != null) {
                    log.info("Stopping shared inference fixture (JVM shutdown)");
                    fixture.stop();
                }
            }, "e2e-inference-cleanup"));
            shutdownHookRegistered = true;
        }
    }

    /**
     * Result of setupInference — backend + optional fixture.
     * Shared fixtures are NOT stopped by stopFixture() — they are cleaned up
     * via JVM shutdown hook to avoid repeated model loading between test classes.
     */
    public record SetupResult(InferenceBackend backend, InferenceServerFixture fixture,
                               boolean shared) {
        public void stopFixture() {
            if (fixture != null && !shared) {
                fixture.stop();
            }
        }
    }

    /**
     * Set up dual-inference for a test that asserts on prose (memory recall,
     * Ember progressive tasks, anything voice-sensitive).
     *
     * <p>Probes two backends:
     * <ul>
     *   <li><b>Skills</b> — the main URL from {@link #inferenceUrl(String)}.
     *       Priority 5 (preferred default). Used for ReAct, classifier,
     *       cap:reasoning. Always required; test is skipped if unhealthy.</li>
     *   <li><b>Voice</b> — {@code WYRDSEKAI_E2E_VOICE_URL} or
     *       {@code http://localhost:<WYRDSEKAI_E2E_VOICE_PORT|8201>}.
     *       Priority 15. Used for cap:quick voice-pass post-processing.
     *       Optional — falls back to single-backend with a WARN log if
     *       unhealthy, since fresh-host CI may not have a voice server.</li>
     * </ul>
     *
     * <p>Model names are discovered via {@code /v1/models} so
     * {@link org.wyrdsekai.core.inference.CapabilityRegistry#fromBackends}
     * can size-route capabilities correctly (9B → reasoning, ≤4B → quick).
     */
    public static DualSetupResult setupDualInference(String name) throws Exception {
        var type = backendType();
        var skillsUrl = inferenceUrl(type);
        var voiceUrl = resolveVoiceUrl();

        // Auto-start path. If the skills backend isn't healthy, bring up the
        // dual-inference compose profile (drive) which provisions both
        // llama-drive (skills @ 8083) and llama-voice (voice @ 8201) in one
        // shot — they share the GPU device and start together. Voice gets a
        // dedicated retry below in case only it is missing (skills already up
        // on a non-default URL, or operator brought up just llama-drive).
        if (!isHealthy(skillsUrl)) {
            log.warn("Skills backend down at {} — auto-starting docker-compose dual-inference",
                skillsUrl);
            // 9B cold-start can take ~3min; the call also starts llama-voice
            // in the same `up` invocation so we don't pay two cold starts.
            tryStartLlamaCppService(skillsUrl, "drive", "llama-drive", 180);
            if (voiceUrl != null && !isHealthy(voiceUrl)) {
                tryStartLlamaCppService(voiceUrl, "drive", "llama-voice", 90);
            }
        }

        // After auto-start, skills MUST be healthy — fail loudly (not skip).
        // Silently skipping was the historical landmine: a 14/14 SKIPPED
        // matrix run looked like a JUnit filter bug instead of "your inference
        // is down." Use fail() so the operator gets a stack trace + recovery
        // hint rather than `<skipped/>` with no message in the JUnit XML.
        if (!isHealthy(skillsUrl)) {
            throw new IllegalStateException(
                "Skills backend not healthy at " + skillsUrl + " after auto-start attempt. "
                + "Manual recovery: \n"
                + "  LLAMA_DRIVE_MODEL=<your-9b.gguf> LLAMA_VOICE_MODEL=<your-4b.gguf> \\\n"
                + "  WYRDSEKAI_DATA=$(pwd)/data \\\n"
                + "    docker compose -f docker/docker-compose.e2e.yml \\\n"
                + "      --profile drive up -d llama-drive llama-voice\n"
                + "Or override the URL: WYRDSEKAI_INFERENCE_URL=http://<host>:<port>");
        }

        var skillsClient = createClient(type, skillsUrl, Duration.ofSeconds(120));
        var skillsModels = discoverModels(skillsUrl);
        var skills = createBackendWithModels(type, name + "-skills", skillsClient, 5, skillsModels);

        var backends = new ArrayList<InferenceBackend>();
        backends.add(skills);

        if (voiceUrl != null && isHealthy(voiceUrl)) {
            var voiceClient = createClient(type, voiceUrl, Duration.ofSeconds(120));
            var voiceModels = discoverModels(voiceUrl);
            backends.add(createBackendWithModels(
                type, name + "-voice", voiceClient, 15, voiceModels));
            log.info("Dual-inference: skills @ {} (models={}) + voice @ {} (models={})",
                skillsUrl, skillsModels, voiceUrl, voiceModels);
        } else {
            log.warn("Dual-inference: voice backend not healthy at {} — falling back to "
                + "single-backend (skills only). Tests asserting on prose may flake; set "
                + "WYRDSEKAI_E2E_VOICE_URL or run a 4B llama-server on :8201.",
                voiceUrl == null ? "<unset>" : voiceUrl);
        }
        return new DualSetupResult(List.copyOf(backends), null, false);
    }

    private static String resolveVoiceUrl() {
        var url = System.getenv("WYRDSEKAI_E2E_VOICE_URL");
        if (url != null && !url.isBlank()) return url;
        var port = System.getenv("WYRDSEKAI_E2E_VOICE_PORT");
        if (port != null && !port.isBlank()) return "http://localhost:" + port;
        return "http://localhost:8201";
    }

    /**
     * Discover model names via {@code /v1/models}. Returns an empty list if
     * the endpoint is missing or unreadable; CapabilityRegistry treats empty
     * as "default capability only" rather than letting size-heuristics fire,
     * so it's important that voice-sensitive callers actually populate this.
     */
    private static List<String> discoverModels(String baseUrl) {
        try {
            var conn = (HttpURLConnection)
                URI.create(baseUrl + "/v1/models").toURL().openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(2000);
            if (conn.getResponseCode() != 200) return List.of();
            var body = new String(conn.getInputStream().readAllBytes());
            var mapper = new ObjectMapper();
            var root = mapper.readTree(body);
            var data = root.get("data");
            if (data == null || !data.isArray()) return List.of();
            var out = new ArrayList<String>();
            for (var m : data) {
                var id = m.has("id") ? m.get("id").asText() : null;
                if (id != null && !id.isBlank()) out.add(id);
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static InferenceBackend createBackendWithModels(
            String type, String name, InferenceClient client, int priority, List<String> models) {
        return switch (type) {
            case "sglang" -> new InferenceBackend.SGLang(name, client, priority, models);
            case "vllm" -> new InferenceBackend.VLLM(name, client, priority, models);
            case "mlx" -> new InferenceBackend.Mlx(name, client, priority, models,
                    "mlx://" + client.getBaseUrl().replaceFirst("^https?://", ""));
            default -> new InferenceBackend.LlamaServer(name, client, priority, models, null);
        };
    }

    /**
     * Result of setupDualInference. Pass {@link #backends()} straight into
     * {@link TestServerBootstrap#TestServerBootstrap(List)}; the bootstrap
     * derives a CapabilityRegistry from the list so cap:quick routes to 4B.
     */
    public record DualSetupResult(List<InferenceBackend> backends,
                                   InferenceServerFixture fixture,
                                   boolean shared) {
        public void stopFixture() {
            if (fixture != null && !shared) {
                fixture.stop();
            }
        }
    }
}
