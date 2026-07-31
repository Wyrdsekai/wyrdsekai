package org.wyrdsekai.core.coding;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

import java.time.Duration;
import java.util.List;

/**
 * Runtime configuration for the {@link ClaudeSdkBackend}.
 *
 * <p>Loaded from the typesafe-config block at
 * {@code wyrdsekai.coding.backends.claude-sdk.*} (
 * §9.1, Phase 2e). The Claude Code SDK ships as the npm package
 * {@code @anthropic-ai/claude-code} and exposes a headless mode via
 * {@code claude -p "<prompt>" --output-format json}. The new {@code --bare}
 * flag (will become the default for {@code -p} in a future release) skips
 * OAuth/keychain reads — bare-mode invocation requires an
 * {@code ANTHROPIC_API_KEY} on the env. We pass {@code --bare} only when
 * the resolver has handed us an {@link AuthMode.ApiKey}; OAuth sessions
 * intentionally avoid {@code --bare} so the CLI can pick up its own
 * subscription credentials.</p>
 *
 * @param enabled         gate for production wiring; {@link
 *                        CodingBackendBootstrap} skips registration when
 *                        this is false.
 * @param executablePath  path / PATH lookup for the {@code claude} binary.
 * @param model           model alias to pass via {@code --model} (e.g.
 *                        {@code "sonnet"}, {@code "opus"}, or a fully
 *                        qualified {@code claude-sonnet-4-20250514}).
 * @param useBare         whether to pass {@code --bare} when the resolver
 *                        returns {@link AuthMode.ApiKey}. Always
 *                        {@code true} on ApiKey paths regardless of this
 *                        flag — the field exists so households can opt
 *                        OUT (e.g. for a future contract change). On
 *                        OAuthSession paths {@code --bare} is never set.
 * @param maxWallclock    hard timeout enforced by the subprocess watchdog.
 * @param extraFlags      extra CLI arguments appended to every invocation.
 */
public record ClaudeSdkRuntimeConfig(
    boolean enabled,
    String executablePath,
    String model,
    boolean useBare,
    Duration maxWallclock,
    List<String> extraFlags
) {

    /** Stable name of the typesafe-config root for this backend. */
    public static final String CONFIG_ROOT = "wyrdsekai.coding.backends.claude-sdk";

    /** Default executable name; resolved against PATH. */
    public static final String DEFAULT_EXECUTABLE = "claude";

    /** Default model alias — Sonnet is the broadest-tier choice. */
    public static final String DEFAULT_MODEL = "sonnet";

    /** Default max wallclock per SPEC §9.1. */
    public static final Duration DEFAULT_MAX_WALLCLOCK = Duration.ofMinutes(30);

    public ClaudeSdkRuntimeConfig {
        if (executablePath == null || executablePath.isBlank())
            executablePath = DEFAULT_EXECUTABLE;
        if (model == null || model.isBlank()) model = DEFAULT_MODEL;
        if (maxWallclock == null || maxWallclock.isZero() || maxWallclock.isNegative())
            maxWallclock = DEFAULT_MAX_WALLCLOCK;
        extraFlags = (extraFlags == null) ? List.of() : List.copyOf(extraFlags);
    }

    /** Build a config with all fields defaulted; useful for tests. */
    public static ClaudeSdkRuntimeConfig defaults() {
        return new ClaudeSdkRuntimeConfig(
            false,
            DEFAULT_EXECUTABLE,
            DEFAULT_MODEL,
            true,
            DEFAULT_MAX_WALLCLOCK,
            List.of()
        );
    }

    /**
     * Read the {@code wyrdsekai.coding.backends.claude-sdk} block. Missing
     * keys fall back to the documented defaults rather than throwing.
     * Both snake_case and dash-case keys are accepted.
     */
    public static ClaudeSdkRuntimeConfig fromConfig(Config config) {
        if (config == null || !config.hasPath(CONFIG_ROOT)) {
            return defaults();
        }
        var block = config.getConfig(CONFIG_ROOT);

        boolean enabled = readBool(block, "enabled", false);
        String exec = readString(block, "executable_path",
            readString(block, "executable-path", DEFAULT_EXECUTABLE));
        String model = readString(block, "model", DEFAULT_MODEL);
        boolean useBare = readBool(block, "use_bare",
            readBool(block, "use-bare", true));
        long maxMin = readLong(block, "max_wallclock_min",
            readLong(block, "max-wallclock-min", DEFAULT_MAX_WALLCLOCK.toMinutes()));
        var flags = readStringList(block, "extra_flags");
        if (flags.isEmpty()) flags = readStringList(block, "extra-flags");

        return new ClaudeSdkRuntimeConfig(
            enabled, exec, model, useBare, Duration.ofMinutes(maxMin), flags);
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
