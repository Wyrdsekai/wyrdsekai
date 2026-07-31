package org.wyrdsekai.core.coding;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

import java.time.Duration;

/**
 * Runtime configuration for the {@link OpenHandsBackend}.
 *
 * <p>Loaded from the typesafe-config block at
 * {@code wyrdsekai.coding.backends.openhands.*} (
 * §9.1 and §6 Q2 for resource-isolation defaults). All fields default to
 * sensible values that match the SPEC §6 Q2 recommendation (2 GB RAM, 5 GB
 * disk, 30 min wallclock cap).</p>
 *
 * <p><b>Transport (2026-05-05 live-verified)</b>: targets the OpenHands V1
 * Agent Server (REST polling on port 8000), pinned to
 * {@code ghcr.io/openhands/agent-server:1.19.1-python}. There is no
 * WebSocket route in the v1.19.1 OpenAPI surface; the adapter polls
 * {@code GET /api/conversations/{id}/events/search} incrementally.
 * See {@link OpenHandsBackend} for the full lifecycle.</p>
 *
 * @param enabled              gate for production wiring; {@link
 *                             CodingBackendBootstrap} skips registration
 *                             when this is false.
 * @param agentServerUrl       Base URL of the OpenHands V1 Agent Server
 *                             ({@code http://localhost:8000} by default).
 *                             Adapter speaks REST to {@code /api/...} and
 *                             {@code /health}.
 * @param dockerImage          OpenHands V1 Agent Server image. Surfaced
 *                             by {@code wyrd setup openhands} and used as
 *                             a marker for which version of the agent-server
 *                             we're configured to talk to.
 * @param maxRamGb             per-task RAM cap (forwarded as a session
 *                             constraint to the Agent Server's runtime).
 *                             SPEC §6 Q2 default: 2 GB.
 * @param maxDiskGb            per-task disk cap. SPEC §6 Q2 default: 5 GB.
 *                             Surfaced as a hint to OpenHands; the agent's
 *                             own sandbox enforces.
 * @param maxWallclockMin      per-task wallclock cap in minutes. SPEC §6 Q2
 *                             default: 30 min.
 * @param defaultProvider      LLM provider OpenHands should use
 *                             ({@code "anthropic"}, {@code "openai"},
 *                             {@code "local"}). Local points at the
 *                             household's llama-server.
 * @param requestTimeout       HTTP request timeout for any one REST call
 *                             (start-conversation, run, events/search,
 *                             agent_final_response). Cap on stall before
 *                             the adapter surfaces a FAILED result.
 * @param llmBaseUrl           Optional base URL pushed into
 *                             {@code agent.llm.base_url} on every
 *                             create-conversation call. When null/blank,
 *                             the agent-server uses its own container env
 *                             ({@code LLM_BASE_URL}) — the recommended
 *                             default.
 * @param llmModel             Optional model name pushed into
 *                             {@code agent.llm.model} on every
 *                             create-conversation call. When null/blank,
 *                             agent-server falls back to {@code LLM_MODEL}.
 * @param llmApiKey            Optional LLM provider API key pushed into
 *                             {@code agent.llm.api_key}. <b>Distinct from
 *                             the household-auth key resolved via
 *                             {@link AuthResolver}</b> — this is the
 *                             downstream LLM provider's key (e.g. an
 *                             OpenAI-compatible token, or the literal
 *                             {@code "not-required"} for local
 *                             llama-server). When null/blank the adapter
 *                             omits the field; litellm then falls back to
 *                             the agent-server's container env
 *                             ({@code LLM_API_KEY} / {@code OPENAI_API_KEY}).
 *                             Note: passing {@code base_url} + {@code model}
 *                             without an explicit {@code api_key} causes
 *                             litellm to fail with an OpenAIError, since
 *                             a per-call override disables env-var
 *                             fallback. Stewards using the local-server
 *                             path should set this to the literal
 *                             {@code "not-required"}.
 * @param maxIterations        cap on the agent loop's iteration count
 *                             ({@code max_iterations} in the V1 create
 *                             body). Default 30 — short enough to fit our
 *                             wallclock cap, generous enough for typical
 *                             explore/refactor/implement runs.
 * @param stuckDetection       whether the agent-server's stuck-detection
 *                             heuristic is on. Default true (matches V1
 *                             default).
 * @param defaultWorkingDir    container-side working directory. Used when
 *                             a {@link TaskSpec} has no
 *                             {@code workspaceHint} of its own. Default
 *                             {@code /workspace} matches the agent-server
 *                             image's bundled mount point.
 */
