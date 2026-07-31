package org.wyrdsekai.core.coding;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

import java.time.Duration;
import java.util.List;

/**
 * Runtime configuration for the {@link ClineBackend}.
 *
 * <p>Loaded from the typesafe-config block at
 * {@code wyrdsekai.coding.backends.cline.*} (
 * §9.1). Cline 2.18+ is npm-distributed (npm package {@code cline});
 * argv shape is {@code cline --json "<task>"} (or {@code cline -y
 * "<task>"} for yolo mode). Auth: pre-stage via {@code cline auth -p
 * <provider> -k <key>} or set the standard provider env var
 * (ANTHROPIC_API_KEY / OPENAI_API_KEY / etc.) keyed off
 * {@code coding.backends.cline.provider}.</p>
 *
 * @param enabled         gate for production wiring; {@link
 *                        CodingBackendBootstrap} skips registration when
 *                        this is false.
 * @param executablePath  path / PATH lookup for the {@code cline} binary.
 * @param provider        upstream provider name ({@code anthropic},
 *                        {@code openai}, {@code google}, {@code local}).
 *                        Drives env-var routing
 *                        ({@link ClineBackend#providerKeyEnvVarFor}).
 *                        Not forwarded as a CLI flag (no such flag
 *                        upstream).
 * @param yolo            when {@code true}, invoke {@code cline -y
 *                        "<task>"} (auto-approve all steps); otherwise
 *                        {@code cline --json "<task>"} (structured event
 *                        stream). SPEC §9.1 default: false.
 * @param maxWallclock    hard timeout enforced by the subprocess
 *                        watchdog. SPEC §9.1 default: 30 minutes.
 * @param extraFlags      extra CLI arguments appended to every
 *                        invocation.
 */
public record ClineRuntimeConfig(
    boolean enabled,
    String executablePath,
    String provider,
    boolean yolo,
    Duration maxWallclock,
    List<String> extraFlags
) {

    /** Stable name of the typesafe-config root for this backend. */
    public static final String CONFIG_ROOT = "wyrdsekai.coding.backends.cline";

    /** Default executable name; resolved against PATH. */
    public static final String DEFAULT_EXECUTABLE = "cline";

    /**
     * Default provider — null means "let Cline pick from its own auth
     * config". When {@code cline auth -p <provider>} pre-stages
     * credentials, the CLI knows which provider to target without our
     * env-var routing.
     */
    public static final String DEFAULT_PROVIDER = null;

    /** Default max wallclock per SPEC §9.1. */
    public static final Duration DEFAULT_MAX_WALLCLOCK = Duration.ofMinutes(30);

    public ClineRuntimeConfig {
        if (executablePath == null || executablePath.isBlank())
            executablePath = DEFAULT_EXECUTABLE;
        // provider is allowed to be null — see DEFAULT_PROVIDER comment.
        if (maxWallclock == null || maxWallclock.isZero() || maxWallclock.isNegative())
            maxWallclock = DEFAULT_MAX_WALLCLOCK;
        extraFlags = (extraFlags == null) ? List.of() : List.copyOf(extraFlags);
    }

    /** Build a config with all fields defaulted; useful for tests. */
    public static ClineRuntimeConfig defaults() {
        return new ClineRuntimeConfig(
            false,
            DEFAULT_EXECUTABLE,
            DEFAULT_PROVIDER,
            false,
            DEFAULT_MAX_WALLCLOCK,
            List.of()
        );
    }

    /**
     * Read the {@code wyrdsekai.coding.backends.cline} block. Missing
     * keys fall back to the documented defaults rather than throwing.
     * Both snake_case and dash-case keys are accepted.
     */
    public static ClineRuntimeConfig fromConfig(Config config) {
        if (config == null || !config.hasPath(CONFIG_ROOT)) {
            return defaults();
        }
        var block = config.getConfig(CONFIG_ROOT);

        boolean enabled = readBool(block, "enabled", false);
        String exec = readString(block, "executable_path",
            readString(block, "executable-path", DEFAULT_EXECUTABLE));
        String provider = readStringOrNull(block, "provider");
        boolean yolo = readBool(block, "yolo", false);
        long maxMin = readLong(block, "max_wallclock_min",
            readLong(block, "max-wallclock-min", DEFAULT_MAX_WALLCLOCK.toMinutes()));
        var flags = readStringList(block, "extra_flags");
        if (flags.isEmpty()) flags = readStringList(block, "extra-flags");

        return new ClineRuntimeConfig(
            enabled, exec, provider, yolo, Duration.ofMinutes(maxMin), flags);
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
