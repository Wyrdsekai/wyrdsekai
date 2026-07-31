package org.wyrdsekai.core.coding;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

import java.time.Duration;
import java.util.List;

/**
 * Runtime configuration for the {@link GeminiCliBackend}.
 *
 * <p>Loaded from the typesafe-config block at
 * {@code wyrdsekai.coding.backends.gemini-cli.*} (
 * §9.1, Phase 2e). The Gemini CLI ships as the npm package
 * {@code @google/gemini-cli}; minimum version pinned at 0.40.1 (CVSS-10
 * RCE floor, 2026-04-30).</p>
 *
 * <p><b>Auth (May 2026)</b>: API-key only for headless hosts. The
 * upstream OAuth flow is browser-only as of May 2026 (issue
 * google-gemini/gemini-cli#1696 unresolved); the resolver SHOULD return
 * {@link AuthMode.ApiKey} or {@link AuthMode.AuthMissing}, never
 * {@link AuthMode.OAuthSession}, but the adapter handles all three
 * defensively.</p>
 *
 * <p><b>Workspace trust (post-RCE)</b>: headless mode requires explicit
 * workspace trust. The {@link #trustWorkspace} flag controls whether the
 * adapter passes {@code --trust} (current upstream accept-trust mechanism)
 * — defaults to false so households opt in deliberately.</p>
 *
 * @param enabled         gate for production wiring.
 * @param executablePath  path / PATH lookup for the {@code gemini} binary.
 * @param model           Gemini model id ({@code "gemini-2.5-flash"},
 *                        {@code "gemini-2.5-pro"}, etc.).
 * @param temperature     sampling temperature (0.0 — 2.0); negative means
 *                        "use upstream default" (no flag emitted).
 * @param trustWorkspace  emit {@code --trust} so headless mode accepts
 *                        the workspace folder. Households opt in
 *                        deliberately.
 * @param maxWallclock    hard timeout enforced by the subprocess watchdog.
 * @param extraFlags      extra CLI arguments appended to every invocation.
 */
public record GeminiCliRuntimeConfig(
    boolean enabled,
    String executablePath,
    String model,
    double temperature,
    boolean trustWorkspace,
    Duration maxWallclock,
    List<String> extraFlags
) {

    /** Stable name of the typesafe-config root for this backend. */
    public static final String CONFIG_ROOT = "wyrdsekai.coding.backends.gemini-cli";

    /** Default executable name; resolved against PATH. */
    public static final String DEFAULT_EXECUTABLE = "gemini";

    /** Default model. */
    public static final String DEFAULT_MODEL = "gemini-2.5-flash";

    /** Sentinel "use upstream default" temperature. */
    public static final double DEFAULT_TEMPERATURE = -1.0;

    /** Default max wallclock per SPEC §9.1. */
    public static final Duration DEFAULT_MAX_WALLCLOCK = Duration.ofMinutes(30);

    public GeminiCliRuntimeConfig {
        if (executablePath == null || executablePath.isBlank())
            executablePath = DEFAULT_EXECUTABLE;
        if (model == null || model.isBlank()) model = DEFAULT_MODEL;
        if (maxWallclock == null || maxWallclock.isZero() || maxWallclock.isNegative())
            maxWallclock = DEFAULT_MAX_WALLCLOCK;
        extraFlags = (extraFlags == null) ? List.of() : List.copyOf(extraFlags);
    }

    /** Build a config with all fields defaulted; useful for tests. */
    public static GeminiCliRuntimeConfig defaults() {
        return new GeminiCliRuntimeConfig(
            false,
            DEFAULT_EXECUTABLE,
            DEFAULT_MODEL,
            DEFAULT_TEMPERATURE,
            false,
            DEFAULT_MAX_WALLCLOCK,
            List.of()
        );
    }

    /**
     * Read the {@code wyrdsekai.coding.backends.gemini-cli} block. Missing
     * keys fall back to the documented defaults rather than throwing.
     */
    public static GeminiCliRuntimeConfig fromConfig(Config config) {
        if (config == null || !config.hasPath(CONFIG_ROOT)) {
            return defaults();
        }
        var block = config.getConfig(CONFIG_ROOT);

        boolean enabled = readBool(block, "enabled", false);
        String exec = readString(block, "executable_path",
            readString(block, "executable-path", DEFAULT_EXECUTABLE));
        String model = readString(block, "model", DEFAULT_MODEL);
        double temperature = readDouble(block, "temperature", DEFAULT_TEMPERATURE);
        boolean trustWorkspace = readBool(block, "trust_workspace",
            readBool(block, "trust-workspace", false));
        long maxMin = readLong(block, "max_wallclock_min",
            readLong(block, "max-wallclock-min", DEFAULT_MAX_WALLCLOCK.toMinutes()));
        var flags = readStringList(block, "extra_flags");
        if (flags.isEmpty()) flags = readStringList(block, "extra-flags");

        return new GeminiCliRuntimeConfig(
            enabled, exec, model, temperature, trustWorkspace,
            Duration.ofMinutes(maxMin), flags);
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

    private static double readDouble(Config c, String key, double fallback) {
        try {
            return c.hasPath(key) ? c.getDouble(key) : fallback;
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