public record OpenHandsRuntimeConfig(
    boolean enabled,
    String agentServerUrl,
    String dockerImage,
    int maxRamGb,
    int maxDiskGb,
    int maxWallclockMin,
    String defaultProvider,
    Duration requestTimeout,
    String llmBaseUrl,
    String llmModel,
    String llmApiKey,
    int maxIterations,
    boolean stuckDetection,
    String defaultWorkingDir,
    boolean nativeToolCalling
) {

    /** Stable name of the typesafe-config root for this backend. */
    public static final String CONFIG_ROOT = "wyrdsekai.coding.backends.openhands";

    /**
     * Default Agent Server URL. The OpenHands V1 Agent Server documents
     * port 8000 as its default ({@code openhands-agent-server --port
     * 8000}). Pre-2026-05 the manifest defaulted to 3000/mcp — a hangover
     * from a fabricated MCP transport — and is now corrected.
     */
    public static final String DEFAULT_AGENT_SERVER_URL = "http://localhost:8000";

    /**
     * Default Docker image — pinned to the V1 standalone agent-server
     * (live-verified 2026-05-05). The pre-2026-05-05 default
     * ({@code ghcr.io/all-hands-ai/openhands:v1.7.0}) was a V0 runtime
     * image at the wrong registry path.
     */
    public static final String DEFAULT_DOCKER_IMAGE =
        "ghcr.io/openhands/agent-server:1.19.1-python";

    /** Default per-task RAM cap (SPEC §6 Q2). */
    public static final int DEFAULT_MAX_RAM_GB = 2;

    /** Default per-task disk cap (SPEC §6 Q2). */
    public static final int DEFAULT_MAX_DISK_GB = 5;

    /** Default per-task wallclock cap in minutes (SPEC §6 Q2). */
    public static final int DEFAULT_MAX_WALLCLOCK_MIN = 30;

    /** Default LLM provider — local llama-server. */
    public static final String DEFAULT_PROVIDER = "local";

    /** Default request timeout — one minute is enough for probe + start-conversation. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Default agent-loop iteration cap. Short enough to fit a 30-min
     * wallclock comfortably; generous enough for typical explore /
     * refactor runs.
     */
    public static final int DEFAULT_MAX_ITERATIONS = 30;

    /** Default for {@link #stuckDetection}. */
    public static final boolean DEFAULT_STUCK_DETECTION = true;

    /**
     * Default for {@link #nativeToolCalling}. {@code false} matches the
     * realistic local-llama-server case: small models (4B/9B) reliably
     * mis-escape JSON tool-call arguments when file bodies contain
     * apostrophes or embedded quotes, breaking entire conversations.
     * The SDK's {@code NonNativeToolCallingMixin} (text-based tool calls
     * parsed via structured prompts) is the safer default. Stewards on
     * bigger / tool-call-trained backends (Anthropic, GPT-5+,
     * Qwen3-Coder-32B) can flip this to {@code true} via config.
     */
    public static final boolean DEFAULT_NATIVE_TOOL_CALLING = false;

    /**
     * Default container-side working directory. Matches the path
     * pre-mounted in {@code ghcr.io/openhands/agent-server:1.19.1-python}.
     */
    public static final String DEFAULT_WORKING_DIR = "/workspace";

    /**
     * Fallback model name used when {@link #llmModel()} is unset. Matches
     * the {@code LLM-Input.model} default in the V1 OpenAPI surface
     * (v1.19.1 — live-verified 2026-05-05). Note that V1's pydantic
     * validation requires {@code agent.llm.model} on every create call;
     * an empty {@code {}} surfaces as a 500 — the adapter therefore
     * always emits a model, falling back to this constant when no
     * steward override is configured. <b>This default does not actually
     * route to a working backend in the local-llama-server case</b> —
     * stewards using a local server should always set {@code llm_model}
     * (and {@code llm_base_url}) in their config.
     */
    public static final String V1_DEFAULT_MODEL = "claude-sonnet-4-20250514";

    public OpenHandsRuntimeConfig {
        // Defensive normalisation — every field stays non-null/non-zero
        // even if a hand-built record passes through.
        if (agentServerUrl == null || agentServerUrl.isBlank()) agentServerUrl = DEFAULT_AGENT_SERVER_URL;
        if (dockerImage == null || dockerImage.isBlank()) dockerImage = DEFAULT_DOCKER_IMAGE;
        if (maxRamGb <= 0) maxRamGb = DEFAULT_MAX_RAM_GB;
        if (maxDiskGb <= 0) maxDiskGb = DEFAULT_MAX_DISK_GB;
        if (maxWallclockMin <= 0) maxWallclockMin = DEFAULT_MAX_WALLCLOCK_MIN;
        if (defaultProvider == null || defaultProvider.isBlank()) defaultProvider = DEFAULT_PROVIDER;
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative())
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        if (maxIterations <= 0) maxIterations = DEFAULT_MAX_ITERATIONS;
        if (defaultWorkingDir == null || defaultWorkingDir.isBlank())
            defaultWorkingDir = DEFAULT_WORKING_DIR;
        // llmBaseUrl / llmModel may legitimately be null — they're optional.
    }

    /**
     * Back-compat alias for legacy callers that still ask for "mcpUrl".
     * The 2026-05-04 reconciliation renamed the field to
     * {@link #agentServerUrl()} since the transport is REST + WS, not
     * JSON-RPC MCP. Existing callers should migrate; this shim removes
     * compile-break risk in unrelated code paths.
     *
     * @deprecated use {@link #agentServerUrl()}.
     */
    @Deprecated
    public String mcpUrl() {
        return agentServerUrl;
    }

    /** Build a config with all fields defaulted; useful for tests. */
    public static OpenHandsRuntimeConfig defaults() {
        return new OpenHandsRuntimeConfig(
            false,                        // disabled by default — SPEC §8.1 opt-in
            DEFAULT_AGENT_SERVER_URL,
            DEFAULT_DOCKER_IMAGE,
            DEFAULT_MAX_RAM_GB,
            DEFAULT_MAX_DISK_GB,
            DEFAULT_MAX_WALLCLOCK_MIN,
            DEFAULT_PROVIDER,
            DEFAULT_REQUEST_TIMEOUT,
            null,                         // llmBaseUrl — env-var fallback
            null,                         // llmModel  — env-var fallback
            null,                         // llmApiKey — env-var fallback
            DEFAULT_MAX_ITERATIONS,
            DEFAULT_STUCK_DETECTION,
            DEFAULT_WORKING_DIR,
            DEFAULT_NATIVE_TOOL_CALLING
        );
    }

    /** Convenience accessor for the wallclock budget as a {@link Duration}. */
    public Duration maxWallclock() {
        return Duration.ofMinutes(maxWallclockMin);
    }

    /**
     * Read the {@code wyrdsekai.coding.backends.openhands} block. Missing
     * keys fall back to the documented defaults rather than throwing — a
     * household that drops the block entirely still gets a working
     * (disabled) entry. Both snake_case and dash-case keys are accepted to
     * match the rest of the coding-backends config family.
     *
     * <p>Reads both the new {@code agent_server_url} key and the legacy
     * {@code mcp_url} key for compat with manifests written before the
     * 2026-05-04 reconciliation. Legacy reads log a debug-level note;
     * fresh writes always use {@code agent_server_url}.</p>
     */
    public static OpenHandsRuntimeConfig fromConfig(Config config) {
        if (config == null || !config.hasPath(CONFIG_ROOT)) {
            return defaults();
        }
        var block = config.getConfig(CONFIG_ROOT);
        boolean enabled = readBool(block, "enabled", false);
        // New canonical key — falls back to legacy "mcp_url"/"mcp-url" if
        // the steward's config predates the reconciliation.
        String agentServerUrl = readString(block, "agent_server_url",
            readString(block, "agent-server-url",
              readString(block, "mcp_url",
                readString(block, "mcp-url", DEFAULT_AGENT_SERVER_URL))));
        String image = readString(block, "docker_image",
            readString(block, "docker-image", DEFAULT_DOCKER_IMAGE));
        int maxRam = (int) readLong(block, "max_ram_gb",
            readLong(block, "max-ram-gb", DEFAULT_MAX_RAM_GB));
        int maxDisk = (int) readLong(block, "max_disk_gb",
            readLong(block, "max-disk-gb", DEFAULT_MAX_DISK_GB));
        int maxMin = (int) readLong(block, "max_wallclock_min",
            readLong(block, "max-wallclock-min", DEFAULT_MAX_WALLCLOCK_MIN));
        String provider = readString(block, "default_provider",
            readString(block, "default-provider", DEFAULT_PROVIDER));
        long timeoutSec = readLong(block, "request_timeout_sec",
            readLong(block, "request-timeout-sec", DEFAULT_REQUEST_TIMEOUT.getSeconds()));
        String llmBaseUrl = readString(block, "llm_base_url",
            readString(block, "llm-base-url", null));
        String llmModel = readString(block, "llm_model",
            readString(block, "llm-model", null));
        String llmApiKey = readString(block, "llm_api_key",
            readString(block, "llm-api-key", null));
        int maxIter = (int) readLong(block, "max_iterations",
            readLong(block, "max-iterations", DEFAULT_MAX_ITERATIONS));
        boolean stuck = readBool(block, "stuck_detection",
            readBool(block, "stuck-detection", DEFAULT_STUCK_DETECTION));
        String wd = readString(block, "default_working_dir",
            readString(block, "default-working-dir", DEFAULT_WORKING_DIR));
        boolean nativeTools = readBool(block, "native_tool_calling",
            readBool(block, "native-tool-calling", DEFAULT_NATIVE_TOOL_CALLING));

        return new OpenHandsRuntimeConfig(
            enabled, agentServerUrl, image, maxRam, maxDisk, maxMin, provider,
            Duration.ofSeconds(timeoutSec),
            llmBaseUrl, llmModel, llmApiKey, maxIter, stuck, wd, nativeTools);
    }

    private static String readString(Config c, String key, String fallback) {
        try {
            return c.hasPath(key) ? c.getString(key) : fallback;
        } catch (ConfigException _) {
            return fallback;
        }
    }

    private static boolean readBool(Config c, String key, boolean fallback) {
        try {
            return c.hasPath(key) ? c.getBoolean(key) : fallback;
        } catch (ConfigException _) {
            return fallback;
        }
    }

    private static long readLong(Config c, String key, long fallback) {
        try {
            return c.hasPath(key) ? c.getLong(key) : fallback;
        } catch (ConfigException _) {
            return fallback;
        }
    }
}
