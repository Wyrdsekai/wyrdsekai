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

    @Test void budget_computation() {
        // Full hour elapsed, nothing spent → full budget
        double budget = ProactivityJudgment.computeBudget(0.0, 3_600_000);
        assertThat(budget).isEqualTo(3.0);

        // Half hour elapsed, 1.0 spent → 0.5 remaining
        double budget2 = ProactivityJudgment.computeBudget(1.0, 1_800_000);
        assertThat(budget2).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01));
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
