package org.wyrdsekai.core.coding;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

import java.time.Duration;
import java.util.List;

/**
 * Runtime configuration for the {@link PiCodingBackend} (pi.dev's
 * {@code @mariozechner/pi-coding-agent}, MIT-licensed multi-provider
 * coding harness, May 2026 — / Phase 2f).
 *
 * <p>Pi's wire shape is intentionally similar to the Claude SDK's:
 * {@code pi -p "<prompt>" --mode json --model <alias> [--provider <p>]
 * [--no-session] [--api-key <k>]}. The killer feature is provider
 * fan-out: a single CLI routes to Anthropic, OpenAI, Google, Azure,
 * Bedrock, Mistral, Groq, etc. So one adapter covers the long tail of
 * cloud-paid providers we'd otherwise duplicate per-provider.</p>
 *
 * @param enabled         gate for production wiring; {@link
 *                        CodingBackendBootstrap} skips registration when
 *                        this is false.
 * @param executablePath  path / PATH lookup for the {@code pi} binary.
 * @param model           model alias passed via {@code --model}. Pi
 *                        accepts simple aliases ({@code sonnet}),
 *                        provider-prefixed ({@code openai/gpt-4o}), or
 *                        with thinking-level suffix
 *                        ({@code sonnet:high}).
 * @param provider        explicit provider name ({@code anthropic},
 *                        {@code openai}, {@code google}, …). When null
 *                        or blank, {@code --provider} is omitted and pi
 *                        infers from the model alias.
 * @param noSession       whether to pass {@code --no-session}
 *                        (ephemeral mode; default true to match the
 *                        Claude SDK's {@code --no-session-persistence}
 *                        invariant — every task is self-contained).
 * @param maxWallclock    hard timeout enforced by the subprocess
 *                        watchdog. SPEC §9.1 default: 30 minutes.
 * @param extraFlags      extra CLI arguments appended verbatim.
 */
public record PiCodingRuntimeConfig(
    boolean enabled,
    String executablePath,
    String model,
    String provider,
    boolean noSession,
    Duration maxWallclock,
    List<String> extraFlags
) {

    /** Stable name of the typesafe-config root for this backend. */
    public static final String CONFIG_ROOT = "wyrdsekai.coding.backends.pi";

    /** Default executable name; resolved against PATH. */
    public static final String DEFAULT_EXECUTABLE = "pi";

    /** Default model alias — sonnet routes via Anthropic by default. */
    public static final String DEFAULT_MODEL = "sonnet";

    /** Default max wallclock per SPEC §9.1. */
    public static final Duration DEFAULT_MAX_WALLCLOCK = Duration.ofMinutes(30);

    public PiCodingRuntimeConfig {
        if (executablePath == null || executablePath.isBlank())
            executablePath = DEFAULT_EXECUTABLE;
        if (model == null || model.isBlank()) model = DEFAULT_MODEL;
        // provider may be null/blank — pi infers from model
        if (maxWallclock == null || maxWallclock.isZero() || maxWallclock.isNegative())
            maxWallclock = DEFAULT_MAX_WALLCLOCK;
        extraFlags = (extraFlags == null) ? List.of() : List.copyOf(extraFlags);
    }

    /** Build a config with all fields defaulted; useful for tests. */
    public static PiCodingRuntimeConfig defaults() {
        return new PiCodingRuntimeConfig(
            false,
            DEFAULT_EXECUTABLE,
            DEFAULT_MODEL,
            null,
            true,
            DEFAULT_MAX_WALLCLOCK,
            List.of()
        );
    }

    /**
     * Read the {@code wyrdsekai.coding.backends.pi} block. Missing keys
     * fall back to documented defaults rather than throwing. Both
     * snake_case and dash-case keys are accepted.
     */
    public static PiCodingRuntimeConfig fromConfig(Config config) {
        if (config == null || !config.hasPath(CONFIG_ROOT)) {
            return defaults();
        }
        var block = config.getConfig(CONFIG_ROOT);

        boolean enabled = readBool(block, "enabled", false);
        String exec = readString(block, "executable_path",
            readString(block, "executable-path", DEFAULT_EXECUTABLE));
        String model = readString(block, "model", DEFAULT_MODEL);
        String provider = readStringOrNull(block, "provider");
        boolean noSession = readBool(block, "no_session",
            readBool(block, "no-session", true));
        long maxMin = readLong(block, "max_wallclock_min",
            readLong(block, "max-wallclock-min", DEFAULT_MAX_WALLCLOCK.toMinutes()));
        var flags = readStringList(block, "extra_flags");
        if (flags.isEmpty()) flags = readStringList(block, "extra-flags");

        return new PiCodingRuntimeConfig(
            enabled, exec, model, provider, noSession,
            Duration.ofMinutes(maxMin), flags);
    }

    private static String readString(Config c, String key, String fallback) {
        try {
            return c.hasPath(key) ? c.getString(key) : fallback;
        } catch (ConfigException _) {
            return fallback;
        }
    }

    private static String readStringOrNull(Config c, String key) {
        try {
            if (!c.hasPath(key)) return null;
            var v = c.getString(key);
            return (v == null || v.isBlank()) ? null : v;
        } catch (ConfigException _) {
            return null;
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
