package org.wyrdsekai.core.parlor;

/**
 * Trust tier for a speaker in a Parlor context.
 *
 * <p>Rate limits are trust-scaled — strangers are throttled aggressively,
 * contacts flow near-freely, residents/family without limits. The tier is
 * computed by the caller based on federation state, bond depth, and
 * contact book; this enum is the input to per-tier parlor rate limiting.</p>
 */
public enum TrustTier {
    /**
     * No prior relationship — stranger in a Public Parlor with no contract.
     * Rate-limited aggressively to deter spam (say 1/30s, tell/whisper 1/60s,
     * emote 1/60s per spec §6.10).
     */
    UNKNOWN,

    /**
     * Has a contract at tourist tier — federated visitor with light
     * permissions. Throttled but usable (say/tell/whisper 1/3s, emote 1/5s).
     */
    CONTACT,

    /**
     * Resident, family, or long-standing bond — no per-utterance limits.
     * Still subject to dedup + length cap (§6.10 Supplementary mechanics)
     * so a resident can't spam-paste either.
     */
    RESIDENT
}
