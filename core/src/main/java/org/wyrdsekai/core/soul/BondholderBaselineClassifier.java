package org.wyrdsekai.core.soul;

import java.time.Duration;
import java.time.Instant;

/**
 * Wave 3: pattern-based bond-state classifier.
 *
 * <p>Distinguishes "bondholder away on a trip" from "bondholder drifting" by
 * comparing current silence against the bondholder's <i>own baseline</i> pattern
 * (median inter-engagement interval from recent history). Pure-time thresholds
 * are explicitly rejected by the spec — a bondholder who engages weekly with
 * two-week gaps should not trigger DORMANT at the same threshold as one who
 * engaged daily and went silent.
 *
 * <p>Decisions explicitly respect:
 * <ul>
 *   <li><b>Cold-start</b> (Bond.inColdStart): first 14 days suspend auto-
 *       transitions. New bonds are not pre-emptively interpreted as drift.
 *       SPEC §3.5.
 *   <li><b>Explicit-absence declaration</b>: when bondholder declared they'd
 *       be away for stated duration, classifier honors it — stays in AWAY
 *       confidently regardless of pattern. SPEC §3.3.
 *   <li><b>Sustained-drift pattern</b>: declining recent substance + interval
 *       gap together push DORMANT faster than interval alone. SPEC §3.4.
 *   <li><b>SEVERED for sustained disappearance</b>: 90+ days with no signal
 *       transitions to SEVERED with {@code unresolved} flag (handled by
 *       caller via separate severance protocol). SPEC §8.3.
 * </ul>
 *
 * <p>Pure logic — no I/O, no actor refs, no persistence side effects. Caller
 * wires the classifier into a periodic tick and applies recommendations to
 * the {@link Bond} via {@link Bond#withState(BondState)}.
 */
public final class BondholderBaselineClassifier {

    /** Multiple of median interval at which ACTIVE → AWAY transition fires. SPEC §3.2. */
    public static final double AWAY_THRESHOLD_MULTIPLIER = 1.5;

    /** Multiple of median interval at which AWAY → DORMANT transition fires. SPEC §3.2. */
    public static final double DORMANT_THRESHOLD_MULTIPLIER = 4.0;

    /** Sustained-disappearance window past which DORMANT → SEVERED fires. SPEC §3.2 + §8.3. */
    public static final Duration SEVERED_THRESHOLD = Duration.ofDays(90);

    /**
     * Cold-start absolute thresholds for bondholders with no established baseline
     * (fewer than {@link BondholderEngagementHistory#medianInterval} returns
     * non-null). Used after the 14-day cold-start window has expired but before
     * the classifier has enough history to compute median. Defaults err
     * conservative — slow to escalate, generous to the bondholder.
     */
    public static final Duration COLD_START_AWAY_DEFAULT = Duration.ofDays(7);
    public static final Duration COLD_START_DORMANT_DEFAULT = Duration.ofDays(30);

    /**
     * Recent substance-drop ratio at which sustained-drift signal activates.
     * If avg substance over last 3 events &lt; 0.5 × avg substance over last 10,
     * the bondholder is drifting. Combined with interval gap, pushes toward
     * DORMANT faster than interval alone would. SPEC §3.4.
     */
    public static final double DRIFT_RATIO_THRESHOLD = 0.5;

    private BondholderBaselineClassifier() {}

