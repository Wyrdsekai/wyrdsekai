package org.wyrdsekai.core.substrate.training;

import org.wyrdsekai.core.config.WyrdConfig;

/**
 * Steward-configurable preference for how training should be allocated.
 *
 * <p>Surfaced in Study config (Scroll of Settings). The selector consults
 * this as a hint — a peer or cloud strategy may still be skipped if no
 * peer/cloud is reachable, in which case the policy degrades gracefully.</p>
 */
public enum UserTrainingPolicy {
    /** Pick the cheapest viable strategy by detection — recommended default. */
    AUTO,
    /** Always train on this host even if a peer would be faster. Sovereign mode. */
    PREFER_LOCAL,
    /** Prefer peer delegation when any peer can handle it; fall back to local. */
    PREFER_PEER,
    /** Never train locally (CPU-class hosts that should always offload). */
    LOCAL_FORBIDDEN,
    /** Use cloud distillation when local can't fit; never offload to peer. */
    PREFER_CLOUD,
    /** Disable training entirely — agent's voice never evolves via this host. */
    DISABLED;

    public static UserTrainingPolicy fromEnv() {
        var v = WyrdConfig.get().trainingPolicy();
        if (v == null || v.isBlank()) return AUTO;
        try { return UserTrainingPolicy.valueOf(v.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return AUTO; }
    }
}
