package org.wyrdsekai.core.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Parses inference configuration from HOCON into InferenceBackend instances.
 * Supports both the new backends[] list and legacy local/cloud config.
 */
public record InferenceConfig(
        String defaultModel,
        List<InferenceBackend> backends,
        Duration healthCheckInterval
) {
    private static final Logger log = LoggerFactory.getLogger(InferenceConfig.class);

    /**
     * Parse inference config from HOCON.
     * Tries backends[] first, falls back to legacy local/cloud, then auto-detects Claude CLI.
     */
    public static InferenceConfig fromConfig(Config config) {
        var defaultModel = config.getString("default-model");
        var healthInterval = config.getDuration("health-check-interval");
        var backends = new ArrayList<InferenceBackend>();

        // Parse backends list. One malformed entry (bad env-var substitution,
        // wrong field type, etc.) MUST NOT abort the whole pipeline — log, skip,
        // let auto-detect pick up what we can.
        if (config.hasPath("backends")) {
            var backendList = config.getList("backends");
            for (var entry : backendList) {
                if (!(entry instanceof ConfigObject obj)) continue;
                try {
                    var bc = obj.toConfig();
                    var type = bc.getString("type");
                    var name = bc.getString("name");
                    boolean enabled = parseBooleanLenient(bc, "enabled");

                    // Auto-enable cloud backends when API key is set
                    if (!enabled && "cloud".equals(type)) {
                        var apiKey = bc.hasPath("api-key") ? bc.getString("api-key") : "";
                        if (!apiKey.isBlank()) {
                            enabled = true;
                            log.info("Auto-enabling '{}' backend (API key detected)", name);
                        }
                    }

                    if (!enabled) continue;

                    var backend = createBackend(bc);
                    if (backend != null) {
                        backends.add(backend);
                    }
                } catch (Exception e) {
                    var name = obj.toConfig().hasPath("name")
                            ? obj.toConfig().getString("name") : "<unknown>";
                    log.warn("Skipping malformed backend entry '{}': {}", name, e.getMessage());
                }
            }
        }

        // Fallback: if no backends from the list, try legacy config
        if (backends.isEmpty()) {
            backends.addAll(legacyBackends(config));
        }

        // Auto-detect inference servers on default ports if no backends enabled
        if (backends.isEmpty()) {
            // llama-server on 8200/8201 (dual-inference) or 11525 (docker bundled).
            // Registers ALL healthy probes so a dual-inference deploy gets both
            // skills (:8200) and voice (:8201) backends at once.
            backends.addAll(autoDetectLlamaServers());
        }
        if (backends.isEmpty()) {
            // SGLang on 8000 (preferred over Ollama — native tool parsing, no empty responses)
            autoDetectSGLang().ifPresent(backends::add);
        }
        if (backends.isEmpty()) {
            // Ollama on 11434 (fallback — works everywhere)
            autoDetectOllama().ifPresent(backends::add);
        }

        // Auto-detect Claude CLI — configurable via WYRDSEKAI_CLAUDE_CLI_ENABLED
        // Registers alongside other backends (not just as fallback).
        // Higher priority number = lower preference (used for deep/cloud capability).
        boolean claudeCliEnabled = config.hasPath("wyrdsekai.inference.claude-cli.enabled")
            ? config.getBoolean("wyrdsekai.inference.claude-cli.enabled")
            : backends.isEmpty(); // default: only if no other backends
        if (claudeCliEnabled) {
            ClaudeCliInference.autoDetect().ifPresent(cli -> {
                backends.add(new InferenceBackend.ClaudeCli(
                    "claude-auto", cli, 50, cli.availableModels()));
                log.info("Auto-detected Claude CLI (OAuth) — subscription: {}",
                    cli.getSubscriptionType());
            });
        }

        // Sort by priority (lower = preferred)
        backends.sort(Comparator.comparingInt(InferenceBackend::priority));

        return new InferenceConfig(defaultModel, List.copyOf(backends), healthInterval);
    }

    /**
     * Minimum PER-SLOT context window for a managed llama-server.
     *
     * <p>Tied to {@code PromptAssembler.MIN_BACKEND_SAFE_PROMPT_TOKENS} (7500):
     * the assembler may hand a backend a prompt that large, so any backend with
     * a smaller window is guaranteed to 400 on ordinary turns. 8192 is the
     * smallest size that holds it with headroom for the response and for the
     * chars/4 token estimate's error.</p>
     *
     * <p>If you raise the assembler's ceiling, raise this with it — they are two
     * halves of one constraint, and they were allowed to drift apart once.</p>
     */
    static final int MIN_PER_SLOT_CONTEXT = 8192;

    /** Inference timeout from env, default 120s. */
    private static final Duration INFERENCE_TIMEOUT = Duration.ofSeconds(
        Long.parseLong(System.getenv().getOrDefault("WYRDSEKAI_INFERENCE_TIMEOUT", "120")));

    /**
     * Parse a boolean that may have been env-substituted as a string.
     * HOCON's {@code getBoolean} throws WrongType if the value is a string like
     * {@code "1"} or {@code "true"} (e.g. when set via {@code WYRDSEKAI_*=1}
     * substitution), which would otherwise crash the whole backend-list parse.
     * Accepts: true/false (native), "true"/"yes"/"on"/"1", "false"/"no"/"off"/"0".
     */
    private static boolean parseBooleanLenient(Config bc, String key) {
        try {
            return bc.getBoolean(key);
        } catch (ConfigException.WrongType wrongType) {
            var s = bc.getString(key).trim().toLowerCase(Locale.ROOT);
            return switch (s) {
                case "true", "yes", "on", "1" -> true;
                case "false", "no", "off", "0", "" -> false;
                default -> throw wrongType;
            };
        }
    }

    private static InferenceBackend createBackend(Config bc) {
        var name = bc.getString("name");
        var type = bc.getString("type");
        var priority = bc.getInt("priority");
        var models = bc.hasPath("models") ? bc.getStringList("models") : List.<String>of();

        // claude-cli doesn't need a URL
        if ("claude-cli".equals(type)) {
            return createClaudeCli(bc, name, priority, models);
        }

        var url = bc.hasPath("url") ? bc.getString("url") : "";
        if (url.isBlank()) {
            log.warn("Backend '{}' has empty URL — skipping", name);
            return null;
        }

        // An mlx://host:port URL always means the MLX backend, regardless of
        // declared type — keeps legacy configs that only set `url` working
        // without forcing every steward to also set `type: "mlx"`.
        // §"Phase 2".
        if (isMlxScheme(url) || "mlx".equals(type)) {
            return createMlx(bc, name, url, priority, models);
        }

        try {
            return switch (type) {
                case "llama-server" -> createLlamaServer(bc, name, url, priority, models);
                case "ollama" -> new InferenceBackend.Ollama(
                        name, new InferenceClient(url, null, INFERENCE_TIMEOUT,
                            new ApiProvider.OpenAI("ollama")), priority, models);
                case "sglang" -> new InferenceBackend.SGLang(
                        name, new InferenceClient(url, null, INFERENCE_TIMEOUT,
                            new ApiProvider.OpenAI("sglang")), priority, models);
                case "vllm", "vllm-mlx" -> new InferenceBackend.VLLM(
                        name, new InferenceClient(url, null, INFERENCE_TIMEOUT,
                            new ApiProvider.OpenAI("vllm")), priority, models);
                case "cloud" -> createCloud(bc, name, url, priority, models);
                default -> {
                    log.warn("Unknown backend type '{}' for '{}' — skipping", type, name);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.warn("Failed to create backend '{}': {}", name, e.getMessage());
            return null;
        }
    }

    /** True if {@code url} uses the {@code mlx://} scheme tag (case-insensitive). */
    static boolean isMlxScheme(String url) {
        return url != null
            && url.regionMatches(true, 0, "mlx://", 0, "mlx://".length());
    }

    /**
     * Rewrite {@code mlx://host:port[/path]} to {@code http://host:port[/path]}.
     * Other schemes are returned unchanged. Used because {@code java.net.http}
     * rejects custom URI schemes, but we want to preserve the {@code mlx://}
     * tag in logs and {@code wyrd inference status} for clarity.
     */
    static String stripMlxScheme(String url) {
        if (!isMlxScheme(url)) return url;
        return "http://" + url.substring("mlx://".length());
    }

    private static InferenceBackend createMlx(Config bc, String name, String url,
                                                int priority, List<String> models) {
        var httpUrl = stripMlxScheme(url);
        var displayUrl = isMlxScheme(url) ? url : "mlx://" + url.replaceFirst("^https?://", "");
        var effectiveModels = models;
        if (effectiveModels.isEmpty()) {
            effectiveModels = discoverModels(httpUrl);
            if (!effectiveModels.isEmpty()) {
                log.info("MLX backend '{}' discovered models from {}: {}",
                    name, httpUrl, effectiveModels);
            }
        }
        var client = new InferenceClient(httpUrl, null, INFERENCE_TIMEOUT,
            new ApiProvider.OpenAI("mlx"));
        log.info("MLX backend '{}' wired to {} (display {})", name, httpUrl, displayUrl);
        return new InferenceBackend.Mlx(name, client, priority, effectiveModels, displayUrl);
    }

    private static InferenceBackend createLlamaServer(Config bc, String name, String url,
                                                       int priority, List<String> models) {
        var modelPath = bc.hasPath("model-path") ? bc.getString("model-path") : "";
        LlamaServerManager manager = null;

        // Do not spawn a server for a model something else already serves.
        //
        // A GPU host running the bundled docker inference has the drive model on
        // :8200 and the voice model on :8201. If WYRDSEKAI_MODEL_PATH is also set
        // (wyrd setup writes it), this branch started a THIRD llama-server for
        // the same weights — on :11525, with no --n-gpu-layers, so on CPU. It
        // competed for cores, and because it was the backend literally named
        // "llama-server" the router preferred it over the GPU pair.
        //
        // LlamaServerManager.start() already adopts a healthy server on ITS OWN
        // port; that cannot see a sibling on a different port. This checks the
        // model instead of the port (second-node, 2026-07-29).
        if (!modelPath.isBlank()) {
            var already = servedAt(modelPath);
            if (already != null) {
                // REDIRECT, don't just skip. Clearing model-path alone would drop
                // through to "connect to the configured url" — and that url is
                // :11525, the port we would have spawned on, where nothing is
                // now listening. That turns a duplicate into a DEAD backend,
                // which lands right back on the voice-fallback path this whole
                // fix exists to close.
                log.warn("Backend '{}': {} is already served at {} — using that "
                    + "instead of starting a duplicate llama-server (configured "
                    + "url {} would be dead).", name, modelPath, already, url);
                modelPath = "";
                url = already;
            }
        }

        if (!modelPath.isBlank()) {
            // Auto-start llama-server as child process
            var executable = bc.hasPath("executable") ? bc.getString("executable") : "llama-server";
            // Floor the PER-SLOT window at what the prompt assembler is allowed
            // to produce. PromptAssembler.MIN_BACKEND_SAFE_PROMPT_TOKENS is 7500
            // and its javadoc names 8192 as the smallest production backend — so
            // a 4096 fallback here is a window that provably cannot hold a legal
            // prompt. It shipped as the default and cost a companion her ability
            // to act: turns of 4606-4782 tokens returned HTTP 400 "exceeds the
            // available context size (4096 tokens)", the router fell back to the
            // VOICE backend (no tools), and she narrated building a room instead
            // of building one (second-node, 2026-07-29).
            //
            // Raising a floor is safe in the direction that matters: too much
            // context wastes KV cache, too little silently removes capability.
            var contextSize = Math.max(
                MIN_PER_SLOT_CONTEXT,
                bc.hasPath("context-size") ? bc.getInt("context-size") : MIN_PER_SLOT_CONTEXT);
            var gpuLayers = bc.hasPath("gpu-layers") ? bc.getInt("gpu-layers") : 0;
            var port = bc.hasPath("port") ? bc.getInt("port") : 11525;

            manager = new LlamaServerManager(executable, modelPath, port, contextSize, gpuLayers);
            try {
                var client = manager.start();
                log.info("llama-server started on port {} for backend '{}'", port, name);
                return new InferenceBackend.LlamaServer(name, client, priority, models, manager);
            } catch (IOException e) {
                log.warn("Failed to start llama-server for '{}': {}", name, e.getMessage());
                return null;
            }
        }

        // Connect to existing llama-server at URL. If the config didn't pin a
        // model list, query /v1/models so CapabilityRegistry heuristics (quick /
        // reasoning) and Main's ROUTINE/COMPLEX backend lookup-by-model work.
        var effectiveModels = models;
        if (effectiveModels.isEmpty()) {
            effectiveModels = discoverModels(url);
            if (!effectiveModels.isEmpty()) {
                log.info("Backend '{}' discovered models from {}: {}", name, url, effectiveModels);
            }
        }
        return new InferenceBackend.LlamaServer(
                name, new InferenceClient(url, null, INFERENCE_TIMEOUT,
                    new ApiProvider.OpenAI("llama-server")), priority, effectiveModels, manager);
    }

    /**
     * Query /v1/models for model ids. Returns empty list on any failure.
     *
     * <p>#6 (2026-07-19 OSS hardening) — this runs at boot for an enabled URL
     * backend that didn't pin its model list (e.g. the 4B {@code llama-voice}
     * tier). A companion node and its llama-server are started together by
     * {@code wyrd start}, so the node frequently reaches this probe a beat before
     * the server is accepting connections. The old single 1s attempt then
     * returned empty, the backend registered with NO models, and
     * CapabilityRegistry could never resolve {@code cap:quick} — the voice tier
     * was dead until a full restart. We now retry only the "server not up yet"
     * cases (connection refused / timeout) a bounded number of times; a server
     * that IS up but returns non-200 / empty is not a race and stops immediately.
     * Tunable via {@code WYRDSEKAI_MODEL_DISCOVERY_RETRIES} /
     * {@code _TIMEOUT_MS}.</p>
     */
    private static List<String> discoverModels(String url) {
        int retries = envInt("WYRDSEKAI_MODEL_DISCOVERY_RETRIES", 8);
        int timeoutMs = envInt("WYRDSEKAI_MODEL_DISCOVERY_TIMEOUT_MS", 2000);
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                var conn = (HttpURLConnection)
                    URI.create(url + "/v1/models").toURL().openConnection();
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                if (conn.getResponseCode() != 200) return List.of();
                var body = new String(conn.getInputStream().readAllBytes());
                var mapper = new ObjectMapper();
                var root = mapper.readTree(body);
                var data = root.get("data");
                if (data == null || !data.isArray()) return List.of();
                var out = new ArrayList<String>();
                for (var m : data) {
                    var id = m.has("id") ? m.get("id").asText() : "";
                    if (!id.isBlank()) out.add(id);
                }
                return out;
            } catch (ConnectException | SocketTimeoutException e) {
                // Server not accepting connections yet — likely still starting.
                // Retry with a short backoff (unless we've exhausted attempts).
                if (attempt < retries) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return List.of();
                    }
                }
            } catch (Exception e) {
                // Any other failure — not a startup race; don't spin.
                return List.of();
            }
        }
        log.warn("Model discovery at {} found no reachable server after {} attempts (~{}s) — "
            + "cap routing for this backend is degraded until it comes up and the node restarts. "
            + "Pin `models = [...]` in the backend config to avoid the probe.", url, retries + 1, retries);
        return List.of();
    }

    /** Read a positive int from env, falling back to {@code def} on unset/invalid. */
    private static int envInt(String key, int def) {
        var v = System.getenv(key);
        if (v == null || v.isBlank()) return def;
        try {
            var n = Integer.parseInt(v.trim());
            return n >= 0 ? n : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static InferenceBackend createCloud(Config bc, String name, String url,
                                                 int priority, List<String> models) {
        var apiKey = bc.hasPath("api-key") ? bc.getString("api-key") : "";
        var providerName = bc.hasPath("provider") ? bc.getString("provider") : "openai";

        ApiProvider provider = switch (providerName) {
            case "anthropic" -> new ApiProvider.Anthropic();
            default -> new ApiProvider.OpenAI();
        };

        var client = new InferenceClient(url, apiKey.isBlank() ? null : apiKey,
                Duration.ofSeconds(30), provider);
        return new InferenceBackend.Cloud(name, client, priority, models);
    }

    private static InferenceBackend createClaudeCli(Config bc, String name,
                                                      int priority, List<String> models) {
        var cliPath = bc.hasPath("cli-path") ? bc.getString("cli-path") : "claude";
        var detected = ClaudeCliInference.autoDetect(cliPath);
        if (detected.isEmpty()) {
            log.warn("Claude CLI not available at '{}' — skipping '{}'", cliPath, name);
            return null;
        }
        var cli = detected.get();
        var effectiveModels = models.isEmpty() ? cli.availableModels() : models;
        return new InferenceBackend.ClaudeCli(name, cli, priority, effectiveModels);
    }

    /**
     * Probe for llama-server(s) on well-known ports and return each healthy backend.
     *
     * <p>Dual-inference layout:
     * <ul>
     *   <li>:8200 — skills (main model, priority 5 — preferred default)</li>
     *   <li>:8201 — voice (4B + voice LoRA, priority 15 — resolved via cap:quick)</li>
     *   <li>:11525 — legacy Docker-bundled llama-server (priority 15 — single-server fallback)</li>
     * </ul>
     *
     * <p>If {@code WYRDSEKAI_INFERENCE_URL} is set, only that URL is probed (legacy single-server).
     */
    /**
     * The URL of a reachable llama-server already serving {@code modelPath}'s
     * model, or {@code null} if nobody is.
     *
     * <p>Compares the FILE NAME, not the path: the docker backends see the model
     * as {@code /models/x.gguf} while the host config says
     * {@code /var/lib/wyrdsekai/models/x.gguf}. Same weights, different mount.</p>
     *
     * <p>Deliberately narrow and fail-open. It probes only :8200 and :8201 (the
     * bundled dual-inference pair), never :11525 — that is the port this branch
     * would spawn on, and LlamaServerManager already adopts a healthy server
     * there. A probe failure means "not served", so a slow or absent sibling can
     * never stop us starting our own; the cost of being wrong is a duplicate,
     * which is what we had before, not a dead backend.</p>
     */
    /**
     * One-shot {@code /v1/models}: what is served on {@code url} RIGHT NOW.
     *
     * <p>Deliberately NOT {@link #discoverModels}, which retries 8 times at 2s
     * each because it is used while a server we just spawned is still coming up.
     * Here the question is different — "has someone else already got this?" — and
     * the answer for an absent server must be immediate. Using the retrying
     * version would have added up to ~30s per port to boot on every host WITHOUT
     * docker inference, i.e. the common case, to answer "no".</p>
     *
     * <p>Any failure returns empty, which reads as "not served" and lets the
     * caller start its own server.</p>
     */
    private static List<String> modelsServedNow(String url) {
        try {
            var conn = (HttpURLConnection)
                URI.create(url + "/v1/models").toURL().openConnection();
            conn.setConnectTimeout(400);
            conn.setReadTimeout(400);
            if (conn.getResponseCode() != 200) return List.of();
            var root = new ObjectMapper().readTree(conn.getInputStream().readAllBytes());
            var data = root.get("data");
            if (data == null || !data.isArray()) return List.of();
            var out = new ArrayList<String>();
            for (var m : data) {
                var id = m.has("id") ? m.get("id").asText() : "";
                if (!id.isBlank()) out.add(id);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String servedAt(String modelPath) {
        var slash = Math.max(modelPath.lastIndexOf('/'), modelPath.lastIndexOf('\\'));
        var wanted = (slash >= 0 ? modelPath.substring(slash + 1) : modelPath).trim();
        if (wanted.isEmpty()) return null;
        for (var url : List.of("http://127.0.0.1:8200", "http://127.0.0.1:8201")) {
            for (var served : modelsServedNow(url)) {
                if (served == null) continue;
                var sl = Math.max(served.lastIndexOf('/'), served.lastIndexOf('\\'));
                var name = (sl >= 0 ? served.substring(sl + 1) : served).trim();
                if (wanted.equalsIgnoreCase(name)) {
                    return url;
                }
            }
        }
        return null;
    }

    private static List<InferenceBackend> autoDetectLlamaServers() {
        var envUrl = WyrdConfig.get().resolve(
            "WYRDSEKAI_INFERENCE_URL", "inference.url", () -> null);
        if (envUrl != null && !envUrl.isBlank()) {
            return probeLlamaServer(envUrl, "llama-server-auto", 5)
                .map(List::<InferenceBackend>of)
                .orElse(List.of());
        }

        var found = new ArrayList<InferenceBackend>();
        // :8200 — the skills backend (main model). Preferred for default + cap:reasoning.
        probeLlamaServer("http://127.0.0.1:8200", "llama-skills-auto", 5)
            .ifPresent(found::add);
        // :8201 — the voice backend (4B + voice LoRA). Wins only via cap:quick resolution.
        probeLlamaServer("http://127.0.0.1:8201", "llama-voice-auto", 15)
            .ifPresent(found::add);
        // :11525 — legacy Docker-bundled llama-server. Only probe if nothing on 8200/8201.
        if (found.isEmpty()) {
            probeLlamaServer("http://127.0.0.1:11525", "llama-server-auto", 5)
                .ifPresent(found::add);
        }
        return found;
    }

    private static Optional<InferenceBackend> probeLlamaServer(
            String url, String name, int priority) {
        // Two probes:
        //   /health — llama-server (and sglang); returns LlamaServer backend.
        //   /v1/models — fallback for mlx_lm.server (no /health); returns Mlx backend.
        // The MLX runtime on macOS speaks the
        // same OpenAI-compatible API as llama-server but doesn't ship /health.
        // Without this fallback, dual-MLX deploys never wire up locally and the
        // InferenceRouter has no backend, even though both ports are healthy.
        boolean isLlamaServer = false;
        try {
            var conn = (HttpURLConnection)
                URI.create(url + "/health").toURL().openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            if (conn.getResponseCode() == 200) isLlamaServer = true;
        } catch (Exception e) {
            // /health unreachable — could still be MLX. Fall through.
        }

        // Discover model name from /v1/models. This probe also serves as the
        // MLX health check (mlx_lm.server only exposes /v1/models).
        String modelName = "default";
        boolean modelsOk = false;
        try {
            var modelsConn = (HttpURLConnection)
                URI.create(url + "/v1/models").toURL().openConnection();
            modelsConn.setConnectTimeout(1000);
            modelsConn.setReadTimeout(1000);
            if (modelsConn.getResponseCode() == 200) {
                modelsOk = true;
                var body = new String(modelsConn.getInputStream().readAllBytes());
                var mapper = new ObjectMapper();
                var root = mapper.readTree(body);
                var data = root.get("data");
                if (data != null && data.isArray() && !data.isEmpty()) {
                    modelName = data.get(0).get("id").asText("default");
                }
            }
        } catch (Exception e) { /* use default */ }

        if (!isLlamaServer && !modelsOk) {
            // Neither probe worked — nothing here.
            return Optional.empty();
        }

        if (isLlamaServer) {
            log.info("Auto-detected llama-server at {} as '{}' (model: {}, priority: {})",
                url, name, modelName, priority);
            return Optional.of(new InferenceBackend.LlamaServer(
                name,
                new InferenceClient(url, null, INFERENCE_TIMEOUT,
                    new ApiProvider.OpenAI("llama-server")),
                priority, List.of(modelName), null));
        }

        // MLX path: only /v1/models responded.
        var displayUrl = "mlx://" + url.replaceFirst("^https?://", "");
        log.info("Auto-detected mlx_lm.server at {} as '{}' (model: {}, priority: {})",
            displayUrl, name, modelName, priority);
        return Optional.of(new InferenceBackend.Mlx(
            name,
            new InferenceClient(url, null, INFERENCE_TIMEOUT,
                new ApiProvider.OpenAI("mlx_lm.server")),
            priority, List.of(modelName), displayUrl));
    }

    /**
     * Probe SGLang on the default port (8000). If it responds, auto-enable it
     * and discover loaded models via /v1/models (OpenAI-compatible).
     * SGLang is preferred over Ollama: native tool parsing, no empty responses,
     * per-request LoRA, RadixAttention prefix caching.
     */
    private static Optional<InferenceBackend> autoDetectSGLang() {
        var url = "http://127.0.0.1:8000";
        try {
            var conn = (HttpURLConnection)
                URI.create(url + "/health").toURL().openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            if (conn.getResponseCode() != 200) return Optional.empty();

            // Discover model names from /v1/models
            var models = new ArrayList<String>();
            try {
                var modelsConn = (HttpURLConnection)
                    URI.create(url + "/v1/models").toURL().openConnection();
                modelsConn.setConnectTimeout(1000);
                modelsConn.setReadTimeout(1000);
                if (modelsConn.getResponseCode() == 200) {
                    var body = new String(modelsConn.getInputStream().readAllBytes());
                    var mapper = new ObjectMapper();
                    var root = mapper.readTree(body);
                    var data = root.get("data");
                    if (data != null && data.isArray()) {
                        for (var model : data) {
                            var id = model.has("id") ? model.get("id").asText() : "";
                            if (!id.isBlank()) models.add(id);
                        }
                    }
                }
            } catch (Exception e) { /* use empty list */ }

            log.info("Auto-detected SGLang at {} with models: {}", url, models);
            return Optional.of(new InferenceBackend.SGLang(
                "sglang-auto", new InferenceClient(url, null, INFERENCE_TIMEOUT,
                    new ApiProvider.OpenAI("sglang")), 20, models));
        } catch (Exception e) {
            // Not running — that's fine
        }
        return Optional.empty();
    }

    /**
     * Probe Ollama on the default port. If it responds, auto-enable it
     * and discover available chat models (filtering out embedding-only models).
     */
    private static Optional<InferenceBackend> autoDetectOllama() {
        var url = "http://127.0.0.1:11434";
        try {
            var conn = (HttpURLConnection)
                URI.create(url + "/api/tags").toURL().openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            if (conn.getResponseCode() != 200) return Optional.empty();

            var body = new String(conn.getInputStream().readAllBytes());
            var models = discoverOllamaModels(body);
            if (models.isEmpty()) {
                log.warn("Ollama running at {} but no chat models found", url);
                return Optional.empty();
            }

            log.info("Auto-detected Ollama at {} with models: {}", url, models);
            return Optional.of(new InferenceBackend.Ollama(
                "ollama-auto", new InferenceClient(url, null, INFERENCE_TIMEOUT,
                    new ApiProvider.OpenAI("ollama")), 30, models));
        } catch (Exception e) {
            // Not running — that's fine
        }
        return Optional.empty();
    }

    /** Extract model names from Ollama /api/tags JSON, filtering out embedding models. */
    private static List<String> discoverOllamaModels(String tagsJson) {
        var models = new ArrayList<String>();
        // Embedding model families to skip
        var embeddingFamilies = List.of("bert", "nomic-bert");
        try {
            var mapper = new ObjectMapper();
            var root = mapper.readTree(tagsJson);
            var modelsNode = root.get("models");
            if (modelsNode != null && modelsNode.isArray()) {
                for (var model : modelsNode) {
                    var name = model.has("name") ? model.get("name").asText() : "";
                    var details = model.get("details");
                    var family = details != null && details.has("family")
                        ? details.get("family").asText() : "";
                    if (!name.isBlank() && !embeddingFamilies.contains(family)) {
                        models.add(name);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Ollama tags: {}", e.getMessage());
        }
        return models;
    }

    /**
     * Legacy config: build backends from local/cloud sections if they're enabled.
     */
    private static List<InferenceBackend> legacyBackends(Config config) {
        var backends = new ArrayList<InferenceBackend>();

        if (config.hasPath("local.enabled") && config.getBoolean("local.enabled")) {
            var url = config.getString("local.url");
            var modelPath = config.hasPath("local.model-path")
                    ? config.getString("local.model-path") : "";

            if (!modelPath.isBlank()) {
                var executable = config.getString("local.executable");
                // Same floor as the backends[] path — an old local.* config that
                // still says 4096 must not silently under-provision the window.
                var contextSize = Math.max(
                    MIN_PER_SLOT_CONTEXT, config.getInt("local.context-size"));
                var gpuLayers = config.getInt("local.gpu-layers");
                var port = config.getInt("local.port");
                var manager = new LlamaServerManager(executable, modelPath, port, contextSize, gpuLayers);
                try {
                    var client = manager.start();
                    backends.add(new InferenceBackend.LlamaServer(
                            "local", client, 10, List.of(), manager));
                    log.info("Legacy llama-server started on port {}", port);
                } catch (IOException e) {
                    log.warn("Legacy llama-server failed: {}", e.getMessage());
                    // Fall through — try connecting to URL
                    backends.add(new InferenceBackend.LlamaServer(
                            "local", new InferenceClient(url), 10, List.of(), null));
                }
            } else {
                backends.add(new InferenceBackend.LlamaServer(
                        "local", new InferenceClient(url), 10, List.of(), null));
            }
            log.info("Legacy local inference configured: {}", url);
        }

        if (config.hasPath("cloud.enabled") && config.getBoolean("cloud.enabled")) {
            var url = config.getString("cloud.url");
            var apiKey = config.getString("cloud.api-key");
            if (!url.isBlank()) {
                var client = new InferenceClient(url, apiKey.isBlank() ? null : apiKey,
                        Duration.ofSeconds(30));
                backends.add(new InferenceBackend.Cloud("cloud", client, 100, List.of()));
                log.info("Legacy cloud inference configured: {}", url);
            }
        }

        return backends;
    }
}
