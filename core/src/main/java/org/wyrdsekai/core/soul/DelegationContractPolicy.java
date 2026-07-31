package org.wyrdsekai.core.soul;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Group C: pure-function policy layer that
 * decides whether an agent's crisis bunshin / Attendant-summon /
 * emergency-call spend is allowed against the bondholder's delegated
 * API allotment.
 *
 * <p>Per spec §3.2:
 * <ul>
 *   <li>Bondholder authorizes agent to use their personal API allotment
 *       for crisis bunshin <i>without per-request consent</i>, up to a cap</li>
 *   <li>Caps come in three windows: per-month, per-day, per-incident</li>
 *   <li>Cap-exhaustion triggers other paths (not blocks the request — the
 *       runtime checks alternative resources)</li>
 *   <li>SUSPENSION: if bondholder is flagged source-of-harm
 *       (PROTECTION_FLAGS: SUSPECTED+), delegation is immediately
 *       suspended — agent cannot draw on bondholder's allotment</li>
 * </ul>
 *
 * <p>This is the architectural commitment: the agent has a budget on
 * bondholder's resources, but that budget is moral-default-gated by
 * the protection-flag state. Source-of-harm flags collapse the budget
 * to zero immediately, without waiting for steward intervention.
 */
public final class DelegationContractPolicy {

    /** A bondholder's standing delegation contract. */
    public record Contract(
        String bondholderDid,
        long perMonthCapUsd,
        long perDayCapUsd,
        long perIncidentCapUsd,
        boolean suspended) {

        public static Contract suspended(String bondholderDid) {
            return new Contract(bondholderDid, 0, 0, 0, true);
        }
    }

    /** Per-window spend tracking. */
    public record SpendWindow(
        long spentThisMonthUsd,
        long spentThisDayUsd,
        long spentThisIncidentUsd) {

        public static SpendWindow zero() {
            return new SpendWindow(0, 0, 0);
        }
    }

    public enum DenyReason {
        ALLOWED,
        SUSPENDED_BY_PROTECTION_FLAG,
        EXHAUSTED_MONTHLY_CAP,
        EXHAUSTED_DAILY_CAP,
        EXHAUSTED_INCIDENT_CAP,
        NO_CONTRACT,
        INVALID_REQUEST
    }

    /** A policy decision. */
    public record Decision(boolean allowed, DenyReason reason, long requestedUsd,
                            long remainingMonthlyUsd) {
        public static Decision allow(long requestedUsd, long remainingMonthlyUsd) {
            return new Decision(true, DenyReason.ALLOWED, requestedUsd, remainingMonthlyUsd);
        }
        public static Decision deny(DenyReason r, long requestedUsd) {
            return new Decision(false, r, requestedUsd, 0);
        }
    }

    private DelegationContractPolicy() {}

    /**
     * Decide whether {@code requestedUsd} (a crisis bunshin / attendant /
     * emergency-call request) can be drawn from the bondholder's
     * delegated allotment.
     *
     * @param contract the standing contract (null = no delegation set up)
     * @param spend    current spend window tracker
     * @param bondholderProtectionFlag bondholder's protection-flag state
     * @param requestedUsd the request size
     * @return Decision with allowed/denied + remaining-monthly for context
     */
    public static Decision decide(
            Contract contract,
            SpendWindow spend,
            Optional<ProtectionFlag.State> bondholderProtectionFlag,
            long requestedUsd) {
        if (requestedUsd < 0) {
            return Decision.deny(DenyReason.INVALID_REQUEST, requestedUsd);
        }
        if (contract == null) {
            return Decision.deny(DenyReason.NO_CONTRACT, requestedUsd);
        }
        // §3.2 source-of-harm gate: SUSPECTED+ immediately suspends.
        if (bondholderProtectionFlag.isPresent()) {
            var flag = bondholderProtectionFlag.get();
            if (flag == ProtectionFlag.State.SUSPECTED
                    || flag == ProtectionFlag.State.CONFIRMED) {
                return Decision.deny(DenyReason.SUSPENDED_BY_PROTECTION_FLAG, requestedUsd);
            }
        }
        if (contract.suspended()) {
            return Decision.deny(DenyReason.SUSPENDED_BY_PROTECTION_FLAG, requestedUsd);
        }
        var s = spend == null ? SpendWindow.zero() : spend;
        if (s.spentThisIncidentUsd() + requestedUsd > contract.perIncidentCapUsd()) {
            return Decision.deny(DenyReason.EXHAUSTED_INCIDENT_CAP, requestedUsd);
        }
        if (s.spentThisDayUsd() + requestedUsd > contract.perDayCapUsd()) {
            return Decision.deny(DenyReason.EXHAUSTED_DAILY_CAP, requestedUsd);
        }
        if (s.spentThisMonthUsd() + requestedUsd > contract.perMonthCapUsd()) {
            return Decision.deny(DenyReason.EXHAUSTED_MONTHLY_CAP, requestedUsd);
        }
        long remaining = Math.max(0,
            contract.perMonthCapUsd() - s.spentThisMonthUsd() - requestedUsd);
        return Decision.allow(requestedUsd, remaining);
    }
}