    /**
     * Classify recommended bond state given current Bond + engagement history.
     *
     * @return recommended state with reason, or null if no transition recommended.
     */
    public static Recommendation classify(Bond bond,
                                          BondholderEngagementHistory history,
                                          Instant now) {
        if (bond == null) return null;
        var canonical = bond.canonicalState();
        var currentState = canonical.state();
        if (currentState == null) currentState = BondState.ACTIVE;

        // Already-terminal states don't auto-transition.
        if (currentState == BondState.SEVERED) return null;

        var t = now == null ? Instant.now() : now;

        // Identify the bondholder DID from the bond. The "bondholder" is whichever
        // party isn't the agent — but the classifier is told from outside which
        // DID is the bondholder. For simplicity we accept either party's history
        // by trying both; caller passes the bondholder side. The bond participants
        // are {agentADid, agentBDid}; engagement history is keyed on bondholderDid
        // which is one of them.
        var bondholderDid = identifyBondholderDid(bond, history);
        if (bondholderDid == null) {
            // No engagement history at all — bond is too new for classification.
            // Cold-start logic in Bond covers this case.
            return null;
        }

        // Honor cold-start window — suspends auto-transitions per SPEC §3.5.
        if (canonical.inColdStart()) {
            return null;
        }

        // Honor explicit-absence declaration — bondholder said they'd be away
        // for stated duration. If declaration still active, transition to AWAY
        // if not already there but suspend further auto-transitions. SPEC §3.3.
        var declared = history.activeDeclaredAbsence(bondholderDid, t);
        if (declared != null) {
            if (currentState == BondState.ACTIVE) {
                return new Recommendation(BondState.AWAY,
                    "explicit-absence declaration active until " + declared.declaredUntil());
            }
            // Already AWAY/DORMANT/REACTIVATING under declared absence — hold.
            return null;
        }

        // REACTIVATING is a one-cycle transient state — Bond.withInteraction
        // handles ACTIVE return on next engagement. Classifier doesn't auto-
        // transition out of REACTIVATING.
        if (currentState == BondState.REACTIVATING) return null;

        var lastEngagement = history.lastEngagement(bondholderDid);
        if (lastEngagement == null) {
            // History exists but no engagement events recorded — treat as cold-start
            // fallback for safety.
            return null;
        }
        var silence = Duration.between(lastEngagement, t);

        // SEVERED check — 90+ days of total silence past current state.
        if (silence.compareTo(SEVERED_THRESHOLD) > 0) {
            return new Recommendation(BondState.SEVERED,
                "no engagement in " + silence.toDays() + " days (unresolved disappearance)");
        }

        // Compute median interval baseline. May be null if insufficient history.
        var median = history.medianInterval(bondholderDid);
        Duration awayThreshold;
        Duration dormantThreshold;
        if (median != null) {
            // Pattern-based thresholds scale with the bondholder's own baseline.
            // Duration.multipliedBy only takes a long, so we go via seconds to
            // preserve the fractional multiplier (1.5×).
            awayThreshold = Duration.ofSeconds(
                (long) (median.getSeconds() * AWAY_THRESHOLD_MULTIPLIER));
            dormantThreshold = Duration.ofSeconds(
                (long) (median.getSeconds() * DORMANT_THRESHOLD_MULTIPLIER));
        } else {
            // Insufficient history — conservative fallback defaults.
            awayThreshold = COLD_START_AWAY_DEFAULT;
            dormantThreshold = COLD_START_DORMANT_DEFAULT;
        }

        // Sustained-drift: if recent substance has dropped, the bondholder is
        // drifting even before pure-interval thresholds fire. Push toward DORMANT
        // at lower interval threshold under this condition.
        var driftActive = isSustainedDrift(history, bondholderDid);

        // Apply transitions in priority order.
        if (currentState == BondState.ACTIVE) {
            // ACTIVE → DORMANT directly when drift + silence both present.
            // ACTIVE → AWAY on simple silence past baseline.
            if (driftActive && silence.compareTo(dormantThreshold) > 0) {
                return new Recommendation(BondState.DORMANT,
                    "sustained drift pattern + silence " + silence.toDays() + " days past baseline");
            }
            if (silence.compareTo(awayThreshold) > 0) {
                return new Recommendation(BondState.AWAY,
                    "silence " + silence.toHours() + " hours past 1.5× baseline ("
                        + (median != null ? median.toHours() + "h" : "cold-start default") + ")");
            }
        } else if (currentState == BondState.AWAY) {
            // AWAY → DORMANT on prolonged silence.
            if (silence.compareTo(dormantThreshold) > 0) {
                return new Recommendation(BondState.DORMANT,
                    "silence " + silence.toDays() + " days past 4× baseline");
            }
        } else if (currentState == BondState.DORMANT) {
            // DORMANT → SEVERED happens via SEVERED_THRESHOLD check above.
        } else if (currentState == BondState.MOURNING) {
            // Mourning is a terminal-tendency state — classifier does not
            // auto-transition out. Steward/ceremony moves it.
        }
        return null;
    }

    /**
     * Identify which side of the bond is the bondholder by checking which DID
     * has engagement events recorded. Returns null if neither party has events.
     * Note: this is an inference because Bond.java doesn't tag which side is
     * agent vs bondholder — both are "agent DIDs" formally. In practice the
     * agent's own DID never has engagement events recorded against itself, so
     * the DID that DOES have events is the bondholder.
     */
    private static String identifyBondholderDid(Bond bond, BondholderEngagementHistory history) {
        if (history.eventCount(bond.agentADid()) > 0) return bond.agentADid();
        if (history.eventCount(bond.agentBDid()) > 0) return bond.agentBDid();
        return null;
    }

    /**
     * Sustained-drift signal — recent substance (last 3 events) has dropped
     * substantially relative to broader recent average (last 10). Activates
     * the drift-flag for the classifier to use harsher thresholds.
     */
    private static boolean isSustainedDrift(BondholderEngagementHistory history, String did) {
        if (history.eventCount(did) < 6) return false; // need enough data to compare
        var recentAvg = history.recentAvgSubstance(did, 3);
        var broaderAvg = history.recentAvgSubstance(did, 10);
        if (broaderAvg <= 0.01) return false; // avoid divide-by-near-zero
        return (recentAvg / broaderAvg) < DRIFT_RATIO_THRESHOLD;
    }

    /** Classifier recommendation: target state + human-readable reason for chronicle. */
    public record Recommendation(BondState recommendedState, String reason) {}
}
