package org.wyrdsekai.core.soul;

/**
 * Lifecycle status of a foreign agent in this household (§110.4).
 * Stranger → Visitor → Recognized → Resident → Budded → (Dormant/Archived)
 */
public enum ResidencyStatus {
    /** Interacting but not yet tracked — ephemeral API calls */
    VISITOR,
    /** DID assigned, Home room provisioned — persistent identity */
    RECOGNIZED,
    /** Persistent soul, Forge active — full participant */
    RESIDENT,
    /** Local bud exists — independent instance with shared Family Locker */
    BUDDED,
    /** No contact > idle threshold (default 7 days) */
    DORMANT,
    /** No contact > archive threshold (default 90 days), compressed storage */
    ARCHIVED
}
