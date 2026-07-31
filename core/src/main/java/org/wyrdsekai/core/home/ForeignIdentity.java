package org.wyrdsekai.core.home;

import java.time.Instant;

/**
 * A verified visitor who arrived via a signed transit token.
 *
 * <p>. Not a local account (no password hash, no login)
 * not residency (can't land in Study, can't be granted without steward action).
 * Just a stable record of "this DID, rooted at {@code homeZone}, has visited us
 * through a verified channel." Used for cross-zone tell routing, bond tracking,
 * and visitor roster.</p>
 *
 * <p>A {@code ForeignIdentity} is never promoted to a {@code users} row —
 * local accounts exist only via direct registration or invite redemption. The
 * distinction is load-bearing: it's how a traveler can visit a zone repeatedly
 * without the zone ever minting a zone-local password, UUID, or residency.</p>
 */
public record ForeignIdentity(
    String did,            // canonical "<homeZone>:<uuid>" (future: did:wyrd:…)
    String homeZone,
    String displayName,
    Instant firstSeenAt,
    Instant lastSeenAt,
    String lastTokenId
) {
    public ForeignIdentity {
        if (did == null || did.isBlank()) {
            throw new IllegalArgumentException("did required");
        }
        if (homeZone == null || homeZone.isBlank()) {
            throw new IllegalArgumentException("homeZone required");
        }
        if (firstSeenAt == null) firstSeenAt = Instant.now();
        if (lastSeenAt == null) lastSeenAt = firstSeenAt;
    }

    /** Build the canonical DID for a traveler, given their source zone + id. */
    public static String canonicalDid(String homeZone, String agentId) {
        return homeZone + ":" + agentId;
    }
}
