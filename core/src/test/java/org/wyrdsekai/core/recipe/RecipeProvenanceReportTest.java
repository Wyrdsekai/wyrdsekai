package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * B1 — the provenance instrument's pure aggregation.
 * No DB: feeds terminal rows straight into the list overload.
 */
class RecipeProvenanceReportTest {

    private static final Instant T0 = Instant.parse("2026-05-20T00:00:00Z");

    private static QueuedRecipe terminal(QueuedRecipe.TriggerSource src, Instant completedAt) {
        return new QueuedRecipe("id-" + src + completedAt.toEpochMilli(), "some-recipe",
            Map.of(), "reason", src, completedAt.minusSeconds(60), completedAt.minusSeconds(30),
            completedAt, QueuedRecipe.Status.SUCCEEDED, "did:test:a",
            CadenceTier.WARMUP, 0, "run-x", "ok");
    }

    @Test
    void counts_each_source_and_computes_agent_fraction() {
        var rows = List.of(
            terminal(QueuedRecipe.TriggerSource.AGENT, T0.plusSeconds(10)),
            terminal(QueuedRecipe.TriggerSource.AGENT, T0.plusSeconds(20)),
            terminal(QueuedRecipe.TriggerSource.CRON, T0.plusSeconds(30)),
            terminal(QueuedRecipe.TriggerSource.GAP, T0.plusSeconds(40)),
            terminal(QueuedRecipe.TriggerSource.STEWARD, T0.plusSeconds(50)));

        var p = RecipeProvenanceReport.compute(rows, T0, T0.plus(Duration.ofDays(1)), 0);

        assertThat(p.agent()).isEqualTo(2);
        assertThat(p.cron()).isEqualTo(1);
        assertThat(p.gap()).isEqualTo(1);
        assertThat(p.steward()).isEqualTo(1);
        assertThat(p.authored()).isEqualTo(0);
        assertThat(p.agentInitiated()).isEqualTo(2);
        assertThat(p.total()).isEqualTo(5);
        assertThat(p.agentFraction()).isCloseTo(0.4, within(1e-9));
    }

    @Test
    void authored_acts_fold_into_agent_initiated() {
        var rows = List.of(
            terminal(QueuedRecipe.TriggerSource.CRON, T0.plusSeconds(10)),
            terminal(QueuedRecipe.TriggerSource.AGENT, T0.plusSeconds(20)));

        // 2 authored shape_recipe acts on top of 1 request_recipe run.
        var p = RecipeProvenanceReport.compute(rows, T0, T0.plus(Duration.ofDays(1)), 2);

        assertThat(p.authored()).isEqualTo(2);
        assertThat(p.agentInitiated()).isEqualTo(3);  // 1 AGENT run + 2 authored
        assertThat(p.total()).isEqualTo(4);            // +1 CRON
        assertThat(p.agentFraction()).isCloseTo(0.75, within(1e-9));
    }

    @Test
    void rows_outside_the_window_are_ignored() {
        var rows = List.of(
            terminal(QueuedRecipe.TriggerSource.AGENT, T0.minusSeconds(5)),   // before
            terminal(QueuedRecipe.TriggerSource.AGENT, T0.plusSeconds(5)),    // in
            terminal(QueuedRecipe.TriggerSource.CRON, T0.plus(Duration.ofDays(2)))); // after

        var p = RecipeProvenanceReport.compute(rows, T0, T0.plus(Duration.ofDays(1)), 0);

        assertThat(p.total()).isEqualTo(1);
        assertThat(p.agent()).isEqualTo(1);
    }

    @Test
    void empty_is_zero_fraction_not_nan() {
        var p = RecipeProvenanceReport.compute(List.of(), T0, T0.plus(Duration.ofDays(1)), 0);
        assertThat(p.total()).isEqualTo(0);
        assertThat(p.agentFraction()).isEqualTo(0.0);
        assertThat(p.trend()).isEmpty();
    }

    @Test
    void trend_buckets_by_day_ascending() {
        var day0 = T0.plusSeconds(10);
        var day1 = T0.plus(Duration.ofDays(1)).plusSeconds(10);
        var rows = List.of(
            terminal(QueuedRecipe.TriggerSource.AGENT, day0),
            terminal(QueuedRecipe.TriggerSource.CRON, day0),
            terminal(QueuedRecipe.TriggerSource.AGENT, day1));

        var p = RecipeProvenanceReport.compute(rows, T0, T0.plus(Duration.ofDays(3)), 0);

        assertThat(p.trend()).hasSize(2);
        assertThat(p.trend().get(0).epochDay()).isLessThan(p.trend().get(1).epochDay());
        assertThat(p.trend().get(0).agentInitiated()).isEqualTo(1);
        assertThat(p.trend().get(0).total()).isEqualTo(2);
        assertThat(p.trend().get(1).agentInitiated()).isEqualTo(1);
        assertThat(p.trend().get(1).total()).isEqualTo(1);
    }
}
