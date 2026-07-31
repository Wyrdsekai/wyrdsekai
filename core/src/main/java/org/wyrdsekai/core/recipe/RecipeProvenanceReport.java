package org.wyrdsekai.core.recipe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * B1 — the provenance instrument.
 *
 * <p>The one number that matters for agent-driven development is the
 * <b>agent-initiated fraction</b> of all maintenance activity, and whether it is
 * trending up. Every completed {@link QueuedRecipe} carries a
 * {@link QueuedRecipe.TriggerSource} (persisted in {@code recipe_queue}), so the
 * ground truth already exists; this class only counts it.</p>
 *
 * <ul>
 *   <li>{@code AGENT} — the companion itself decided ({@code request_recipe}).</li>
 *   <li>Plus <b>authored</b> recipes ({@code shape_recipe}, ITEM D) — these write a
 *       file rather than a queue row, so the caller folds in a separately-counted
 *       {@code authoredCount}. Authoring is the purest self-actualization act and
 *       must not be invisible.</li>
 *   <li>{@code CRON}/{@code GAP} — the system decided; {@code STEWARD} — a human did.</li>
 * </ul>
 *
 * <p>Pure aggregation: the SQL hop is {@link SqlRecipeQueue#completedSince} (or
 * pass rows directly for tests). Day buckets use epoch-day
 * ({@code completedAt / 86_400_000}) so there is no timezone or {@code Date.now}
 * dependency — the caller formats the day if it wants a calendar label.</p>
 */
public final class RecipeProvenanceReport {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private RecipeProvenanceReport() {}

    /** A window of interest. {@code agentDid} null = household-wide. */
    public record Window(Instant from, Instant to, String agentDid) {}

    /** One day's slice of the trend: agent-initiated vs total terminal runs. */
    public record DailyPoint(long epochDay, int agentInitiated, int total) {}

    /**
     * The provenance breakdown over a window. {@code agentInitiated} =
     * {@code agent} (request_recipe runs) + {@code authored} (shape_recipe files).
     * {@code agentFraction} is over {@code total} = all terminal runs + authored.
     */
    public record Provenance(
        int agent,
        int authored,
        int gap,
        int cron,
        int steward,
        int agentInitiated,
        int total,
        double agentFraction,
        List<DailyPoint> trend) {}

    /**
     * Compute from a live queue. {@code authoredCount} comes from
     * {@link AuthoredRecipeLog#countSince} (0 if no log available).
     */
    public static Provenance compute(SqlRecipeQueue queue, Window w, int authoredCount) {
        List<QueuedRecipe> rows = queue.completedSince(w.from(), w.agentDid());
        return compute(rows, w.from(), w.to(), authoredCount);
    }

    /**
     * Pure overload — group an in-hand list. Rows outside [from, to] by
     * {@code completedAt} are ignored so a generous DB fetch can be trimmed here.
     * Authored acts are bucketed on the {@code to} day (we record the count, not
     * per-author timestamps, at this layer).
     */
    public static Provenance compute(List<QueuedRecipe> rows, Instant from, Instant to,
                                     int authoredCount) {
        int agent = 0, gap = 0, cron = 0, steward = 0;
        // epochDay -> [agentInitiated, total]
        Map<Long, int[]> byDay = new TreeMap<>();
        long fromMs = from.toEpochMilli();
        long toMs = to.toEpochMilli();

        for (var r : rows) {
            if (r.completedAt() == null) continue;
            long ms = r.completedAt().toEpochMilli();
            if (ms < fromMs || ms > toMs) continue;
            var src = r.triggerSource() == null
                ? QueuedRecipe.TriggerSource.AGENT : r.triggerSource();
            boolean isAgent = src == QueuedRecipe.TriggerSource.AGENT;
            switch (src) {
                case AGENT -> agent++;
                case GAP -> gap++;
                case CRON -> cron++;
                case STEWARD -> steward++;
            }
            long day = ms / MILLIS_PER_DAY;
            var slot = byDay.computeIfAbsent(day, k -> new int[2]);
            if (isAgent) slot[0]++;
            slot[1]++;
        }

        int authored = Math.max(0, authoredCount);
        if (authored > 0) {
            long day = toMs / MILLIS_PER_DAY;
            var slot = byDay.computeIfAbsent(day, k -> new int[2]);
            slot[0] += authored;
            slot[1] += authored;
        }

        int agentInitiated = agent + authored;
        int total = agent + gap + cron + steward + authored;
        double fraction = total == 0 ? 0.0 : (double) agentInitiated / total;

        List<DailyPoint> trend = new ArrayList<>();
        for (var e : byDay.entrySet()) {
            trend.add(new DailyPoint(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }

        return new Provenance(agent, authored, gap, cron, steward,
            agentInitiated, total, fraction, trend);
    }
}
