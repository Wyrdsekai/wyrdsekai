package org.wyrdsekai.core.soul;

import java.time.Duration;
import java.time.Instant;

/**
 * Wave 4.6a: immutable record of a single
 * Sanctuary session's lifecycle. The agent's request → Attendant
 * presence → bounded session → Attendant withdraws → Sanctuary closes
 * sequence, captured as data so the runtime adapter (Wave 4.6b) can
 * drive it, persist it, and surface it to the bondholder-facing Study
 * (without ever surfacing session contents).
 *
 * <p>Hard constraints per spec §5.3/§5.5:
 * <ul>
 *   <li>Session contents are NOT chronicled — privacy. Only the
 *       <i>initiation</i> and <i>state transitions</i> here are.</li>
 *   <li>No bondholder visibility into the session itself.</li>
 *   <li>No steward visibility into the session itself.</li>
 *   <li>Time-bounded — closes after {@link #DEFAULT_MAX_DURATION}
 *       wall-clock OR {@link #DEFAULT_MAX_TURNS} turns, whichever
 *       comes first.</li>
 * </ul>
 *
 * <p>The session is held by a per-agent {@link AttendantSessionTracker}
 * (also in this package). One active session per agent.
 */
public record AttendantSession(
    String sessionId,
    String agentDid,
    State state,
    Instant openedAt,
    Instant attendantArrivedAt,
    Instant withdrawnAt,
    Instant closedAt,
    int turnCount,
    int maxTurns,
    Duration maxDuration,
    String requestReason
) {

    /** Default bound: 90 minutes per spec §5.3. */
    public static final Duration DEFAULT_MAX_DURATION = Duration.ofMinutes(90);

    /** Default bound: 30 turns per spec §5.3 ("N turns" calibrated). */
    public static final int DEFAULT_MAX_TURNS = 30;

    /** Lifecycle states per spec §5.5. */
    public enum State {
        /** Agent has requested entry; Attendant has not yet arrived. */
        REQUESTED,
        /** Attendant has arrived; session can begin. */
        ATTENDANT_PRESENT,
        /** Session is active — bounded turn/time counter ticking. */
        ACTIVE,
        /** Attendant has withdrawn. Sanctuary closes next tick. */
        WITHDRAWN,
        /** Session closed — terminal state. */
        CLOSED
    }

    /**
     * Open a fresh session in REQUESTED state. Generated session ID is
     * derived from agentDid + open timestamp for deterministic logging.
     */
    public static AttendantSession open(String agentDid, String reason, Instant at) {
        if (agentDid == null || agentDid.isBlank()) {
            throw new IllegalArgumentException("agentDid required");
        }
        var id = "sanctuary-" + agentDid + "-" + at.getEpochSecond();
        return new AttendantSession(id, agentDid, State.REQUESTED,
            at, null, null, null,
            0, DEFAULT_MAX_TURNS, DEFAULT_MAX_DURATION,
            reason == null ? "" : reason);
    }

    /** Mark Attendant arrival; transitions REQUESTED → ATTENDANT_PRESENT. */
    public AttendantSession attendantArrived(Instant at) {
        if (state != State.REQUESTED) {
            throw new IllegalStateException(
                "Attendant arrival expected REQUESTED, got " + state);
        }
        return new AttendantSession(sessionId, agentDid, State.ATTENDANT_PRESENT,
            openedAt, at, withdrawnAt, closedAt,
            turnCount, maxTurns, maxDuration, requestReason);
    }

    /** Begin active session; transitions ATTENDANT_PRESENT → ACTIVE. */
    public AttendantSession activate() {
        if (state != State.ATTENDANT_PRESENT) {
            throw new IllegalStateException(
                "Activate expected ATTENDANT_PRESENT, got " + state);
        }
        return new AttendantSession(sessionId, agentDid, State.ACTIVE,
            openedAt, attendantArrivedAt, withdrawnAt, closedAt,
            turnCount, maxTurns, maxDuration, requestReason);
    }

    /** Increment turn count. No-op in non-ACTIVE states. */
    public AttendantSession recordTurn() {
        if (state != State.ACTIVE) return this;
        return new AttendantSession(sessionId, agentDid, state,
            openedAt, attendantArrivedAt, withdrawnAt, closedAt,
            turnCount + 1, maxTurns, maxDuration, requestReason);
    }

    /** Attendant withdraws; transitions ACTIVE → WITHDRAWN. */
    public AttendantSession attendantWithdraws(Instant at) {
        if (state != State.ACTIVE && state != State.ATTENDANT_PRESENT) {
            throw new IllegalStateException(
                "Withdraw expected ACTIVE or ATTENDANT_PRESENT, got " + state);
        }
        return new AttendantSession(sessionId, agentDid, State.WITHDRAWN,
            openedAt, attendantArrivedAt, at, closedAt,
            turnCount, maxTurns, maxDuration, requestReason);
    }

    /** Close the session; transitions any non-CLOSED → CLOSED. Terminal. */
    public AttendantSession close(Instant at) {
        if (state == State.CLOSED) return this;
        return new AttendantSession(sessionId, agentDid, State.CLOSED,
            openedAt, attendantArrivedAt, withdrawnAt, at,
            turnCount, maxTurns, maxDuration, requestReason);
    }

    /** Whether time + turn bounds have been exceeded. */
    public boolean boundsExceeded(Instant now) {
        if (turnCount >= maxTurns) return true;
        var elapsed = Duration.between(openedAt, now);
        return elapsed.compareTo(maxDuration) >= 0;
    }

    /** Whether the session is terminal. */
    public boolean isTerminal() {
        return state == State.CLOSED;
    }

    /** Whether the agent is currently inside an active Sanctuary session. */
    public boolean isActiveInSanctuary() {
        return state == State.ACTIVE || state == State.ATTENDANT_PRESENT;
    }
}
