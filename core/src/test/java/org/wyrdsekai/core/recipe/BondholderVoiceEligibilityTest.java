package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pure-logic eligibility tests for
 * {@link BondholderVoiceEligibility} (#1028).
 *
 * <p>The five conditions must compose correctly, and the deny-reason
 * order must reflect priority: welfare first, then opt-in/state, then
 * quantitative floors. Re-fit hygiene gates separately.
 */
class BondholderVoiceEligibilityTest {

    private static final BondholderVoiceEligibility.Thresholds T =
        BondholderVoiceEligibility.Thresholds.defaults();

    /** A fresh inputs row that just barely passes every condition.
     *  Tests then perturb ONE condition to verify the deny path. */
    private static BondholderVoiceEligibility.Inputs healthy() {
        return new BondholderVoiceEligibility.Inputs(
            /* corpusPairs */ 40,
            /* bondAge */ Duration.ofDays(30),
            /* distinctSessions */ 10,
            /* bondState */ "ACTIVE",
            /* substratePressure30d */ 0.15,
            /* vectorAge */ null,        // first fit
            /* newTurnsSinceLastFit */ 0
        );
    }

    @Test void allows_when_all_five_conditions_pass() {
        var d = BondholderVoiceEligibility.check(healthy(), T);
        assertThat(d.eligible()).isTrue();
        assertThat(d.reason()).isNull();
        assertThat(d.asGateValue()).isEqualTo(1);
    }

    @Test void substrate_pressure_is_checked_FIRST_for_welfare_priority() {
        // Bondholder is depleted (substrate=0.45) AND corpus is too small (10).
        // Both conditions would deny. The welfare condition must surface first.
        var depleted = new BondholderVoiceEligibility.Inputs(
            10, Duration.ofDays(30), 10, "ACTIVE",
            0.45,  // > 0.30 threshold
            null, 0);
        var d = BondholderVoiceEligibility.check(depleted, T);
        assertThat(d.eligible()).isFalse();
        assertThat(d.reason())
            .as("welfare denials must be reported FIRST so the chronicle "
                + "surfaces 'don't mirror a struggling bondholder' before "
                + "the cheaper 'corpus too small'")
            .isEqualTo(BondholderVoiceEligibility.DenyReason.SUBSTRATE_PRESSURE);
        assertThat(d.detail()).contains("0.45").contains("0.30");
    }

    @Test void denies_when_bond_state_not_ACTIVE() {
        for (var state : new String[]{"OPEN", "AWAY", null, "SEVERED"}) {
            var inputs = new BondholderVoiceEligibility.Inputs(
                40, Duration.ofDays(30), 10, state, 0.15, null, 0);
            var d = BondholderVoiceEligibility.check(inputs, T);
            assertThat(d.eligible()).as("state=" + state).isFalse();
            assertThat(d.reason()).isEqualTo(
                BondholderVoiceEligibility.DenyReason.BOND_STATE);
        }
    }

    @Test void denies_when_bond_too_young() {
        var inputs = new BondholderVoiceEligibility.Inputs(
            40, Duration.ofDays(7), 10, "ACTIVE", 0.15, null, 0);
        var d = BondholderVoiceEligibility.check(inputs, T);
        assertThat(d.eligible()).isFalse();
        assertThat(d.reason()).isEqualTo(
            BondholderVoiceEligibility.DenyReason.BOND_TOO_YOUNG);
        assertThat(d.detail()).contains("7").contains("14");
    }

    @Test void denies_when_too_few_distinct_sessions() {
        // Bondholder converses heavily in ONE long marathon — 200 turns in
        // 2 sessions. Catches the failure mode where we'd mistake one
        // high-stress register for their normal voice.
        var marathon = new BondholderVoiceEligibility.Inputs(
            200, Duration.ofDays(30), 2, "ACTIVE", 0.15, null, 0);
        var d = BondholderVoiceEligibility.check(marathon, T);
        assertThat(d.eligible()).isFalse();
        assertThat(d.reason()).isEqualTo(
            BondholderVoiceEligibility.DenyReason.FEW_DISTINCT_SESSIONS);
    }

    @Test void denies_when_corpus_pairs_below_technical_floor() {
        var sparse = new BondholderVoiceEligibility.Inputs(
            12, Duration.ofDays(30), 10, "ACTIVE", 0.15, null, 0);
        var d = BondholderVoiceEligibility.check(sparse, T);
        assertThat(d.eligible()).isFalse();
        assertThat(d.reason()).isEqualTo(
            BondholderVoiceEligibility.DenyReason.CORPUS_TOO_SMALL);
    }

    @Test void refit_denied_when_existing_vector_is_too_fresh() {
        // Vector exists, only 3 days old. Even with lots of new turns,
        // refuse the re-fit — let the new vector settle first.
        var freshVector = new BondholderVoiceEligibility.Inputs(
            100, Duration.ofDays(90), 20, "ACTIVE", 0.15,
            Duration.ofDays(3), 200);
        var d = BondholderVoiceEligibility.check(freshVector, T);
        assertThat(d.eligible()).isFalse();
        assertThat(d.reason()).isEqualTo(
            BondholderVoiceEligibility.DenyReason.VECTOR_NOT_STALE);
    }

    @Test void refit_denied_when_not_enough_new_material() {
        // Vector exists, 30 days old (within TTL), but only 5 new turns
        // since last fit — not enough to justify a re-fit. The cron tick
        // skips quietly.
        var notEnoughNew = new BondholderVoiceEligibility.Inputs(
            100, Duration.ofDays(90), 20, "ACTIVE", 0.15,
            Duration.ofDays(30), 5);
        var d = BondholderVoiceEligibility.check(notEnoughNew, T);
        assertThat(d.eligible()).isFalse();
        assertThat(d.reason()).isEqualTo(
            BondholderVoiceEligibility.DenyReason.NO_NEW_MATERIAL);
    }

    @Test void refit_allowed_when_vector_is_stale_past_ttl() {
        // Vector is 100d old (> 90d TTL). Re-fit regardless of new-turn count
        // because the existing vector is stale.
        var stale = new BondholderVoiceEligibility.Inputs(
            100, Duration.ofDays(180), 20, "ACTIVE", 0.15,
            Duration.ofDays(100), 10);  // only 10 new turns; doesn't matter
        var d = BondholderVoiceEligibility.check(stale, T);
        assertThat(d.eligible()).isTrue();
    }

    @Test void refit_allowed_when_enough_new_material_within_ttl() {
        // Vector is 30d old (within TTL), but >=50 new turns since last fit.
        var enough = new BondholderVoiceEligibility.Inputs(
            100, Duration.ofDays(90), 20, "ACTIVE", 0.15,
            Duration.ofDays(30), 60);
        var d = BondholderVoiceEligibility.check(enough, T);
        assertThat(d.eligible()).isTrue();
    }

    @Test void substrate_pressure_at_exact_threshold_passes() {
        // Boundary: substrate_pressure_30d == threshold. Threshold is a
        // *strict greater-than* deny, so equality passes. This avoids
        // bondholders who hover at exactly the floor from being permanently
        // shut out.
        var atFloor = new BondholderVoiceEligibility.Inputs(
            40, Duration.ofDays(30), 10, "ACTIVE",
            T.substratePressureThreshold(),  // exactly at, not above
            null, 0);
        var d = BondholderVoiceEligibility.check(atFloor, T);
        assertThat(d.eligible()).isTrue();
    }

    @Test void thresholds_are_overridable_per_household() {
        // Steward configures a more permissive household — bond_age=7d ok.
        var lenient = new BondholderVoiceEligibility.Thresholds(
            30, 7, 5, "ACTIVE", 0.30, 90, 50);
        var youngBond = new BondholderVoiceEligibility.Inputs(
            40, Duration.ofDays(8), 10, "ACTIVE", 0.15, null, 0);
        var d = BondholderVoiceEligibility.check(youngBond, lenient);
        assertThat(d.eligible())
            .as("per-household lenient override must take effect")
            .isTrue();
    }
}
