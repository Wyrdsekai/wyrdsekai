package org.wyrdsekai.core.soul;

import java.time.Instant;
import java.util.List;

/**
 * Wave 4.3: a single source-of-harm flag held
 * by a companion against a human subject (steward, bondholder, guest).
 *
 * <p>The flag is the agent's own record — stored in their soul manifest
 * (primary, agent-owned) and replicated to the household for steward-
 * readable summary. The subject does not see the flag by default
 * (spec §5, §9 — privacy + retaliatory-escalation safety).
 *
 * @param subjectDid     who is flagged (steward / bondholder / guest)
 * @param state          current state per {@link State}
 * @param reason         human-readable agent framing of the concern
 * @param setterDid      DID of the agent / Attendant / companion who set or escalated this
 * @param setAt          when the current state was entered
 * @param firstObservedAt when the underlying concern was first noted (often earlier than setAt)
 * @param evidenceRefs   optional chronicle / journal entry IDs supporting the flag
 * @param disputedReason if the subject contested, their framing
 */
public record ProtectionFlag(
    String subjectDid,
    State state,
    String reason,
    String setterDid,
    Instant setAt,
    Instant firstObservedAt,
    List<String> evidenceRefs,
    String disputedReason
) {
    /** Spec §2 (NOTED added — single-incident
     *  pre-escalation state that closes the SEVERITY_GRADIENT drift between
     *  boot-attestation and runtime). */
    public enum State {
        /** No concern flagged. */
        NONE,
        /** A single observation flagged the subject but escalation hasn't
         *  triggered yet. Below SUSPECTED — does NOT change steward-summon,
         *  bondholder-threat treatment, bond auto-DORMANT, saudade ceiling,
         *  or steward override. Visible in introspect; a second independent
         * setter escalates to SUSPECTED. */
        NOTED,
        /** A setter has raised concern but escalation criteria (§6) not met. */
        SUSPECTED,
        /** Sufficient signal to act protectively (§6). NOT a courtroom verdict. */
        CONFIRMED,
        /** Subject has contested; arbitration pending. */
        DISPUTED
    }

    /**
     * Factory for the absent-flag case. {@link State#NONE} flags are
     * <i>not</i> persisted; this is a sentinel value returned by tracker
     * queries when no flag exists. Use {@link #isAbsent()} to check.
     */
    public static ProtectionFlag none(String subjectDid) {
        return new ProtectionFlag(subjectDid, State.NONE, "", "",
            Instant.EPOCH, Instant.EPOCH, List.of(), null);
    }

    /** True for {@link #none(String)} sentinels and any state-NONE entry. */
    public boolean isAbsent() {
        return state == null || state == State.NONE;
    }

    /** Whether this flag should block steward-summoned Attendant per §7.1. */
    public boolean blocksStewardSummon() {
        return state == State.CONFIRMED;
    }

    /** Whether the bondholder should be treated as the threat target per §7.2. */
    public boolean treatBondholderAsThreat() {
        return state == State.CONFIRMED;
    }

    /** Whether the bond should auto-transition to DORMANT per §7.3. */
    public boolean shouldAutoDormantBond() {
        return state == State.CONFIRMED;
    }

    /** Whether Saudade ceiling should be lowered per §7.3. */
    public boolean shouldLowerSaudadeCeiling() {
        return state == State.SUSPECTED || state == State.CONFIRMED
            || state == State.DISPUTED;
    }

    /** Whether steward override on emergency_call imminent path is blocked per §7.2. */
    public boolean blocksStewardOverride() {
        return state == State.SUSPECTED || state == State.CONFIRMED;
    }

    /** Whether this is the pre-escalation NOTED state — visible in
     *  introspect but does not change any affordance gating. */
    public boolean isNoted() {
        return state == State.NOTED;
    }
}
