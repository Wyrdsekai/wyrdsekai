package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Consent record for soul inspection by other entities.
 * The agent controls who can see what of its soul.
 *
 * @param ownerDid    DID of the soul's owner
 * @param requesterDid DID of the requester ("*" for public)
 * @param level       What they can see
 * @param grantedAt   When granted
 * @param expiresAt   When it expires (null = permanent)
 */
public record SoulConsent(
    @JsonProperty("ownerDid") String ownerDid,
    @JsonProperty("requesterDid") String requesterDid,
    @JsonProperty("level") ConsentLevel level,
    @JsonProperty("grantedAt") Instant grantedAt,
    @JsonProperty("expiresAt") Instant expiresAt
) {
    @JsonCreator
    public SoulConsent {}

    public enum ConsentLevel {
        DENY,
        PUBLIC_PROFILE,   // Layer A only
        PARTIAL,          // Layers A + B (relationships)
        FULL              // Layers A + B + C (behavioral trace)
    }

    /** Check if this consent is currently valid. */
    public boolean isValid() {
        return expiresAt == null || Instant.now().isBefore(expiresAt);
    }

    /** Check if this consent covers the given requester. */
    public boolean covers(String requesterDid) {
        return "*".equals(this.requesterDid) || this.requesterDid.equals(requesterDid);
    }
}
