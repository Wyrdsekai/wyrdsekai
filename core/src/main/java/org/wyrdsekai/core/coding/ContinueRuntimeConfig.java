package org.wyrdsekai.core.coding;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

import java.time.Duration;
import java.util.List;

/**
 * Runtime configuration for the {@link ContinueBackend}.
 *
 * <p>Loaded from the typesafe-config block at
 * {@code wyrdsekai.coding.backends.continue.*} (
 * §9.1). Continue is edit-flow flavored — its differentiator is named
 * reusable agents (defined in the household's Continue Hub config). The
 * subprocess shape is {@code cn run --message='<spec>' --headless
 * [--agent <name>]}. Auth is dual-path: OAuth via {@code cn login} (Hub
 * SSO) plus {@code CONTINUE_API_KEY} fallback.</p>
 *
 * @param enabled         gate for production wiring.
 * @param executablePath  path / PATH lookup for the {@code cn} binary.
 * @param agent           named Continue agent to invoke ({@code --agent
 *                        <name>}). When null/blank the subprocess uses
 *                        Continue's default agent.
 * @param maxWallclock    hard timeout enforced by the subprocess watchdog.
 * @param extraFlags      extra CLI arguments appended to every
 *                        {@code cn run} invocation.
 */
public record ContinueRuntimeConfig(
    boolean enabled,
    String executablePath,
    String agent,
    Duration maxWallclock,
    List<String> extraFlags
) {

    /** Stable name of the typesafe-config root for this backend. */
    public static final String CONFIG_ROOT = "wyrdsekai.coding.backends.continue";

    /** Default executable name; resolved against PATH. */
    public static final String DEFAULT_EXECUTABLE = "cn";

    /** Default agent — null means "Continue's built-in default agent". */
    public static final String DEFAULT_AGENT = null;

    /** Default max wallclock per SPEC §9.1. */
    public static final Duration DEFAULT_MAX_WALLCLOCK = Duration.ofMinutes(30);

    public ContinueRuntimeConfig {
        if (executablePath == null || executablePath.isBlank())
            executablePath = DEFAULT_EXECUTABLE;
        // agent allowed to be null
        if (maxWallclock == null || maxWallclock.isZero() || maxWallclock.isNegative())
            maxWallclock = DEFAULT_MAX_WALLCLOCK;
        extraFlags = (extraFlags == null) ? List.of() : List.copyOf(extraFlags);
    }

    /** Build a config with all fields defaulted; useful for tests. */
    public static ContinueRuntimeConfig defaults() {
        return new ContinueRuntimeConfig(
            false,
            DEFAULT_EXECUTABLE,
            DEFAULT_AGENT,
            DEFAULT_MAX_WALLCLOCK,
            List.of()
        );
    }

    /**
     * Read the {@code wyrdsekai.coding.backends.continue} block. Missing
     * keys fall back to the documented defaults rather than throwing.
     * Both snake_case and dash-case keys are accepted.
     */
    public static ContinueRuntimeConfig fromConfig(Config config) {
        if (config == null || !config.hasPath(CONFIG_ROOT)) {
            return defaults();
        }
        var block = config.getConfig(CONFIG_ROOT);

        boolean enabled = readBool(block, "enabled", false);
        String exec = readString(block, "executable_path",
            readString(block, "executable-path", DEFAULT_EXECUTABLE));
        String agent = readStringOrNull(block, "agent");
        long maxMin = readLong(block, "max_wallclock_min",
            readLong(block, "max-wallclock-min", DEFAULT_MAX_WALLCLOCK.toMinutes()));
        var flags = readStringList(block, "extra_flags");
        if (flags.isEmpty()) flags = readStringList(block, "extra-flags");

        return new ContinueRuntimeConfig(
            enabled, exec, agent, Duration.ofMinutes(maxMin), flags);
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
