package org.wyrdsekai.core.coding;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

import java.time.Duration;

/**
 * Runtime configuration for the {@link DevinBackend}.
 *
 * <p>Loaded from the typesafe-config block at
 * {@code wyrdsekai.coding.backends.devin.*} (
 * §9.1, Phase 2e). Devin is the async-cloud (shape #3) outlier in the
 * Phase 2 backend set: no local binary, REST API only, sessions can run
 * for hours and routinely cost real $$.</p>
 *
 * <p><b>Endpoint</b>: {@code POST {api_base}/v3/organizations/{org_id}/sessions}
 * to start; {@code GET {api_base}/v3/organizations/{org_id}/sessions/{session_id}}
 * to poll. v1/v2 are deprecated but still functional.</p>
 *
 * <p><b>Auth</b>: API-key only ({@code Authorization: Bearer $DEVIN_API_KEY});
 * no OAuth path. The resolver returns {@link AuthMode.ApiKey} or
 * {@link AuthMode.AuthMissing}.</p>
 *
 * @param enabled            gate for production wiring; {@link
 *                           CodingBackendBootstrap} skips registration when
 *                           this is false.
 * @param orgId              required organisation ID Devin partitions
 *                           sessions under (no smart default — must be
 *                           configured by the steward).
 * @param apiBase            base URL for the Devin REST API. Defaults to
 *                           the public endpoint.
 * @param pollIntervalSec    polling interval for session status, in
 *                           seconds. Adapter applies exponential backoff
 *                           starting from this baseline.
 * @param maxWallclockHours  hard cap on the total wall-clock time the
 *                           adapter will wait for a session to settle.
 *                           Default 4 hours per SPEC §6 Q2 caution; can
 *                           be tuned per household.
 * @param requestTimeout     HTTP request timeout for individual REST
 *                           calls. Cap on how long any one call
 *                           (create-session, poll, abort) may stall.
 */
public record DevinRuntimeConfig(
    boolean enabled,
    String orgId,
    String apiBase,
    int pollIntervalSec,
    int maxWallclockHours,
    Duration requestTimeout
) {

    /** Stable name of the typesafe-config root for this backend. */
    public static final String CONFIG_ROOT = "wyrdsekai.coding.backends.devin";

    /** Default Devin API base URL. */
    public static final String DEFAULT_API_BASE = "https://api.devin.ai";

    /** Default polling interval (seconds). */
    public static final int DEFAULT_POLL_INTERVAL_SEC = 10;

    /** Default total wallclock cap in hours. */
    public static final int DEFAULT_MAX_WALLCLOCK_HOURS = 4;

    /** Default per-request timeout. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    public DevinRuntimeConfig {
        if (apiBase == null || apiBase.isBlank()) apiBase = DEFAULT_API_BASE;
        if (pollIntervalSec <= 0) pollIntervalSec = DEFAULT_POLL_INTERVAL_SEC;
        if (maxWallclockHours <= 0) maxWallclockHours = DEFAULT_MAX_WALLCLOCK_HOURS;
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative())
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        // orgId stays nullable — submitTask surfaces a clear error if missing.
    }

    /** Build a config with all fields defaulted; useful for tests. */
    public static DevinRuntimeConfig defaults() {
        return new DevinRuntimeConfig(
            false,
            null,
            DEFAULT_API_BASE,
            DEFAULT_POLL_INTERVAL_SEC,
            DEFAULT_MAX_WALLCLOCK_HOURS,
            DEFAULT_REQUEST_TIMEOUT
        );
    }

    /** Convenience accessor — total wallclock cap as a {@link Duration}. */
    public Duration maxWallclock() {
        return Duration.ofHours(maxWallclockHours);
    }

    /** Convenience accessor — initial poll interval as a {@link Duration}. */
    public Duration pollInterval() {
        return Duration.ofSeconds(pollIntervalSec);
    }

    /**
     * Read the {@code wyrdsekai.coding.backends.devin} block. Missing
     * keys fall back to the documented defaults rather than throwing.
     * Both snake_case and dash-case keys are accepted.
     */
    public static DevinRuntimeConfig fromConfig(Config config) {
        if (config == null || !config.hasPath(CONFIG_ROOT)) {
            return defaults();
        }
        var block = config.getConfig(CONFIG_ROOT);

        boolean enabled = readBool(block, "enabled", false);
        String orgId = readStringOrNull(block, "org_id");
        if (orgId == null) orgId = readStringOrNull(block, "org-id");
        String apiBase = readString(block, "api_base",
            readString(block, "api-base", DEFAULT_API_BASE));
        int pollSec = (int) readLong(block, "poll_interval_sec",
            readLong(block, "poll-interval-sec", DEFAULT_POLL_INTERVAL_SEC));
        int maxHours = (int) readLong(block, "max_wallclock_hours",
            readLong(block, "max-wallclock-hours", DEFAULT_MAX_WALLCLOCK_HOURS));
        long timeoutSec = readLong(block, "request_timeout_sec",
            readLong(block, "request-timeout-sec", DEFAULT_REQUEST_TIMEOUT.getSeconds()));

        return new DevinRuntimeConfig(
            enabled, orgId, apiBase, pollSec, maxHours,
            Duration.ofSeconds(timeoutSec));
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
}
