package org.wyrdsekai.core.coding;

import org.wyrdsekai.core.inference.LocalInferenceEndpoint;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Runtime configuration for the {@link GooseBackend}.
 *
 * <p>Loaded from the typesafe-config block at
 * {@code wyrdsekai.coding.backends.goose.*} (
 * §9.1). Goose is the simplest of the Phase 2d trio: API-key-only auth
 * (no OAuth path), provider-pluggable (anthropic / openai / google /
 * ollama), and a single subprocess shape ({@code goose run --text <spec>
 * --output-format json --no-session -q}).</p>
 *
 * <p><b>2026-05-05 reconciliation</b>: Verified against
 * {@code aaif-goose/goose@main} {@code crates/goose-cli/src/cli.rs}
 * (v1.33.1). Notable findings vs the pre-2026-05 adapter:
 * <ul>
 *   <li>Goose has <b>no {@code local} provider</b>. To target a local
 *       OpenAI-compatible llama-server, set {@code provider = "openai"}
 *       plus {@code base-url = "http://localhost:8200/v1"}; the adapter
 *       will export {@code OPENAI_HOST} so upstream Goose's built-in
 *       OpenAI provider routes there. {@code provider = "ollama"} is
 *       also key-free and may suit households running Ollama directly.
 *   </li>
 *   <li>Goose has no {@code --workspace} CLI flag — workspace is the
 *       subprocess CWD. The adapter sets the CWD via the
 *       {@link GooseBackend.ProcessRunner} contract.
 *   </li>
 *   <li>Headless argv flags are {@code --text <T>}, {@code --output-format
 *       json}, {@code --no-session}, {@code -q}. Pre-reconciliation argv
 *       used {@code --task=<T>}, {@code --format=json}, and a bogus
 *       {@code --workspace=<P>}.
 *   </li>
 * </ul>
 * </p>
 *
 * @param enabled         gate for production wiring; {@link
 *                        CodingBackendBootstrap} skips registration when
 *                        this is false.
 * @param executablePath  path / PATH lookup for the {@code goose} binary.
 *                        Phase 2a installs to a fixed location; Phase 2d
 *                        also accepts an explicit override.
 * @param provider        upstream provider Goose should target
 *                        ({@code anthropic}, {@code openai},
 *                        {@code google}/{@code gemini-cli},
 *                        {@code ollama}, …). Forwarded to the subprocess
 *                        via the {@code GOOSE_PROVIDER} env var (and
 *                        the redundant-but-explicit {@code --provider}
 *                        flag introduced in v1.30).
 * @param model           model id Goose should ask the provider for —
 *                        wired via the {@code GOOSE_MODEL} env var. For
 *                        the default {@code provider=openai} +
 *                        local-llama-server posture this is the model
 *                        name llama-server exposes (e.g.
 *                        {@code wyrdsekai-3.5-9b-v5-q4km.gguf}).
 * @param baseUrl         OpenAI-compatible inference endpoint used when
 *                        {@code provider=openai} (or any provider that
 *                        respects {@code OPENAI_HOST}). Defaults to the
 *                        bundled local llama-server. Ignored for the
 *                        {@code anthropic} / {@code google} providers.
 * @param maxWallclock    hard timeout enforced by the subprocess
 *                        watchdog. SPEC §9.1 default: 30 minutes.
 * @param extraFlags      extra CLI arguments appended to every
 *                        {@code goose run} invocation.
 */
