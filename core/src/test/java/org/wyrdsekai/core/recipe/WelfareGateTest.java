package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.RepairMode;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.wyrdsekai.core.recipe.WelfareGate.DenyReason;
import static org.wyrdsekai.core.recipe.WelfareGate.evaluate;

/**
 * Track-C C3 — pure-logic gate transitions.
 *
 * <p>Each gate gets its own test; the integration test
 * ({@code RecipeSchedulerTest.welfare_gate_denial_keeps_row_PENDING})
 * covers the wiring between gate and scheduler.</p>
 */
class WelfareGateTest {

    @Test
    void null_inputs_allow_everything() {
        var d = evaluate(null);
        assertThat(d.allow()).isTrue();
        assertThat(d.reason()).isEqualTo(DenyReason.ALLOW);
    }

    @Test
    void healthy_inputs_allow() {
        var d = evaluate(healthy().build());
        assertThat(d.allow()).isTrue();
    }

    // ── (a) repair-mode + substrate-pressure ────────────────────────────

    @Test
    void any_agent_in_repair_denies_REPAIR_MODE_ACTIVE() {
        var d = evaluate(healthy()
            .activeRepairModes(EnumSet.of(RepairMode.NONE, RepairMode.BONDED))
            .build());
        assertThat(d.allow()).isFalse();
        assertThat(d.reason()).isEqualTo(DenyReason.REPAIR_MODE_ACTIVE);
        assertThat(d.detail()).contains("BONDED");
    }

    @Test
    void all_agents_NONE_does_not_trip_repair_gate() {
        var d = evaluate(healthy()
            .activeRepairModes(EnumSet.of(RepairMode.NONE))
            .build());
        assertThat(d.allow()).isTrue();
    }

    @Test
    void substrate_pressure_sustained_denies() {
        var d = evaluate(healthy()
            .substratePressureSustained(true)
            .build());
        assertThat(d.allow()).isFalse();
        assertThat(d.reason()).isEqualTo(DenyReason.SUBSTRATE_PRESSURE_SUSTAINED);
    }

    // ── (b) budget ──────────────────────────────────────────────────────

    @Test
    void gpu_budget_at_cap_denies_GPU_DAILY_BUDGET_EXCEEDED() {
        var d = evaluate(healthy()
            .gpuUsedToday(WelfareGate.DEFAULT_DAILY_GPU_BUDGET)
            .gpuDailyBudget(WelfareGate.DEFAULT_DAILY_GPU_BUDGET)
            .build());
        assertThat(d.allow()).isFalse();
        assertThat(d.reason()).isEqualTo(DenyReason.GPU_DAILY_BUDGET_EXCEEDED);
        assertThat(d.detail()).contains("6h").contains("cap=");
    }

    @Test
    void gpu_just_under_budget_allows() {
        var d = evaluate(healthy()
            .gpuUsedToday(Duration.ofMinutes(359))
            .gpuDailyBudget(Duration.ofHours(6))
            .build());
        assertThat(d.allow()).isTrue();
    }

    @Test
    void monthly_run_cap_at_limit_denies_MONTHLY_RUN_CAP_EXCEEDED() {
        var d = evaluate(healthy()
            .runsThisMonth(100)
            .monthlyRunCap(100)
            .build());
        assertThat(d.allow()).isFalse();
        assertThat(d.reason()).isEqualTo(DenyReason.MONTHLY_RUN_CAP_EXCEEDED);
    }

    @Test
    void monthly_cap_zero_disables_the_gate() {
        // A 0 cap means "no limit" so the gate must not deny.
        var d = evaluate(healthy()
            .runsThisMonth(10_000)
            .monthlyRunCap(0)
            .build());
        assertThat(d.allow()).isTrue();
    }

    // ── (c) cooldown ────────────────────────────────────────────────────

    @Test
    void cooldown_not_elapsed_denies_when_last_run_too_recent() {
        var now = Instant.parse("2026-06-01T12:00:00Z");
        var d = evaluate(healthy()
            .now(now)
            .currentTier(CadenceTier.SETTLING)  // 3-day period
            .lastTerminalAt(now.minus(Duration.ofDays(1)))
            .build());
        assertThat(d.allow()).isFalse();
        assertThat(d.reason()).isEqualTo(DenyReason.COOLDOWN_NOT_ELAPSED);
        assertThat(d.detail()).contains("tier=SETTLING");
    }

