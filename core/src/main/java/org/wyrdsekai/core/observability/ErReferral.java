package org.wyrdsekai.core.observability;

import java.time.Instant;
import java.util.*;

/**
 * Zone-to-zone ER referral (§105.7).
 * When local ER can't handle a problem, refer to a specialist zone.
 */
public class ErReferral {

    /** A referral to an external ER zone. */
    public record Referral(
        String referralId,
        String agentDid,
        String sourceZone,
        String targetZone,
        ReferralType type,
        ReferralStatus status,
        Instant createdAt,
        String reason,
        String recoveryPlan
    ) {}

    public enum ReferralType {
        /** Send diagnostic data, receive recovery plan. */
        REMOTE_DIAGNOSIS,
        /** Transfer bud to remote zone for in-patient care. */
        IN_PATIENT_TRANSFER
    }

    public enum ReferralStatus {
        INITIATED, STATE_TRANSFERRING, DIAGNOSING, PLAN_RECEIVED,
        PLAN_APPLIED, ADMITTED, RECOVERING, DISCHARGED, FAILED
    }

    /** An ER service zone discovered via A2A. */
    public record ErServiceZone(
        String zoneId,
        String displayName,
        boolean codeplaneBacked,
        List<String> capabilities,
        double reputationScore,
        Map<String, Double> pricing
    ) {}

    private final Map<String, Referral> referrals = new LinkedHashMap<>();
    private final List<ErServiceZone> knownZones = new ArrayList<>();
    private int nextId = 1;

    /** Register a discovered ER service zone. */
    public void registerZone(ErServiceZone zone) {
        knownZones.removeIf(z -> z.zoneId().equals(zone.zoneId()));
        knownZones.add(zone);
    }

    /** Get available ER zones sorted by reputation. */
    public List<ErServiceZone> availableZones() {
        return knownZones.stream()
            .sorted(Comparator.comparingDouble(ErServiceZone::reputationScore).reversed())
            .toList();
    }

    /** Initiate a referral. */
    public Referral initiate(String agentDid, String sourceZone, String targetZone,
                             ReferralType type, String reason) {
        var referral = new Referral("ref-" + nextId++, agentDid, sourceZone, targetZone,
            type, ReferralStatus.INITIATED, Instant.now(), reason, null);
        referrals.put(referral.referralId(), referral);
        return referral;
    }

    /** Advance referral status. */
    public Referral advance(String referralId, ReferralStatus newStatus) {
        var referral = referrals.get(referralId);
        if (referral == null) return null;
        var updated = new Referral(referral.referralId(), referral.agentDid(),
            referral.sourceZone(), referral.targetZone(), referral.type(),
            newStatus, referral.createdAt(), referral.reason(), referral.recoveryPlan());
        referrals.put(referralId, updated);
        return updated;
    }

    /** Attach a recovery plan to a referral. */
    public Referral attachRecoveryPlan(String referralId, String plan) {
        var referral = referrals.get(referralId);
        if (referral == null) return null;
        var updated = new Referral(referral.referralId(), referral.agentDid(),
            referral.sourceZone(), referral.targetZone(), referral.type(),
            ReferralStatus.PLAN_RECEIVED, referral.createdAt(), referral.reason(), plan);
        referrals.put(referralId, updated);
        return updated;
    }

    /** Get active referrals for an agent. */
    public List<Referral> activeReferrals(String agentDid) {
        return referrals.values().stream()
            .filter(r -> r.agentDid().equals(agentDid))
            .filter(r -> r.status() != ReferralStatus.DISCHARGED
                      && r.status() != ReferralStatus.FAILED)
            .toList();
    }

    public Optional<Referral> getReferral(String referralId) {
        return Optional.ofNullable(referrals.get(referralId));
    }

    public int referralCount() { return referrals.size(); }
}
