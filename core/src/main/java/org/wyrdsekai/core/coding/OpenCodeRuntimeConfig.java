package org.wyrdsekai.core.coding;

import org.wyrdsekai.core.inference.LocalInferenceEndpoint;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

import java.time.Duration;
import java.util.List;

/**
 * Runtime configuration for the {@link OpenCodeBackend}.
 *
 * <p>Loaded from the typesafe-config block at
 * {@code wyrdsekai.coding.backends.opencode.*} (
 * §2.5 / §8.1). All fields default to sensible values that point the
 * subprocess at the household's local llama-server, so a fresh install
 * works out of the box without any household editing.</p>
 *
 * @param enabled              gate for production wiring; the
 *                             {@link CodingBackendBootstrap} skips the
 *                             register call entirely when this is false.
 * @param executablePath       path / PATH lookup for the {@code opencode}
 *                             binary. Phase 2a installs to a fixed location;
 *                             Phase 2b assumes PATH or an explicit override.
 * @param baseUrl              OpenAI-compatible inference endpoint
 *                             ({@code /v1/chat/completions}). Defaults to
 *                             the local llama-server.
 * @param model                model id the OpenCode subprocess should use
 *                             ({@code provider/model} or just the model
 *                             name for OpenCode's default provider).
 * @param providerName         shorthand provider key OpenCode embeds in
 *                             {@code provider/model}; matches the key in
 *                             the generated {@code opencode.json}.
 * @param apiKey               passed via {@code OPENAI_API_KEY} for
 *                             OpenAI-compatible providers; llama-server
 *                             ignores it but the {@code @ai-sdk/openai-compatible}
 *                             npm package still requires the env var to be
 *                             set to anything non-blank.
 * @param maxFilesPerTask      soft cap surfaced as a TaskSpec validation
 *                             check before submission; advisory only.
 * @param maxWallclock         hard timeout enforced by the subprocess
 *                             watchdog. Falls back to the documented
 *                             SPEC §9.1 default (30 minutes).
 * @param extraFlags           extra CLI arguments appended to every
 *                             {@code opencode run} invocation; lets
 *                             stewards turn on {@code --thinking},
 *                             {@code --share}, etc. without a code change.
 */
