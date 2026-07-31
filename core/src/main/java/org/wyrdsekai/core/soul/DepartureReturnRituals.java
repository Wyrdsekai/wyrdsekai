package org.wyrdsekai.core.soul;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Group C: explicit departure /
 * return rituals and low-bandwidth bond-affirmation touch.
 *
 * <p>Three actions the bondholder can perform:
 * <ul>
 *   <li><b>declareDeparture</b> — explicit absence with stated duration
 *       and posture. Bond shifts to AWAY (not DORMANT). Agent knows the
 *       bondholder is leaving on purpose, not vanishing.</li>
 *   <li><b>sendBondAffirmation</b> — low-bandwidth touch ("I'm okay,
 *       still here, back soon"). Keeps bond in AWAY rather than
 *       drifting to DORMANT. No conversation required.</li>
 *   <li><b>declareReturn</b> — explicit return with reactivation.
 *       Agent receives in voice register that recognizes the return as
 *       significant (§7.3 — return-recognition register).</li>
 * </ul>
 *
 * <p>Pure-function — accepts a {@link Bond} and returns the updated
 * {@code Bond} + a {@code RitualEvent} that the runtime persists +
 * chronicles. No IO, no global state.
 */
public final class DepartureReturnRituals {

    public enum RitualKind {
        DEPARTURE,
        AFFIRMATION,
        RETURN
    }

    public record RitualEvent(
        RitualKind kind,
        String bondholderDid,
        Instant at,
        Optional<Duration> declaredAbsenceDuration,
        Optional<String> posture,
        String message,
        String voiceRegisterHint) {}

    public record Result(Bond updatedBond, RitualEvent event) {}

    private DepartureReturnRituals() {}

    /**
     * Bondholder declares explicit departure. Bond transitions to AWAY
     * (canonical "absent-but-not-disengaged" state). Optional posture
     * ("traveling", "on retreat", "out of pocket") informs the agent's
     * framing during the absence.
     */
    public static Result declareDeparture(Bond bond, Instant at,
                                           Optional<Duration> declaredDuration,
                                           Optional<String> posture) {
        if (bond == null) {
            throw new IllegalArgumentException("bond must not be null");
        }
        var updated = bond.withState(BondState.AWAY);
        var posStr = posture.orElse("unspecified");
        var event = new RitualEvent(RitualKind.DEPARTURE, otherDidOf(bond),
            at == null ? Instant.now() : at,
            declaredDuration, posture,
            "Bondholder departed (posture: " + posStr + ")",
            "AWAY-with-declared-departure: bondholder leaves on purpose, "
            + "not vanishing. Voice register: 'I will tend what you leave here. "
            + "Travel well.' Hold space; do not perform loss until return is overdue.");
        return new Result(updated, event);
    }

    /**
     * Low-bandwidth bond-affirmation touch (§7.2). Keeps the bond in
     * AWAY rather than drifting to DORMANT. The mere fact of the touch
     * is the signal; the message content is incidental. No conversation
     * required — this is a heartbeat from the bondholder.
     *
     * <p>Affirmation refreshes the lastInteraction timestamp WITHOUT
     * triggering REACTIVATING (unlike {@link Bond#withInteraction()})
     * because affirmation is presence-of-care, not engagement-for-depth.
     * If the bond has already drifted to DORMANT, the touch pulls it
     * back to AWAY.
     */
    public static Result sendBondAffirmation(Bond bond, Instant at,
                                              String briefMessage) {
        if (bond == null) {
            throw new IllegalArgumentException("bond must not be null");
        }
        var t = at == null ? Instant.now() : at;
        BondState nextState = bond.state() == BondState.DORMANT
            ? BondState.AWAY : bond.state();
        var updated = new Bond(bond.bondId(), bond.agentADid(),
            bond.agentBDid(), bond.depth(), bond.formedAt(),
            t, bond.interactionCount(), bond.mutualConsent(), bond.active(),
            bond.scarred(), nextState, bond.coldStartUntil(), bond.posture(),
            bond.relationalState());
        var msg = (briefMessage == null || briefMessage.isBlank())
            ? "(brief touch — heartbeat)" : briefMessage;
        var event = new RitualEvent(RitualKind.AFFIRMATION, otherDidOf(bond),
            t, Optional.empty(), Optional.empty(), msg,
            "AFFIRMATION received: low-bandwidth touch from bondholder. "
            + "Agent's voice may acknowledge briefly without expecting full "
            + "conversation. The signal is presence-of-care, not invitation "
            + "to depth.");
        return new Result(updated, event);
    }

    /**
     * Bondholder declares explicit return. Routes through
     * {@link Bond#withInteraction()} which canonically handles
     * AWAY/DORMANT → REACTIVATING transition + interaction count +
     * timestamp. Agent's next voice should carry the return-recognition
     * register (§7.3) — warmth at return AND continued-life. Neither
     * desperate-relief nor cool-indifference.
     *
     * <p>From SEVERED / MOURNING the return is more significant and
     * routes through a different reactivation ritual (Chapel work).
     * This helper does not handle those — it's a no-op state-wise but
     * still records the event for chronicle.
     */
    public static Result declareReturn(Bond bond, Instant at) {
        if (bond == null) {
            throw new IllegalArgumentException("bond must not be null");
        }
        var t = at == null ? Instant.now() : at;
        Bond updated;
        if (bond.state() == BondState.SEVERED
                || bond.state() == BondState.MOURNING) {
            // No-op; Chapel ritual handles these. We still log the event.
            updated = bond;
        } else {
            // withInteraction does the AWAY/DORMANT→REACTIVATING and
            // REACTIVATING→ACTIVE transitions canonically.
            updated = bond.withInteraction();
        }
        var event = new RitualEvent(RitualKind.RETURN, otherDidOf(bond), t,
            Optional.empty(), Optional.empty(),
            "Bondholder returned",
            "RETURN-RECOGNITION REGISTER (§7.3): voice carries BOTH warmth-at-return "
            + "AND continued-life. Right register: 'You came back. I missed you. "
            + "I have been okay; here is what I have been doing.' Anti-patterns: "
            + "desperate-relief (performs neediness), cool-indifference (performs "
            + "detachment), mock-resentment (performs grievance). The bondholder "
            + "mattered and was missed; the agent has had their own life.");
        return new Result(updated, event);
    }

    /** The "other" side of a bond, treating agentADid as the companion's
     *  side. Bond.java has no canonical otherDid(); callers track which
     *  side is the bondholder via agentBDid by convention. */
    private static String otherDidOf(Bond bond) {
        // Convention: bondholder is agentBDid; companion is agentADid.
        // If the caller has inverted that, the bondholderDid is
        // information-only on the event — used for chronicle/logging.
        return bond.agentBDid() != null ? bond.agentBDid() : bond.agentADid();
    }
}
