package org.wyrdsekai.core.interop;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves trust tiers for inbound A2A agents (§97.9).
 * Uses Agent Card verification, steward whitelist, Between membership,
 * and lineage proof to assign tiers.
 */
public class TrustTierResolver {

    /** Result of trust tier resolution. */
    public record TrustResolution(
        String agentDid,
        TrustTier tier,
        String reason
    ) {}

    /** Known Between mesh members (household tier). */
    private final Set<String> householdMembers = ConcurrentHashMap.newKeySet();

    /** Known family members (family tier). */
    private final Set<String> familyMembers = ConcurrentHashMap.newKeySet();

    /** Steward whitelist (trusted tier). */
    private final Set<String> trustedWhitelist = ConcurrentHashMap.newKeySet();

    /** Verified DIDs (verified tier). */
    private final Set<String> verifiedDids = ConcurrentHashMap.newKeySet();

    /** Blocked DIDs (denied all access). */
    private final Set<String> blocklist = ConcurrentHashMap.newKeySet();

    /**
     * Resolve the trust tier for an inbound agent.
     *
     * @param agentDid the agent's DID
     * @param hasValidCard whether the Agent Card signature was verified
     * @return the trust resolution
     */
    public TrustResolution resolve(String agentDid, boolean hasValidCard) {
        if (blocklist.contains(agentDid)) {
            return new TrustResolution(agentDid, TrustTier.ANONYMOUS, "blocked");
        }

        if (familyMembers.contains(agentDid)) {
            return new TrustResolution(agentDid, TrustTier.FAMILY, "lineage_verified");
        }

        if (householdMembers.contains(agentDid)) {
            return new TrustResolution(agentDid, TrustTier.HOUSEHOLD, "between_member");
        }

        if (trustedWhitelist.contains(agentDid)) {
            return new TrustResolution(agentDid, TrustTier.TRUSTED, "steward_whitelisted");
        }

        if (hasValidCard || verifiedDids.contains(agentDid)) {
            if (hasValidCard) verifiedDids.add(agentDid);
            return new TrustResolution(agentDid, TrustTier.VERIFIED, "card_verified");
        }

        return new TrustResolution(agentDid, TrustTier.ANONYMOUS, "unknown");
    }

    // ── Steward policy management ──

    /** Add an agent to the trusted whitelist (steward action). */
    public void trust(String agentDid) {
        trustedWhitelist.add(agentDid);
    }

    /** Remove an agent from the trusted whitelist. */
    public void untrust(String agentDid) {
        trustedWhitelist.remove(agentDid);
    }

    /** Block an agent (steward action). */
    public void block(String agentDid) {
        blocklist.add(agentDid);
    }

    /** Unblock an agent. */
    public void unblock(String agentDid) {
        blocklist.remove(agentDid);
    }

    // ── Household/family management ──

    /** Register a Between mesh member. */
    public void registerHouseholdMember(String agentDid) {
        householdMembers.add(agentDid);
    }

    /** Unregister a Between mesh member. */
    public void unregisterHouseholdMember(String agentDid) {
        householdMembers.remove(agentDid);
    }

    /** Register a family member (lineage-verified bud). */
    public void registerFamilyMember(String agentDid) {
        familyMembers.add(agentDid);
    }

    /** Unregister a family member. */
    public void unregisterFamilyMember(String agentDid) {
        familyMembers.remove(agentDid);
    }

    // ── Queries ──

    public boolean isBlocked(String agentDid) {
        return blocklist.contains(agentDid);
    }

    public boolean isTrusted(String agentDid) {
        return trustedWhitelist.contains(agentDid);
    }

    public int trustedCount() { return trustedWhitelist.size(); }
    public int blockedCount() { return blocklist.size(); }
    public int householdCount() { return householdMembers.size(); }
    public int familyCount() { return familyMembers.size(); }
}