    @Test
    void cooldown_elapsed_allows() {
        var now = Instant.parse("2026-06-01T12:00:00Z");
        var d = evaluate(healthy()
            .now(now)
            .currentTier(CadenceTier.WARMUP)    // 1-day period
            .lastTerminalAt(now.minus(Duration.ofDays(1).plusMinutes(1)))
            .build());
        assertThat(d.allow()).isTrue();
    }

    @Test
    void no_prior_run_allows_cooldown_gate() {
        // Fresh enrollment — no terminal yet means no cooldown to wait on.
        var d = evaluate(healthy()
            .lastTerminalAt(null)
            .build());
        assertThat(d.allow()).isTrue();
    }

    // ── (d) deploy-ceiling ──────────────────────────────────────────────

    @Test
    void deploy_ceiling_at_limit_denies_DEPLOY_CEILING_HIT() {
        var d = evaluate(healthy()
            .consecutiveDeployFailures(WelfareGate.DEPLOY_CEILING)
            .build());
        assertThat(d.allow()).isFalse();
        assertThat(d.reason()).isEqualTo(DenyReason.DEPLOY_CEILING_HIT);
    }

    @Test
    void deploy_ceiling_one_below_limit_allows() {
        var d = evaluate(healthy()
            .consecutiveDeployFailures(WelfareGate.DEPLOY_CEILING - 1)
            .build());
        assertThat(d.allow()).isTrue();
    }

    // ── ordering — repair > budget > cooldown > deploy ──────────────────

    @Test
    void repair_mode_wins_over_lower_gates() {
        var d = evaluate(healthy()
            .activeRepairModes(EnumSet.of(RepairMode.SELF))
            .gpuUsedToday(Duration.ofHours(10))
            .consecutiveDeployFailures(99)
            .build());
        // Repair-mode is checked first — the gate stops there.
        assertThat(d.reason()).isEqualTo(DenyReason.REPAIR_MODE_ACTIVE);
    }

    @Test
    void budget_wins_over_cooldown_when_both_trip() {
        var now = Instant.now();
        var d = evaluate(healthy()
            .gpuUsedToday(Duration.ofHours(7))
            .gpuDailyBudget(Duration.ofHours(6))
            .currentTier(CadenceTier.SETTLING)
            .lastTerminalAt(now.minusSeconds(60))
            .now(now)
            .build());
        assertThat(d.reason()).isEqualTo(DenyReason.GPU_DAILY_BUDGET_EXCEEDED);
    }

    // ── builder helpers ─────────────────────────────────────────────────

    private static InputsBuilder healthy() {
        return new InputsBuilder();
    }

    /**
     * Mutable builder used only by this test — keeps each test concise +
     * lets each assert focus on one gate by overriding just the field
     * under test against a known-healthy baseline.
     */
    private static final class InputsBuilder {
        Set<RepairMode> activeRepairModes = Set.of(RepairMode.NONE);
        boolean substratePressureSustained = false;
        Duration gpuUsedToday = Duration.ZERO;
        Duration gpuDailyBudget = Duration.ofHours(6);
        int runsThisMonth = 0;
        int monthlyRunCap = 100;
        Instant lastTerminalAt = null;
        CadenceTier currentTier = CadenceTier.WARMUP;
        int consecutiveDeployFailures = 0;
        Instant now = Instant.now();

        InputsBuilder activeRepairModes(Set<RepairMode> v) { activeRepairModes = v; return this; }
        InputsBuilder substratePressureSustained(boolean v) { substratePressureSustained = v; return this; }
        InputsBuilder gpuUsedToday(Duration v) { gpuUsedToday = v; return this; }
        InputsBuilder gpuDailyBudget(Duration v) { gpuDailyBudget = v; return this; }
        InputsBuilder runsThisMonth(int v) { runsThisMonth = v; return this; }
        InputsBuilder monthlyRunCap(int v) { monthlyRunCap = v; return this; }
        InputsBuilder lastTerminalAt(Instant v) { lastTerminalAt = v; return this; }
        InputsBuilder currentTier(CadenceTier v) { currentTier = v; return this; }
        InputsBuilder consecutiveDeployFailures(int v) { consecutiveDeployFailures = v; return this; }
        InputsBuilder now(Instant v) { now = v; return this; }

        WelfareGate.Inputs build() {
            return new WelfareGate.Inputs(activeRepairModes, substratePressureSustained,
                gpuUsedToday, gpuDailyBudget, runsThisMonth, monthlyRunCap,
                lastTerminalAt, currentTier, consecutiveDeployFailures, now);
        }
    }
}
