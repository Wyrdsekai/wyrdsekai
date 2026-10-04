package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code world.llm.budget_remaining()} returned tokens 0, cost 0 and "reset in 24 h" every time
 * (2026-10-03), while the tracker beside it had the numbers and nothing recorded her own turns on
 * it. Now: every completed inference lands on her ledger under either of her names, the call
 * returns today's real numbers, and a cap is reported only when a steward set one.
 */
class TheBudgetToolTellsTheTruthTest {

    @Test
    void her_turns_count_under_either_of_her_names_and_the_call_returns_todays_numbers() {
        var tracker = new AgentCostTracker();
        tracker.alias("companion-rose", "did:key:zRoseExample");
        tracker.recordInference("companion-rose", 800, 1200, 150);     // what the router sees
        tracker.recordInference("did:key:zRoseExample", 500, 300, 40);  // what an item of hers records
        var zone = ZoneId.of("America/New_York");
        var now = Instant.parse("2026-10-03T20:15:00Z");
        var m = BudgetSnapshot.of(tracker, "did:key:zRoseExample", zone, now);
        assertThat(m).containsEntry("tracked", true)
            .containsEntry("tokens", 1690L)
            .containsEntry("inferencesToday", 2L)
            .containsEntry("promptTokensToday", 1500L)
            .containsEntry("completionTokensToday", 190L)
            .containsEntry("costUsd", 0.0)
            .containsEntry("capped", false)
            .doesNotContainKeys("budgetUsd", "remainingUsd");
        var resetAt = Instant.ofEpochMilli((Long) m.get("dailyResetAt"));
        assertThat(LocalDate.ofInstant(resetAt, zone)).as("the next local midnight").isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(resetAt.atZone(zone).toLocalTime().toSecondOfDay()).isZero();
        assertThat(BudgetSnapshot.of(tracker, "companion-rose", zone, now)).as("read back under the other name").containsEntry("tokens", 1690L);
    }

    @Test
    void an_alias_declared_after_spend_was_recorded_folds_it_in_and_a_cap_shows_only_when_set() {
        var tracker = new AgentCostTracker();
        tracker.recordInference("companion-mia", 100, 50, 10);
        tracker.alias("companion-mia", "did:key:zMiaExample");
        assertThat(tracker.today("did:key:zMiaExample").tokens()).isEqualTo(60);
        tracker.recordMcp("did:key:zMiaExample", "weather", 30, 0.02);
        tracker.setBudget("companion-mia", 1.00);
        var m = BudgetSnapshot.of(tracker, "did:key:zMiaExample", ZoneId.of("UTC"), Instant.parse("2026-10-03T12:00:00Z"));
        assertThat(m).containsEntry("capped", true).containsEntry("budgetUsd", 1.00);
        assertThat((Double) m.get("remainingUsd")).isCloseTo(0.98, org.assertj.core.data.Offset.offset(1e-9));
        assertThat((Double) m.get("costUsd")).isCloseTo(0.02, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void a_being_never_heard_of_and_a_node_without_a_tracker_say_so_instead_of_pretending() {
        var tracker = new AgentCostTracker();
        var m = BudgetSnapshot.of(tracker, "did:key:zNobody", ZoneId.of("UTC"), Instant.parse("2026-10-03T12:00:00Z"));
        assertThat(m).containsEntry("tracked", true).containsEntry("tokens", 0L).containsEntry("inferencesToday", 0L);
        var none = BudgetSnapshot.of(null, "did:key:zNobody", ZoneId.of("UTC"), Instant.parse("2026-10-03T12:00:00Z"));
        assertThat(none).containsEntry("tracked", false).containsEntry("capped", false).containsEntry("tokens", 0L);
    }

    @Test
    void the_router_records_every_completed_inference_on_the_asking_beings_ledger() {
        AgentCostTracker.init();
        org.wyrdsekai.core.inference.InferenceRouter.noteSpend("companion-rose", 700, 900, 120);
        assertThat(AgentCostTracker.get().today("companion-rose").tokens()).isEqualTo(1020);
        org.wyrdsekai.core.inference.InferenceRouter.noteSpend("", 1, 1, 1);  // nobody: nothing recorded, nothing thrown
    }
}
