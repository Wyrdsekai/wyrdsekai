package org.wyrdsekai.core.codemode;

/**
 * Track A Phase 1 — feature flag for {@code run_script}.
 *
 * <p>Two env vars:</p>
 * <ul>
 *   <li>{@code WYRDSEKAI_CODE_MODE_ENABLED} — master gate. Default off in
 *       first release; matches the spec's §12 migration plan.</li>
 *   <li>{@code WYRDSEKAI_CODE_MODE_AUDIT_ONLY} — when {@code true}, scripts
 *       are journalled but not executed. Used during the soak window to
 *       collect a sample of what the 9B actually emits before letting it
 *       run.</li>
 * </ul>
 *
 * <p>Flags are read every call so test harnesses can flip them via
 * {@code System.setProperty} without restart. System properties take
 * precedence over env vars (mirrors the standard Wyrd config-resolution
 * pattern).
 */
public final class CodeModeFeatureFlag {

    public static final String ENABLED_ENV = "WYRDSEKAI_CODE_MODE_ENABLED";
    public static final String AUDIT_ONLY_ENV = "WYRDSEKAI_CODE_MODE_AUDIT_ONLY";

    /**
     * / Track A Phase 2b — second feature flag for
     * <em>free-form</em> code-mode (prose + ```js block in a single response).
     *
     * <p>Both {@link #ENABLED_ENV} <em>and</em> this flag must be true for the
     * free-form prompt-shape to fire. Default off so a soak window can run with
     * just the {@code run_script} tool path (Phase 1) before the more invasive
     * shape lands. The {@code run_script} surface stays operational regardless.
     */
    public static final String IMPROV_ENV = "WYRDSEKAI_CODE_MODE_IMPROV";

    private CodeModeFeatureFlag() {}

    public static boolean isEnabled() {
        return resolveBool(ENABLED_ENV, false);
    }

    public static boolean isAuditOnly() {
        return resolveBool(AUDIT_ONLY_ENV, false);
    }

    /**
     * Track A Phase 2b — true iff <em>both</em> the master
     * gate {@link #isEnabled()} AND the improvisation flag are on. The two-flag
     * design lets the operator opt into the Phase 1 tool surface without also
     * accepting the more invasive prose+JS prompt shape.
     */
    public static boolean isImprovisationEnabled() {
        return isEnabled() && resolveBool(IMPROV_ENV, false);
    }

    private static boolean resolveBool(String key, boolean fallback) {
        var prop = System.getProperty(key);
        if (prop == null || prop.isBlank()) {
            prop = System.getenv(key);
        }
        if (prop == null || prop.isBlank()) return fallback;
        return "true".equalsIgnoreCase(prop.trim()) || "1".equals(prop.trim());
    }
}
