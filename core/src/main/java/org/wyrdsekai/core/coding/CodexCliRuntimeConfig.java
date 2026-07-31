package org.wyrdsekai.core.coding;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

import java.time.Duration;
import java.util.List;

/**
 * Runtime configuration for the {@link CodexCliBackend}.
 *
 * <p>Loaded from the typesafe-config block at
 * {@code wyrdsekai.coding.backends.codex.*} (
 * §9.1, Phase 2e). The Codex CLI is a Rust binary distributed via
 * GitHub releases under {@code openai/codex}. Headless mode:
 * {@code codex exec "<prompt>" --json} (NOT {@code codex run}).</p>
 *
 * <p><b>Auth (May 2026)</b>: dual-path — OAuth via
 * {@code codex login --device-auth} (paste-this-URL flow, headless
 * supported) leaves credentials at {@code ~/.codex/auth.json};
 * API-key fallback is the standard {@code OPENAI_API_KEY} env var.
 * The {@code CODEX_API_KEY} alternate is also honoured by upstream
 * but only inside {@code codex exec}; we set both for robustness.</p>
 *
 * @param enabled         gate for production wiring; {@link
 *                        CodingBackendBootstrap} skips registration when
 *                        this is false.
 * @param executablePath  path / PATH lookup for the {@code codex} binary.
 * @param provider        codex {@code --provider} value when set;
 *                        {@code null} lets codex pick its default.
 * @param wallclockMin    hard timeout enforced by the subprocess
 *                        watchdog. SPEC §9.1 default: 30 minutes.
 * @param extraFlags      extra CLI arguments appended to every
 *                        {@code codex exec} invocation.
 */
public record CodexCliRuntimeConfig(
    boolean enabled,
    String executablePath,
    String provider,
    int wallclockMin,
    List<String> extraFlags
) {

    /** Stable name of the typesafe-config root for this backend. */
    public static final String CONFIG_ROOT = "wyrdsekai.coding.backends.codex";

    /** Default executable name; resolved against PATH. */
    public static final String DEFAULT_EXECUTABLE = "codex";

    /** Default provider — null means "let codex pick its default". */
    public static final String DEFAULT_PROVIDER = null;

    /** Default max wallclock per SPEC §9.1. */
    public static final int DEFAULT_WALLCLOCK_MIN = 30;

    public CodexCliRuntimeConfig {
        if (executablePath == null || executablePath.isBlank())
            executablePath = DEFAULT_EXECUTABLE;
        // provider allowed to be null
        if (wallclockMin <= 0) wallclockMin = DEFAULT_WALLCLOCK_MIN;
        extraFlags = (extraFlags == null) ? List.of() : List.copyOf(extraFlags);
    }

    /** Build a config with all fields defaulted; useful for tests. */
    public static CodexCliRuntimeConfig defaults() {
        return new CodexCliRuntimeConfig(
            false,
            DEFAULT_EXECUTABLE,
            DEFAULT_PROVIDER,
            DEFAULT_WALLCLOCK_MIN,
            List.of()
        );
    }

    /** Convenience accessor — wallclock cap as a {@link Duration}. */
    public Duration maxWallclock() {
        return Duration.ofMinutes(wallclockMin);
    }

    /**
     * Read the {@code wyrdsekai.coding.backends.codex} block. Missing
     * keys fall back to the documented defaults rather than throwing.
     * Both snake_case and dash-case keys are accepted.
     */
    public static CodexCliRuntimeConfig fromConfig(Config config) {
        if (config == null || !config.hasPath(CONFIG_ROOT)) {
            return defaults();
        }
        var block = config.getConfig(CONFIG_ROOT);

        boolean enabled = readBool(block, "enabled", false);
        String exec = readString(block, "executable_path",
            readString(block, "executable-path", DEFAULT_EXECUTABLE));
        String provider = readStringOrNull(block, "provider");
        int wallclock = (int) readLong(block, "wallclock_min",
            readLong(block, "wallclock-min",
              readLong(block, "max_wallclock_min",
                readLong(block, "max-wallclock-min", DEFAULT_WALLCLOCK_MIN))));
        var flags = readStringList(block, "extra_flags");
        if (flags.isEmpty()) flags = readStringList(block, "extra-flags");

        return new CodexCliRuntimeConfig(
            enabled, exec, provider, wallclock, flags);
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
            String v = c.getString(key);
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
