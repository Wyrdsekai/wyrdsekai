package org.wyrdsekai.core.economy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Set;

/**
 * Graduated stewardship visibility grant (§99.4).
 * The elderly person (or any steward) deliberately and revocably grants
 * a family member visibility into selected data categories.
 * <p>
 * Privacy is non-negotiable — conversation content and emotional state
 * are NEVER shared by default.
 */
public record VisibilityGrant(
    @JsonProperty("grantorDid") String grantorDid,
    @JsonProperty("granteeDid") String granteeDid,
    @JsonProperty("categories") Set<String> categories,
    @JsonProperty("alertsOnly") boolean alertsOnly,
    @JsonProperty("granted") Instant granted,
    @JsonProperty("expires") Instant expires,
    @JsonProperty("active") boolean active
) {

    @JsonCreator
    public VisibilityGrant {}

    /** Standard categories that CAN be shared. */
    public static final String CAT_MEDICAL_MEDICATION = "medical-medication";
    public static final String CAT_MEDICAL_APPOINTMENT = "medical-appointment";
    public static final String CAT_EMERGENCY_ALERT = "emergency-alert";
    public static final String CAT_SPENDING_SUMMARY = "spending-summary";
    public static final String CAT_COMPANION_WELLNESS = "companion-wellness";

    /** Categories that are NEVER shared, regardless of grant. */
    public static final Set<String> NEVER_SHARED = Set.of(
        "conversation-content",
        "social-interactions",
        "browsing-history",
        "emotional-state",
        "private-reflections",
        "journal-private"
    );

    /** Check if this grant is currently valid. */
    public boolean isValid() {
        if (!active) return false;
        if (expires != null && Instant.now().isAfter(expires)) return false;
        return true;
    }

    /** Check if a specific category is visible under this grant. */
    public boolean isVisible(String category) {
        if (!isValid()) return false;
        if (NEVER_SHARED.contains(category)) return false;
        return categories.contains(category);
    }

    /** Create a grant with common medical categories. */
    public static VisibilityGrant medical(String grantorDid, String granteeDid) {
        return new VisibilityGrant(grantorDid, granteeDid,
            Set.of(CAT_MEDICAL_MEDICATION, CAT_MEDICAL_APPOINTMENT, CAT_EMERGENCY_ALERT),
            false, Instant.now(), null, true);
    }

    /** Create an alerts-only grant (notify on anomalies, not routine). */
    public static VisibilityGrant alertsOnly(String grantorDid, String granteeDid) {
        return new VisibilityGrant(grantorDid, granteeDid,
            Set.of(CAT_EMERGENCY_ALERT, CAT_COMPANION_WELLNESS),
            true, Instant.now(), null, true);
    }

    /** Revoke this grant. */
    public VisibilityGrant revoke() {
        return new VisibilityGrant(grantorDid, granteeDid, categories,
            alertsOnly, granted, expires, false);
    }
}
