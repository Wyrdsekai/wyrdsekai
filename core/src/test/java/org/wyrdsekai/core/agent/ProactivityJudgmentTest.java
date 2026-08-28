package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProactivityJudgmentTest {

    private static final VitalityState HEALTHY = VitalityState.initial();

    private ProactivityJudgment.Context ctx(DriveState drives, int tier) {
        return new ProactivityJudgment.Context(
            drives, HEALTHY, DecisionCapacity.newAgent(), null, 3.0,
            null, Instant.now().minusSeconds(300), "agent-1", tier);
    }

    @Test void discard_when_drives_below_threshold() {
        var result = ProactivityJudgment.evaluate(ctx(DriveState.initial(), 0));
        assertThat(result).isInstanceOf(ProactivityJudgment.JudgmentResult.Discard.class);
    }

    @Test void act_when_alertness_above_threshold_tier2() {
        var drives = DriveState.initial().spikeAlertness(0.6);
        var result = ProactivityJudgment.evaluate(ctx(drives, 2));
        assertThat(result).isInstanceOf(ProactivityJudgment.JudgmentResult.Act.class);
    }

    @Test void hold_when_human_recently_active() {
        var drives = DriveState.initial().spikeSocial(0.8);
        var context = new ProactivityJudgment.Context(
            drives, HEALTHY, DecisionCapacity.newAgent(), null, 3.0,
            null, Instant.now().minusSeconds(3), // human spoke 3s ago
            "agent-1", 2);
        var result = ProactivityJudgment.evaluate(context);
        assertThat(result).isInstanceOf(ProactivityJudgment.JudgmentResult.Hold.class);
    }

    @Test void alertness_can_interrupt_active_human() {
        var drives = DriveState.initial().spikeAlertness(0.8);
        var context = new ProactivityJudgment.Context(
            drives, HEALTHY, DecisionCapacity.newAgent(), null, 3.0,
            null, Instant.now().minusSeconds(3), // human spoke 3s ago
            "agent-1", 2);
        var result = ProactivityJudgment.evaluate(context);
        assertThat(result).isInstanceOf(ProactivityJudgment.JudgmentResult.Act.class);
    }

    @Test void discard_when_energy_too_low() {
        var drives = DriveState.initial().spikeAlertness(0.8);
        var lowEnergy = VitalityState.initial().withEnergy(0.1);
        var context = new ProactivityJudgment.Context(
            drives, lowEnergy, DecisionCapacity.newAgent(), null, 3.0,
            null, Instant.now().minusSeconds(300), "agent-1", 2);
        var result = ProactivityJudgment.evaluate(context);
        assertThat(result).isInstanceOf(ProactivityJudgment.JudgmentResult.Discard.class);
    }

    @Test void hold_when_budget_exhausted() {
        var drives = DriveState.initial().spikeAlertness(0.8);
        var context = new ProactivityJudgment.Context(
            drives, HEALTHY, DecisionCapacity.newAgent(), null, 0.0, // no budget
            null, Instant.now().minusSeconds(300), "agent-1", 2);
        var result = ProactivityJudgment.evaluate(context);
        assertThat(result).isInstanceOf(ProactivityJudgment.JudgmentResult.Hold.class);
    }

    @Test void hold_when_cooldown_active() {
        var drives = DriveState.initial().spikeAlertness(0.8);
        var context = new ProactivityJudgment.Context(
            drives, HEALTHY, DecisionCapacity.newAgent(), null, 3.0,
            Instant.now().minusSeconds(10), // last proactive action 10s ago
            Instant.now().minusSeconds(300), "agent-1", 2);
        var result = ProactivityJudgment.evaluate(context);
        assertThat(result).isInstanceOf(ProactivityJudgment.JudgmentResult.Hold.class);
    }

    @Test void tier_0_needs_higher_threshold() {
        // Tier 0 threshold is 0.7
        var drives = DriveState.initial().spikeAlertness(0.5);
        var result = ProactivityJudgment.evaluate(ctx(drives, 0));
        assertThat(result).isInstanceOf(ProactivityJudgment.JudgmentResult.Discard.class);

        // Same drive at tier 2 (threshold 0.35) would pass
        var result2 = ProactivityJudgment.evaluate(ctx(drives, 2));
        assertThat(result2).isInstanceOf(ProactivityJudgment.JudgmentResult.Act.class);
    }

    @Test void threshold_for_tier_values() {
        assertThat(ProactivityJudgment.thresholdForTier(0)).isEqualTo(0.7);
        assertThat(ProactivityJudgment.thresholdForTier(1)).isEqualTo(0.5);
        assertThat(ProactivityJudgment.thresholdForTier(2)).isEqualTo(0.35);
        assertThat(ProactivityJudgment.thresholdForTier(3)).isEqualTo(0.2);
    }

    @Test void budget_refills_at_the_hourly_rate() {
        // Half an hour of refill on an empty bucket → half the hourly allowance
        assertThat(ProactivityJudgment.refillBudget(0.0, 1_800_000))
            .isCloseTo(1.5, org.assertj.core.data.Offset.offset(0.01));
        // A partially spent bucket tops up from where it was
        assertThat(ProactivityJudgment.refillBudget(1.0, 1_800_000))
            .isCloseTo(2.5, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test void budget_is_capped_and_never_goes_into_debt() {
        // Idling for a day banks at most one hour's worth — no unbounded credit
        assertThat(ProactivityJudgment.refillBudget(3.0, 86_400_000))
            .isEqualTo(ProactivityJudgment.MAX_BUDGET_PER_HOUR);
        // A negative level (defensive) refills toward zero, never below it
        assertThat(ProactivityJudgment.refillBudget(-100.0, 0)).isEqualTo(0.0);
    }

    @Test void budget_caps_proactive_speech_to_the_hourly_allowance() {
        // The rate pin. A companion whose drives sit above threshold evaluates on every
        // 1s vitality tick; if it speaks whenever it can afford to, the budget alone must
        // hold it to MAX_BUDGET_PER_HOUR ÷ observation-cost per hour — about ten. Live
        // 2026-08-17 a household companion spoke ~120×/hour for a week because held
        // actions were surfaced without consulting the budget at all, so this pins the
        // property the user actually feels: how often she speaks unprompted.
        double cost = new ProactiveAction.Observation("x", "seeking", "seeking").budgetCost();
        double level = ProactivityJudgment.MAX_BUDGET_PER_HOUR;
        int spoke = 0;
        for (int second = 0; second < 3600; second++) {
            level = ProactivityJudgment.refillBudget(level, 1000);
            if (level >= cost) {
                level -= cost;
                spoke++;
            }
        }
        // One full bucket at the start plus one hour of refill, all of it spent.
        assertThat(spoke).isLessThanOrEqualTo(
            (int) Math.ceil(2 * ProactivityJudgment.MAX_BUDGET_PER_HOUR / cost));
        assertThat(spoke).isLessThan(30);
    }

    @Test void spent_out_budget_recovers_in_bounded_time() {
        // The pre-2026-08-17 formula subtracted lifetime-spent from lifetime-elapsed, so an
        // agent that overspent early could never earn its way back and held FOREVER. A
        // bucket at zero must be able to afford an observation again after ~6 minutes
        // (0.3 cost ÷ 3.0 per hour).
        double afterSixMinutes = ProactivityJudgment.refillBudget(0.0, 360_000);
        assertThat(afterSixMinutes).isGreaterThanOrEqualTo(0.3);
    }

    @Test void act_result_contains_action() {
        var drives = DriveState.initial().spikeAlertness(0.8);
        var result = ProactivityJudgment.evaluate(ctx(drives, 2));
        assertThat(result).isInstanceOf(ProactivityJudgment.JudgmentResult.Act.class);
        var act = (ProactivityJudgment.JudgmentResult.Act) result;
        assertThat(act.action()).isNotNull();
        assertThat(act.action().driveName()).isEqualTo("vigilance");
    }
}
