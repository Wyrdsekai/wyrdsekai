package org.wyrdsekai.common.home;

import java.time.Duration;
import java.util.Map;

/**
 * Trust tier for cross-zone visitors.
 *
 * <p>Mirrors the bilateral-agreement trust level ("tourist" | "resident" |
 * "citizen") and carries default grant-template parameters — expiry,
 * capability set, scope notes — so approving a cross-zone knock produces a
 * Grant whose shape reflects the agreement's trust relationship without the
 * owner having to spell it out every time.</p>
 */
public enum TrustTier {

    TOURIST(Duration.ofHours(24), Map.of("trustTier", "tourist")),
    RESIDENT(Duration.ofDays(7), Map.of("trustTier", "resident")),
    CITIZEN(null, Map.of("trustTier", "citizen"));  // null = open-ended

    private final Duration defaultTtl;
    private final Map<String, Object> scopeAdditions;

    TrustTier(Duration defaultTtl, Map<String, Object> scopeAdditions) {
        this.defaultTtl = defaultTtl;
        this.scopeAdditions = scopeAdditions;
    }

    /** Default grant lifetime for this tier. {@code null} = no expiry. */
    public Duration defaultTtl() { return defaultTtl; }

    /** Scope keys the template stamps onto every grant issued at this tier. */
    public Map<String, Object> scopeAdditions() { return scopeAdditions; }

    /** Parse a trust-level string (case-insensitive). Unknown → TOURIST (safest). */
    public static TrustTier parse(String raw) {
        if (raw == null) return TOURIST;
        return switch (raw.toLowerCase()) {
            case "resident" -> RESIDENT;
            case "citizen" -> CITIZEN;
            default -> TOURIST;
        };
    }

    /** Name as stored in bilateral_agreements.trust_level. */
    public String label() { return name().toLowerCase(); }
}
