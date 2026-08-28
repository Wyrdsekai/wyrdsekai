package org.wyrdsekai.core.recipe;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Track-C C1 — one row in the {@code recipe_queue} table.
 *
 * <p>Represents an enqueued (or completed) recipe run. The scheduler peeks
 * PENDING rows whose cadence period has elapsed since the last successful
 * run for the same (recipeId, agentDid) pair, marks them IN_PROGRESS,
 * dispatches via {@link RecipeService#run}, and then writes the terminal
 * outcome back. Cadence promotion/demotion is computed by
 * {@link CadenceLadder} and merged into the row atomically.</p>
 *
 * <p>Records are immutable; mutate via {@link SqlRecipeQueue#markAttempted}
 * and {@link SqlRecipeQueue#markCompleted}.</p>
 */
public record QueuedRecipe(
        String id,
        String recipeId,
        Map<String, Object> params,
        String triggerReason,
        TriggerSource triggerSource,
        Instant enqueuedAt,
        Instant attemptedAt,
        Instant completedAt,
        Status status,
        String agentDid,
        CadenceTier cadenceTier,
        int consecutiveSuccesses,
        String runId,
        String message) {

    public QueuedRecipe {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (recipeId == null || recipeId.isBlank())
            throw new IllegalArgumentException("recipeId required");
        if (enqueuedAt == null) throw new IllegalArgumentException("enqueuedAt required");
        if (status == null) status = Status.PENDING;
        if (triggerSource == null) triggerSource = TriggerSource.AGENT;
        if (cadenceTier == null) cadenceTier = CadenceTier.WARMUP;
        if (params == null) params = Map.of();
        else params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** Convenience for newly enqueued rows — no attempted/completed/runId yet. */
    public static QueuedRecipe newEntry(String id, String recipeId,
            Map<String, Object> params, String triggerReason,
            TriggerSource source, String agentDid,
            CadenceTier tier, int consecutiveSuccesses) {
        return new QueuedRecipe(id, recipeId, params, triggerReason,
            source, Instant.now(), null, null, Status.PENDING, agentDid,
            tier == null ? CadenceTier.WARMUP : tier, consecutiveSuccesses,
            null, null);
    }

    public boolean isTerminal() {
        return status == Status.SUCCEEDED || status == Status.FAILED;
    }

    /**
     * Status lifecycle: PENDING → IN_PROGRESS → SUCCEEDED|FAILED.
     */
    /**
     * {@code SKIPPED} is terminal but is NOT an outcome: the row left the queue without
     * the recipe ever running, because it could not run as configured. It is deliberately
     * excluded from the SUCCEEDED/FAILED queries behind the cadence ladder and the
     * deploy-failure ceiling — a configuration gap must neither break a success streak
     * nor consume a welfare mechanism meant for work that actually executed.
     */
    public enum Status { PENDING, IN_PROGRESS, SUCCEEDED, FAILED, SKIPPED }

    /**
     * Who put this in the queue — used both for audit and for prioritising
     * (e.g. agent-initiated runs preempt cron). Only the first two land in
     * C2 (cron + agent); GAP/STEWARD are wired in C4/C9 respectively.
     */
    public enum TriggerSource { CRON, GAP, AGENT, STEWARD }
}
