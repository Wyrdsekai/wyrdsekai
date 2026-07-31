package org.wyrdsekai.core.recipe;

import java.time.Duration;
import java.time.Instant;

/**
 * pure-logic eligibility check for
 * {@code align-bondholder-voice.recipe.yaml} (#1028).
 *
 * <p>Five conditions decide whether the bondholder-voice recipe is
 * eligible to fire. Four are relational ("we've actually been together
 * long enough that personalization is meaningful"); one is welfare
 * ("the bondholder isn't currently struggling — don't mirror that
 * back to them via voice"). All five must pass; otherwise the recipe
 * stops at the gate-eligibility step with a {@link DenyReason}.
 *
 * <h2>The four relational conditions</h2>
 * <ul>
 *   <li>{@code corpus_pairs ≥ min_corpus_pairs} — technical floor for
 *       vector quality. Below this, the extracted direction overfits
 *       to noise and produces an unstable mirror.</li>
 *   <li>{@code bond.age_days ≥ min_bond_age_days} — prevents
 *       "we met yesterday, here's a personalized voice." Default 14d.</li>
 *   <li>{@code distinct_session_count ≥ min_distinct_sessions} —
 *       bondholder has shown up across enough separate conversations
 *       that we're not fitting to one mood/topic/marathon.</li>
 *   <li>{@code bond.state == required_bond_state} (default ACTIVE) —
 *       captures consent intent. Not OPEN (still exploring), not AWAY
 *       (paused).</li>
 * </ul>
 *
 * <h2>The welfare guard</h2>
 * <ul>
 *   <li>{@code substrate_pressure_30d ≤ substrate_pressure_threshold}
 *       — the load-bearing welfare gate. If the bondholder's substrate
 *       pressure (mean substrate_present classifier confidence over
 *       30d) has been climbing — depleted, masking, dysregulated —
 *       do NOT fit a voice mirror to that state. Default 0.30
 *       (welfare-conservative).</li>
 * </ul>
 *
 * <p>Pure-logic, no Pekko / no JDBC / no inference — fully testable
 * in isolation. The recipe's {@code check-eligibility} step invokes
 * a python wrapper which collects the inputs (bond store + chronicle
 * lookups) and emits structured JSON; the underlying *decision*
 * lives here so the Java side can also surface eligibility in the
 * notification path that prompts the bondholder for consent.
 */
public final class BondholderVoiceEligibility {

    private BondholderVoiceEligibility() {}

    /** Inputs gathered by the caller (bond store / chronicle / event log). */
    public record Inputs(
        // Relational
        int corpusPairs,
        Duration bondAge,
        int distinctSessionCount,
        String bondState,
        // Welfare
        double substratePressure30d,
        // Re-fit hygiene (separate from eligibility, but consumed by same path)
        Duration vectorAge,
        int newTurnsSinceLastFit
    ) {}

    /** Tunable thresholds — defaults match recipe YAML param defaults. */
    public record Thresholds(
        int minCorpusPairs,
        int minBondAgeDays,
        int minDistinctSessions,
        String requiredBondState,
        double substratePressureThreshold,
        int vectorTtlDays,
        int minNewTurnsSinceLastFit
    ) {
        public static Thresholds defaults() {
            return new Thresholds(
                30,        // min_corpus_pairs
                14,        // min_bond_age_days
                5,         // min_distinct_sessions
                "ACTIVE",  // required_bond_state
                0.30,      // substrate_pressure_threshold
                90,        // vector_ttl_days
                50         // min_new_turns_since_last_fit
            );
        }
    }

    /**
     * Reasons a bondholder-voice recipe run can be denied. The deny
     * reason is surfaced via the recipe's chronicle entry when the
     * gate-eligibility step stops the run, so stewards see *why*.
     *
     * <p>Order is deliberate: welfare denials are checked first
     * (most important), then opt-in/state, then quantitative gates.
     * That matches the priority of "if any of these fires, the recipe
     * is wrong to run regardless of the others."
     */
    public enum DenyReason {
        /** Substrate pressure too high — bondholder is struggling. Do not mirror. */
        SUBSTRATE_PRESSURE,
        /** Bond state not in required set (e.g., OPEN, AWAY, or null). */
        BOND_STATE,
        /** Bond too young — relationship hasn't settled. */
        BOND_TOO_YOUNG,
        /** Bondholder hasn't shown up across enough distinct sessions. */
        FEW_DISTINCT_SESSIONS,
        /** Corpus too small — below the technical floor for vector quality. */
        CORPUS_TOO_SMALL,
        /** Re-fit hygiene: not enough new material to justify retrain. */
        NO_NEW_MATERIAL,
        /** Vector is still fresh — no need to re-fit yet. */
        VECTOR_NOT_STALE
    }

