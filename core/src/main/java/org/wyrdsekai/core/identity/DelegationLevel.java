package org.wyrdsekai.core.identity;

/**
 * Delegation authority level from principal to agent (§85.1).
 */
public enum DelegationLevel {
    /** Full authority — agent can do everything the principal can. */
    FULL,
    /** Read-only — agent can observe but not modify. */
    READ_ONLY,
    /** No delegation — agent has no inherited authority. */
    NONE
}