public record OpenCodeRuntimeConfig(
    boolean enabled,
    String executablePath,
    String baseUrl,
    String model,
    String providerName,
    String apiKey,
    int maxFilesPerTask,
    Duration maxWallclock,
    List<String> extraFlags,
    boolean baseUrlFromConfig,
    boolean modelFromConfig
) {
    /**
     * Back-compat shape. A value that equals the compiled-in default is NOT a choice —
     * it is what every caller passed before this distinction existed, including
     * {@code defaults()} — so it stays free to follow the node's real inference.
     * Anything else passed explicitly is a deliberate act and is honoured as one.
     */
    public OpenCodeRuntimeConfig(boolean enabled, String executablePath, String baseUrl, String model, String providerName, String apiKey, int maxFilesPerTask, Duration maxWallclock, List<String> extraFlags) {
        this(enabled, executablePath, baseUrl, model, providerName, apiKey, maxFilesPerTask, maxWallclock, extraFlags,
            baseUrl != null && !baseUrl.isBlank() && !DEFAULT_BASE_URL.equals(baseUrl),
            model != null && !model.isBlank() && !DEFAULT_MODEL.equals(model));
    }

    /**
     * The endpoint to actually use: what the operator configured, else what this node
     * serves right now, else the compiled-in default.
     *
     * <p>The compiled-in default is one machine's port layout. Staged fresh on
     * 2026-08-21 it sent the backend to a port with nothing on it while the install had
     * already found the real model one port over — see {@code LocalInferenceEndpoint}.
     */
    public String effectiveBaseUrl() {
        if (baseUrlFromConfig) return baseUrl;
        return LocalInferenceEndpoint.resolve().map(e -> e.url() + "/v1")
            .orElse(baseUrl);
    }

    /** The model id to send: configured, else what the live server reports. */
    public String effectiveModel() {
        if (modelFromConfig) return model;
        return LocalInferenceEndpoint.resolve().map(LocalInferenceEndpoint.Endpoint::modelId)
            .orElse(model);
    }


    /** Stable name of the typesafe-config root for this backend. */
    public static final String CONFIG_ROOT = "wyrdsekai.coding.backends.opencode";

    /** Default provider key used in OpenCode's generated config. */
    public static final String DEFAULT_PROVIDER = "wyrd-local";

    /** Default base URL — points at the household's bundled llama-server. */
    public static final String DEFAULT_BASE_URL = "http://localhost:8200/v1";

    /** Default model id — currently primary on home-server. Override per release. */
    public static final String DEFAULT_MODEL = "wyrdsekai-3.5-9b-vitality-v6";

    /** Default executable name; resolved against PATH. */
    public static final String DEFAULT_EXECUTABLE = "opencode";

    /** Default max wallclock per SPEC §9.1. */
    public static final Duration DEFAULT_MAX_WALLCLOCK = Duration.ofMinutes(30);

    /** Default soft file cap. */
    public static final int DEFAULT_MAX_FILES = 50;

    public OpenCodeRuntimeConfig {
        // Defensive normalisation — record fields stay non-null even if a
        // caller hand-builds the record.
        if (executablePath == null || executablePath.isBlank())
            executablePath = DEFAULT_EXECUTABLE;
        if (baseUrl == null || baseUrl.isBlank())
            baseUrl = DEFAULT_BASE_URL;
        if (model == null || model.isBlank())
            model = DEFAULT_MODEL;
        if (providerName == null || providerName.isBlank())
            providerName = DEFAULT_PROVIDER;
        if (apiKey == null) apiKey = "";
        if (maxFilesPerTask <= 0) maxFilesPerTask = DEFAULT_MAX_FILES;
        if (maxWallclock == null || maxWallclock.isZero() || maxWallclock.isNegative())
            maxWallclock = DEFAULT_MAX_WALLCLOCK;
        extraFlags = (extraFlags == null) ? List.of() : List.copyOf(extraFlags);
    }

    /** Build a config with all fields defaulted; useful for tests. */
    public static OpenCodeRuntimeConfig defaults() {
        return new OpenCodeRuntimeConfig(
            true,
            DEFAULT_EXECUTABLE,
            DEFAULT_BASE_URL,
            DEFAULT_MODEL,
            DEFAULT_PROVIDER,
            "not-required",
            DEFAULT_MAX_FILES,
            DEFAULT_MAX_WALLCLOCK,
            List.of()
        );
    }

    /**
     * Read the {@code wyrdsekai.coding.backends.opencode} block. Missing
     * keys fall back to the documented defaults rather than throwing —
     * a household that drops the block entirely still gets a working
     * default-on backend (per SPEC §2.5 "complex items must work out of
     * the box").
     */
    public static OpenCodeRuntimeConfig fromConfig(Config config) {
        if (config == null || !config.hasPath(CONFIG_ROOT)) {
            // No block at all — use defaults; treat as enabled so the
            // bundled binary path stays the out-of-box behaviour.
            return defaults();
        }
        var block = config.getConfig(CONFIG_ROOT);

        boolean enabled = readBool(block, "enabled", true);
        String exec = readString(block, "executable_path",
            readString(block, "executable-path", DEFAULT_EXECUTABLE));
        String url = readString(block, "base_url",
            readString(block, "base-url", DEFAULT_BASE_URL));
        String model = readString(block, "model", DEFAULT_MODEL);
        String provider = readString(block, "provider_name",
            readString(block, "provider-name", DEFAULT_PROVIDER));
        String apiKey = readString(block, "api_key",
            readString(block, "api-key", "not-required"));
        int maxFiles = (int) readLong(block, "max_files_per_task",
            readLong(block, "max-files-per-task", DEFAULT_MAX_FILES));
        long maxMin = readLong(block, "max_wallclock_min",
            readLong(block, "max-wallclock-min", DEFAULT_MAX_WALLCLOCK.toMinutes()));
        var flags = readStringList(block, "extra_flags");
        if (flags.isEmpty()) flags = readStringList(block, "extra-flags");

        return new OpenCodeRuntimeConfig(
            enabled, exec, url, model, provider, apiKey,
            maxFiles, Duration.ofMinutes(maxMin), flags,
            chosen(url, DEFAULT_BASE_URL),
            chosenModel(model));
    }


    /**
     * Was this value CHOSEN, or merely present? reference.conf ships inside the jar and
     * is merged into every load, so hasPath() is true for packaged defaults on every
     * node — staged 2026-08-21, that pinned a fresh install to one machine's port
     * layout ("http://localhost:8200/v1") while the node's only model sat one port
     * over. A value that equals the compiled default (ignoring a /v1 suffix and
     * trailing slash) is a default wherever it was written.
     */
    private static boolean chosen(String value, String compiledDefault) {
        if (value == null || value.isBlank()) return false;
        return !normalise(value).equals(normalise(compiledDefault));
    }

    private static boolean chosenModel(String value) {
        return value != null && !value.isBlank() && !DEFAULT_MODEL.equals(value);
    }

    private static String normalise(String url) {
        return url == null ? "" : url.replaceAll("/v1/?$", "").replaceAll("/$", "");
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

    private static List<String> readStringList(Config c, String key) {
        try {
            return c.hasPath(key) ? List.copyOf(c.getStringList(key)) : List.of();
        } catch (ConfigException _) {
            return List.of();
        }
    }
}