public record GooseRuntimeConfig(
    boolean enabled,
    String executablePath,
    String provider,
    String model,
    String baseUrl,
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
    public GooseRuntimeConfig(boolean enabled, String executablePath, String provider, String model, String baseUrl, Duration maxWallclock, List<String> extraFlags) {
        this(enabled, executablePath, provider, model, baseUrl, maxWallclock, extraFlags,
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
        return LocalInferenceEndpoint.resolve().map(e -> e.url())
            .orElse(baseUrl);
    }

    /** The model id to send: configured, else what the live server reports. */
    public String effectiveModel() {
        if (modelFromConfig) return model;
        return LocalInferenceEndpoint.resolve().map(LocalInferenceEndpoint.Endpoint::modelId)
            .orElse(model);
    }


    /** Stable name of the typesafe-config root for this backend. */
    public static final String CONFIG_ROOT = "wyrdsekai.coding.backends.goose";

    /** Default executable name; resolved against PATH. */
    public static final String DEFAULT_EXECUTABLE = "goose";

    /**
     * Resolve the default goose executable. {@code wyrd setup} installs the
     * binary under the data dir's {@code coding-cli-bundle/goose/goose} but does
     * NOT place it on PATH, so a bare {@code goose} exec fails with "Cannot run
     * program goose" (live-found 2026-06-23: dispatch_task reached goose but the
     * server process PATH lacked the bundle). Prefer the bundled binary when it
     * exists; otherwise fall back to a PATH lookup.
     */
    static String resolveDefaultExecutable() {
        // One resolver for every backend: it searches the bundle dir, the
        // system prefixes, and the homes a per-user installer actually writes
        // to -- including the data-directory owner's, which is the one a
        // systemd service (HOME=/root) otherwise cannot see.
        return BackendExecutableResolver.resolve(DEFAULT_EXECUTABLE);
    }

    /** Default provider — local llama-server through Goose's built-in
     *  OpenAI provider. SPEC §9.1 example uses {@code anthropic}; we
     *  default to {@code openai} (paired with {@link #DEFAULT_BASE_URL})
     *  to match the out-of-box "complex items work without keys" posture
     *  — llama-server ignores the API key string Goose sends. */
    public static final String DEFAULT_PROVIDER = "openai";

    /** Default base URL — points at the household's bundled llama-server.
     *  <b>No {@code /v1} suffix</b>: goose v1.34.1+ appends {@code /v1} to
     *  {@code OPENAI_HOST} itself, so a value of {@code http://localhost:8200/v1}
     *  yields {@code .../v1/v1/chat/completions} → 404. Bug uncovered by
     *  GooseLiveInvocationE2ETest 2026-05-24. */
    public static final String DEFAULT_BASE_URL = "http://localhost:8200";

    /** Default model id — primary on home-server. Override per release. */
    public static final String DEFAULT_MODEL = "wyrdsekai-3.5-9b-v5-q4km.gguf";

    /** Default max wallclock per SPEC §9.1. */
    public static final Duration DEFAULT_MAX_WALLCLOCK = Duration.ofMinutes(30);

    public GooseRuntimeConfig {
        // Defensive normalisation — record fields stay non-null even if a
        // caller hand-builds the record.
        // Resolve the bundled binary when the value is blank OR the bare default
        // "goose" (the common case — application.conf ships executable="goose", but
        // the bundle dir isn't on PATH). An explicit full path is respected as-is.
        if (executablePath == null || executablePath.isBlank()
                || DEFAULT_EXECUTABLE.equals(executablePath))
            executablePath = resolveDefaultExecutable();
        if (provider == null || provider.isBlank())
            provider = DEFAULT_PROVIDER;
        if (model == null || model.isBlank())
            model = DEFAULT_MODEL;
        if (baseUrl == null || baseUrl.isBlank())
            baseUrl = DEFAULT_BASE_URL;
        if (maxWallclock == null || maxWallclock.isZero() || maxWallclock.isNegative())
            maxWallclock = DEFAULT_MAX_WALLCLOCK;
        extraFlags = (extraFlags == null) ? List.of() : List.copyOf(extraFlags);
    }

    /** Build a config with all fields defaulted; useful for tests. */
    public static GooseRuntimeConfig defaults() {
        return new GooseRuntimeConfig(
            false,                     // disabled by default — SPEC §8.1 opt-in
            DEFAULT_EXECUTABLE,
            DEFAULT_PROVIDER,
            DEFAULT_MODEL,
            DEFAULT_BASE_URL,
            DEFAULT_MAX_WALLCLOCK,
            List.of()
        );
    }

    /**
     * Read the {@code wyrdsekai.coding.backends.goose} block. Missing
     * keys fall back to the documented defaults rather than throwing —
     * a household that drops the block entirely still gets a working
     * (disabled) entry. Both snake_case and dash-case keys are accepted
     * to match the rest of the coding-backends config family.
     */
    public static GooseRuntimeConfig fromConfig(Config config) {
        if (config == null || !config.hasPath(CONFIG_ROOT)) {
            return defaults();
        }
        var block = config.getConfig(CONFIG_ROOT);

        boolean enabled = readBool(block, "enabled", false);
        String exec = readString(block, "executable_path",
            readString(block, "executable-path", DEFAULT_EXECUTABLE));
        String provider = readString(block, "provider", DEFAULT_PROVIDER);
        String model = readString(block, "model", DEFAULT_MODEL);
        String baseUrl = readString(block, "base_url",
            readString(block, "base-url", DEFAULT_BASE_URL));
        long maxMin = readLong(block, "max_wallclock_min",
            readLong(block, "max-wallclock-min", DEFAULT_MAX_WALLCLOCK.toMinutes()));
        var flags = readStringList(block, "extra_flags");
        if (flags.isEmpty()) flags = readStringList(block, "extra-flags");

        return new GooseRuntimeConfig(
            enabled, exec, provider, model, baseUrl,
            Duration.ofMinutes(maxMin), flags,
            chosen(baseUrl, DEFAULT_BASE_URL),
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
