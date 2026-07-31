package org.wyrdsekai.core.naming;

import org.wyrdsekai.core.config.WyrdConfig;

/**
 * Policy for how the federation layer handles envelope signature mismatches
 *
 * <p>Migration path from spec §7:</p>
 * <ol>
 *   <li>Phase 1 ships with {@link #SOFT}: log a WARN on mismatch, continue
 *       dispatch. Lets operators observe signature health across the mesh
 *       without risking a dropped-traffic outage on day-one.</li>
 *   <li>Phase 2 flips the default to {@link #HARD}: drop mismatching
 *       envelopes at intake. Runs after a deprecation window where WARN
 *       rates are monitored — no WARN traffic in logs = safe to flip.</li>
 *   <li>{@link #OFF} exists for tests and for minimal deployments that
 *       can't run verification (no peer manifest available). Never the
 *       default.</li>
 * </ol>
 *
 * <p>Read from the {@code WYRDSEKAI_ENVELOPE_VERIFY} environment variable
 * via {@link #fromEnv()}; defaults to {@link #SOFT}.</p>
 */
public enum EnvelopeVerificationMode {
    /**
     * Don't verify at all. Used by tests that don't set up peer pubkeys,
     * and for single-node deployments where there's no federation to
     * authenticate. Everything is accepted.
     */
    OFF,

    /**
     * Verify signatures when a peer pubkey is known. Log WARN on mismatch
     * but <b>still dispatch</b> the message. Gives operators visibility
     * into how often signatures fail without actually breaking traffic —
     * the Phase-1 default. See the WARN in
     * {@code FederationActor.verifyEnvelope} for the log format.
     */
    SOFT,

    /**
     * Verify signatures when a peer pubkey is known. <b>Drop</b> the
     * message on mismatch; log at INFO to avoid log-flooding on adversarial
     * traffic. Phase 2 default, set this once WARN rates are consistently
     * zero across the mesh.
     */
    HARD;

    /**
     * Resolve from the {@code WYRDSEKAI_ENVELOPE_VERIFY} env var.
     * Case-insensitive; falls back to {@link #SOFT} for unknown/missing
     * values. Callers that want a different default should pass one to
     * {@link #fromString(String, EnvelopeVerificationMode)}.
     */
    public static EnvelopeVerificationMode fromEnv() {
        return fromString(WyrdConfig.get().envelopeVerify(), SOFT);
    }

    /**
     * @param value    env-var style string, case-insensitive, nullable
     * @param fallback returned when {@code value} is null/blank/unknown
     */
    public static EnvelopeVerificationMode fromString(String value, EnvelopeVerificationMode fallback) {
        if (value == null || value.isBlank()) return fallback;
        return switch (value.strip().toLowerCase()) {
            case "off", "disabled", "none" -> OFF;
            case "soft", "warn" -> SOFT;
            case "hard", "strict", "enforce" -> HARD;
            default -> fallback;
        };
    }
}
