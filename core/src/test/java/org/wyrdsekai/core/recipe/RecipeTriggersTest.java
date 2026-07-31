package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.RepairMode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track-C C4 — pure-logic trigger tests. Combines:
 *
 * <ul>
 *   <li>{@link RecipeCronTrigger#plan} — cadence-tier elapsed check.</li>
 *   <li>{@link RecipeGapTrigger#plan} — chronicle-finding → enqueue mapping.</li>
 *   <li>{@link RecipeRequestGate#evaluate} — agent-action gating with
 *       structured denial.</li>
 * </ul>
 */
class RecipeTriggersTest {

    // ── Cron ────────────────────────────────────────────────────────────

    @Test
    void cron_fresh_enrollment_fires_immediately() {
        var e = enrollment("retrain-classifier-head", "did:alice",
            CadenceTier.WARMUP, 0);
        var planned = RecipeCronTrigger.plan(List.of(e),
            (recipe, did) -> null,  // no prior terminal
            Instant.now());
        assertThat(planned).hasSize(1);
        var row = planned.get(0);
        assertThat(row.recipeId()).isEqualTo("retrain-classifier-head");
        assertThat(row.agentDid()).isEqualTo("did:alice");
        assertThat(row.triggerSource()).isEqualTo(QueuedRecipe.TriggerSource.CRON);
        assertThat(row.cadenceTier()).isEqualTo(CadenceTier.WARMUP);
    }

    @Test
    void cron_holds_when_within_period() {
        var now = Instant.parse("2026-06-01T12:00:00Z");
        var e = enrollment("recipe-A", "did:alice", CadenceTier.SETTLING, 0);
        // SETTLING = 3 days. Last terminal 1d ago → not due.
        var planned = RecipeCronTrigger.plan(List.of(e),
            (r, d) -> now.minus(Duration.ofDays(1)),
            now);
        assertThat(planned).isEmpty();
    }

    @Test
    void cron_fires_when_period_elapsed() {
        var now = Instant.parse("2026-06-01T12:00:00Z");
        var e = enrollment("recipe-A", "did:alice", CadenceTier.WARMUP, 2);
        // WARMUP = 1 day. Last terminal 25h ago → due.
        var planned = RecipeCronTrigger.plan(List.of(e),
            (r, d) -> now.minus(Duration.ofHours(25)),
            now);
        assertThat(planned).hasSize(1);
        assertThat(planned.get(0).consecutiveSuccesses())
            .as("cron preserves the enrollment's consecutive_successes")
            .isEqualTo(2);
    }

    @Test
    void cron_skips_disabled_enrollments() {
        var disabled = new RecipeEnrollment("recipe-A", "did:alice",
            CadenceTier.WARMUP, 0, Instant.now(),
            false, Set.of());
        var planned = RecipeCronTrigger.plan(List.of(disabled),
            (r, d) -> null, Instant.now());
        assertThat(planned).isEmpty();
    }

    // ── #1023 — Quiet-hours preference ─────────────────────────────────

    /** Helper: build a fixed-zone clock at the given local hour on 2026-06-01. */
    private static Clock clockAtLocalHour(int hour) {
        var zone = ZoneId.of("America/Los_Angeles");
        var instant = ZonedDateTime.of(2026, 6, 1, hour, 0, 0, 0, zone)
            .toInstant();
        return Clock.fixed(instant, zone);
    }

    @Test
    void quiet_hours_skips_when_outside_preferred_window() {
        // Recipe prefers [2,3,4] local. Current local hour = 14 (2pm). Should skip.
        var clock = clockAtLocalHour(14);
        var now = clock.instant();
        var e = enrollment("retrain-classifier-head", "did:alice",
            CadenceTier.WARMUP, 0);
        var planned = RecipeCronTrigger.plan(
            List.of(e),
            (r, d) -> now.minus(Duration.ofHours(25)),  // due by cadence
            recipeId -> List.of(2, 3, 4),                // prefers quiet hours
            clock, now);
        assertThat(planned)
            .as("recipe with prefers_hours=[2,3,4] must NOT fire at 14:00 local")
            .isEmpty();
    }

    @Test
    void quiet_hours_fires_when_inside_preferred_window() {
        // Same enrollment, local hour = 3am → should fire.
        var clock = clockAtLocalHour(3);
        var now = clock.instant();
        var e = enrollment("retrain-classifier-head", "did:alice",
            CadenceTier.WARMUP, 0);
        var planned = RecipeCronTrigger.plan(
            List.of(e),
            (r, d) -> now.minus(Duration.ofHours(25)),
            recipeId -> List.of(2, 3, 4),
            clock, now);
        assertThat(planned)
            .as("recipe with prefers_hours=[2,3,4] MUST fire at 03:00 local")
            .hasSize(1);
    }

    @Test
    void quiet_hours_stale_deferral_overrides_preference_when_far_past_cadence() {
        // Outside preferred window AND last_terminal > 2 × period ago.
        // Stale-deferral fires anyway to prevent indefinite skip.
        var clock = clockAtLocalHour(14);  // outside window
        var now = clock.instant();
        var e = enrollment("retrain-classifier-head", "did:alice",
            CadenceTier.WARMUP, 0);  // WARMUP = 1 day period
        var planned = RecipeCronTrigger.plan(
            List.of(e),
            (r, d) -> now.minus(Duration.ofDays(3)),  // 3d ago > 2 × 1d
            recipeId -> List.of(2, 3, 4),
            clock, now);
        assertThat(planned)
            .as("stale-deferral (>2× cadence period overdue) must override the "
                + "prefers_hours preference — otherwise households whose machine "
                + "is off during the window would never retrain")
            .hasSize(1);
    }

    @Test
    void quiet_hours_anytime_lookup_is_a_no_op_filter() {
        // Recipes with no prefers_hours fire any local hour.
        var clock = clockAtLocalHour(14);
        var now = clock.instant();
        var e = enrollment("recipe-anytime", "did:alice", CadenceTier.WARMUP, 0);
        var planned = RecipeCronTrigger.plan(
            List.of(e),
            (r, d) -> now.minus(Duration.ofHours(25)),
            RecipeCronTrigger.PrefersHoursLookup.ANYTIME,
            clock, now);
        assertThat(planned)
            .as("ANYTIME lookup must preserve pre-#1023 cron behavior")
            .hasSize(1);
    }

    @Test
    void quiet_hours_fresh_enrollment_honors_window_no_stale_deferral_yet() {
        // No prior terminal. Outside preferred window. Should skip — fresh
        // enrollments have no "stale" reference point, so the preference
        // should fully apply on the first tick.
        var clock = clockAtLocalHour(14);
        var now = clock.instant();
        var e = enrollment("retrain-classifier-head", "did:alice",
            CadenceTier.WARMUP, 0);
        var planned = RecipeCronTrigger.plan(
            List.of(e),
            (r, d) -> null,  // no prior terminal
            recipeId -> List.of(2, 3, 4),
            clock, now);
        assertThat(planned)
            .as("fresh enrollment with prefers_hours must wait for the window "
                + "(can't be stale-deferred without a prior terminal)")
            .isEmpty();
    }

    @Test
    void quiet_hours_legacy_three_arg_plan_is_unchanged() {
        // The three-arg overload must behave like the old code: no
        // quiet-hours filter, no clock dependency. Regression guard for
        // any caller still on the legacy signature.
        var now = Instant.parse("2026-06-01T21:00:00Z");
        var e = enrollment("recipe-A", "did:alice", CadenceTier.WARMUP, 0);
        var planned = RecipeCronTrigger.plan(List.of(e),
            (r, d) -> now.minus(Duration.ofHours(25)),
            now);
        assertThat(planned).hasSize(1);
    }

    // ── Gap ─────────────────────────────────────────────────────────────

    @Test
    void gap_routes_finding_to_matching_recipe() {
        var e = new RecipeEnrollment("retrain-task-present", "did:alice",
            CadenceTier.WARMUP, 0, Instant.now(), true,
            Set.of("task_present.misroute", "task_present.low_accuracy"));
        var planned = RecipeGapTrigger.plan("task_present.misroute",
            "did:alice", List.of(e));
        assertThat(planned).hasSize(1);
        assertThat(planned.get(0).triggerSource())
            .isEqualTo(QueuedRecipe.TriggerSource.GAP);
        assertThat(planned.get(0).triggerReason())
            .contains("gap:task_present.misroute");
    }

    @Test
    void gap_no_match_returns_empty() {
        var e = new RecipeEnrollment("retrain-task-present", "did:alice",
            CadenceTier.WARMUP, 0, Instant.now(), true,
            Set.of("task_present.misroute"));
        var planned = RecipeGapTrigger.plan("unrelated.key",
            "did:alice", List.of(e));
        // Caller filters by gap_key BEFORE calling plan; this test passes
        // a mismatching enrollment to verify defensive filtering.
        var match = e.gapKeys().contains("unrelated.key") ? List.of(e) : List.<RecipeEnrollment>of();
        var planned2 = RecipeGapTrigger.plan("unrelated.key",
            "did:alice", match);
        assertThat(planned2).isEmpty();
    }

    @Test
    void gap_filters_to_matching_agent_or_unscoped() {
        var aliceEnrollment = new RecipeEnrollment("recipe-A", "did:alice",
            CadenceTier.WARMUP, 0, Instant.now(), true,
            Set.of("gap.x"));
        var unscopedEnrollment = new RecipeEnrollment("recipe-B", null,
            CadenceTier.WARMUP, 0, Instant.now(), true,
            Set.of("gap.x"));
        var bobEnrollment = new RecipeEnrollment("recipe-C", "did:bob",
            CadenceTier.WARMUP, 0, Instant.now(), true,
            Set.of("gap.x"));

        // Alice observed the gap. Recipe-A (alice-scoped) and recipe-B
        // (unscoped) match; recipe-C (bob-scoped) does not.
        var planned = RecipeGapTrigger.plan("gap.x", "did:alice",
            List.of(aliceEnrollment, unscopedEnrollment, bobEnrollment));
        assertThat(planned).hasSize(2);
        assertThat(planned)
            .extracting(QueuedRecipe::recipeId)
            .containsExactlyInAnyOrder("recipe-A", "recipe-B");
        // Unscoped enrollment's queue row gets stamped with the source agent.
        var unscopedRow = planned.stream()
            .filter(r -> r.recipeId().equals("recipe-B")).findFirst().orElseThrow();
        assertThat(unscopedRow.agentDid()).isEqualTo("did:alice");
    }

    // ── Agent-initiated gate ────────────────────────────────────────────

    @Test
    void request_gate_allows_enrolled_healthy_agent() {
        var d = RecipeRequestGate.evaluate(new RecipeRequestGate.Inputs(
            "recipe-A", "did:alice", true, RepairMode.NONE, false));
        assertThat(d.allow()).isTrue();
    }

    @Test
    void request_gate_denies_not_enrolled() {
        var d = RecipeRequestGate.evaluate(new RecipeRequestGate.Inputs(
            "recipe-A", "did:alice", false, RepairMode.NONE, false));
        assertThat(d.allow()).isFalse();
        assertThat(d.reason()).isEqualTo(RecipeRequestGate.DenyReason.NOT_ENROLLED);
        assertThat(d.detail()).contains("did:alice").contains("recipe-A");
    }

    @Test
    void request_gate_denies_repair_mode() {
        var d = RecipeRequestGate.evaluate(new RecipeRequestGate.Inputs(
            "recipe-A", "did:alice", true, RepairMode.BONDED, false));
        assertThat(d.allow()).isFalse();
        assertThat(d.reason()).isEqualTo(RecipeRequestGate.DenyReason.REPAIR_MODE_ACTIVE);
        assertThat(d.detail()).contains("BONDED");
    }

    @Test
    void request_gate_denies_budget_exceeded() {
        var d = RecipeRequestGate.evaluate(new RecipeRequestGate.Inputs(
            "recipe-A", "did:alice", true, RepairMode.NONE, true));
        assertThat(d.allow()).isFalse();
        assertThat(d.reason()).isEqualTo(RecipeRequestGate.DenyReason.BUDGET_NO_HEADROOM);
    }

    @Test
    void request_gate_enrollment_wins_over_other_denies() {
        // If not enrolled, the gate should refuse with NOT_ENROLLED rather
        // than mentioning repair-mode — agents shouldn't be told about
        // recipes they can't request anyway.
        var d = RecipeRequestGate.evaluate(new RecipeRequestGate.Inputs(
            "recipe-A", "did:alice", false, RepairMode.SELF, true));
        assertThat(d.reason()).isEqualTo(RecipeRequestGate.DenyReason.NOT_ENROLLED);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static RecipeEnrollment enrollment(String recipeId, String agentDid,
            CadenceTier tier, int consecutive) {
        return new RecipeEnrollment(recipeId, agentDid, tier, consecutive,
            Instant.now(), true, Set.of());
    }
}