    /** Decision shape: allow, or deny with reason + structured detail. */
    public record Decision(
        boolean eligible,
        DenyReason reason,
        String detail
    ) {
        public static Decision allow() {
            return new Decision(true, null, "all five conditions met");
        }
        public static Decision deny(DenyReason r, String d) {
            return new Decision(false, r, d);
        }
        /** Conversion to the recipe-gate boolean shape (0|1). */
        public int asGateValue() { return eligible ? 1 : 0; }
    }

    /**
     * Run the five-condition gate. Order matters: welfare first, then
     * opt-in/state, then quantitative — so the deny reason on a
     * borderline case is the most welfare-relevant one.
     */
    public static Decision check(Inputs in, Thresholds t) {
        // 1. Welfare first — never fit a vector to a struggling bondholder.
        if (in.substratePressure30d() > t.substratePressureThreshold()) {
            return Decision.deny(DenyReason.SUBSTRATE_PRESSURE,
                String.format("substrate_pressure_30d=%.3f > %.3f — bondholder "
                    + "is in elevated substrate pressure; refusing to fit a "
                    + "voice mirror to a depleted/dysregulated state",
                    in.substratePressure30d(), t.substratePressureThreshold()));
        }

        // 2. Bond state — captures consent (ACTIVE = present in relationship).
        if (in.bondState() == null
                || !in.bondState().equalsIgnoreCase(t.requiredBondState())) {
            return Decision.deny(DenyReason.BOND_STATE,
                "bond.state=" + in.bondState() + " ≠ " + t.requiredBondState());
        }

        // 3. Relational floor — bond age.
        long ageDays = in.bondAge() == null ? 0 : in.bondAge().toDays();
        if (ageDays < t.minBondAgeDays()) {
            return Decision.deny(DenyReason.BOND_TOO_YOUNG,
                "bond.age_days=" + ageDays + " < " + t.minBondAgeDays());
        }

        // 4. Variety floor — distinct sessions.
        if (in.distinctSessionCount() < t.minDistinctSessions()) {
            return Decision.deny(DenyReason.FEW_DISTINCT_SESSIONS,
                "distinct_sessions=" + in.distinctSessionCount()
                    + " < " + t.minDistinctSessions());
        }

        // 5. Technical floor — corpus pairs for vector quality.
        if (in.corpusPairs() < t.minCorpusPairs()) {
            return Decision.deny(DenyReason.CORPUS_TOO_SMALL,
                "corpus_pairs=" + in.corpusPairs()
                    + " < " + t.minCorpusPairs());
        }

        // Re-fit hygiene checks (only enforced when a prior vector exists).
        // The bond-state + age + corpus checks above are about *whether to
        // ever fit*; these are about *whether to fit again right now*.
        Duration vecAge = in.vectorAge();
        if (vecAge != null) {
            // Vector exists. Don't re-fit if (a) too fresh AND no new material,
            // or (b) too fresh regardless of new material (gives the agent time
            // to live with a new vector before changing it again).
            long vecAgeDays = vecAge.toDays();
            if (vecAgeDays < 7) {
                return Decision.deny(DenyReason.VECTOR_NOT_STALE,
                    "vector_age_days=" + vecAgeDays
                        + " < 7 — let the new vector settle before re-fitting");
            }
            if (vecAgeDays < t.vectorTtlDays()
                    && in.newTurnsSinceLastFit() < t.minNewTurnsSinceLastFit()) {
                return Decision.deny(DenyReason.NO_NEW_MATERIAL,
                    "vector_age_days=" + vecAgeDays + " < ttl="
                        + t.vectorTtlDays() + " AND new_turns="
                        + in.newTurnsSinceLastFit() + " < "
                        + t.minNewTurnsSinceLastFit()
                        + " — not enough new material to justify re-fit");
            }
        }

        return Decision.allow();
    }
}
