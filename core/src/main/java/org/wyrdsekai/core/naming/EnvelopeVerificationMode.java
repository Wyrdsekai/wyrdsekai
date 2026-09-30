package org.wyrdsekai.core.naming;

import org.wyrdsekai.core.config.WyrdConfig;

/**
 * Policy for how signed envelopes that fail verification are handled.
 *
 * <p>{@link #HARD} is the default since 2026-09-28: a forged, unsigned, stale, replayed or
 * unknown-sender envelope is dropped with a WARN. {@link #SOFT} and {@link #OFF} are transition
 * settings for a household still running older machines; both log a WARN at start.</p>
 *
 * <p>Read from the {@code WYRDSEKAI_ENVELOPE_VERIFY} environment variable via {@link #fromEnv()}.</p>
 */
public enum EnvelopeVerificationMode {
    /**
     * Don't verify at all. Used by tests that don't set up peer pubkeys,
     * and for single-node deployments where there's no federation to
     * authenticate. Everything is accepted.
     */
    OFF,

    /**
     * Transition setting: verify, log a WARN on failure, but <b>still dispatch</b> the message.
     */
    SOFT,

    /**
     * The default: <b>drop</b> an envelope that fails verification or comes from an unknown sender,
     * with a WARN.
     */
    HARD;

    /**
     * Resolve from the {@code WYRDSEKAI_ENVELOPE_VERIFY} env var. Case-insensitive; falls back to
     * {@link #HARD} for unknown/missing values.
     */
    public static EnvelopeVerificationMode fromEnv() {
        return fromString(WyrdConfig.get().envelopeVerify(), HARD);
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
