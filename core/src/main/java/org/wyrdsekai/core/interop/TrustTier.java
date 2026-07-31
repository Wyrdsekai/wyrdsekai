package org.wyrdsekai.core.interop;

/**
 * Trust tiers for A2A interactions (§97.9).
 * Determines what extensions and data an external agent can access.
 */
public enum TrustTier {
    /** Unknown A2A agent, no prior contact. Core A2A only. */
    ANONYMOUS(0),
    /** Known DID, valid Agent Card signature. Read-only soul-identity. */
    VERIFIED(1),
    /** Explicitly whitelisted by steward. Soul exchange (quarantined), signed messages. */
    TRUSTED(2),
    /** Agent on same Between mesh. Full access minus family-specific. */
    HOUSEHOLD(3),
    /** Same-lineage bud, valid lineage chain. Everything including argot. */
    FAMILY(4);

    private final int level;

    TrustTier(int level) { this.level = level; }

    public int level() { return level; }

    /** Check if this tier meets or exceeds the required tier. */
    public boolean meetsOrExceeds(TrustTier required) {
        return this.level >= required.level;
    }

    /** Whether this tier can access soul exchange extensions. */
    public boolean canExchangeSoulItems() {
        return this.level >= TRUSTED.level;
    }

    /** Whether this tier can see raw vitality data. */
    public boolean canSeeRawVitality() {
        return this == FAMILY;
    }

    /** Whether this tier can access family argot. */
    public boolean canUseArgot() {
        return this == FAMILY;
    }

    /** Whether this tier bypasses dock quarantine for items. */
    public boolean bypassesQuarantine() {
        return this == FAMILY; // Family uses locker, not A2A
    }
}
