package org.wyrdsekai.core.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Process-wide, per-DID log of completed recipe runs awaiting Forge ingestion.
 *
 * <p>Recipe runs originate inside the GraalJS item sandbox ({@code world.recipe.run(...)}),
 * which is decoupled from the {@code CompanionActor} that owns the agent's soul. A run completed
 * in a room script therefore can't be appended to an in-actor buffer directly. This singleton is
 * the decoupling seam — the same role {@code SkillDraftStore.get()} plays for script-authored
 * skill drafts: {@link RecipeService} records on completion; {@code CompanionActor.completeSleep}
 * {@link #drain(String)}s and hands the batch to {@link RecipeForgeIngester}.</p>
 *
 * <p>Drain is destructive (mirrors {@code bunshinReportsSinceLastSleep.clear()} after ingest):
 * each completed run contributes to exactly one Forge pass. Thread-safe — runs are recorded on
 * script-execution threads and drained on the actor's sleep path.</p>
 */
public final class RecipeRunLog {

    private static final RecipeRunLog INSTANCE = new RecipeRunLog();

    /** Process singleton. */
    public static RecipeRunLog get() {
        return INSTANCE;
    }

    private final Map<String, Queue<RecipeForgeIngester.CompletedRun>> byDid = new ConcurrentHashMap<>();

    private RecipeRunLog() {}

    /** Record a completed run for {@code agentDid}. No-op when the DID is null/blank. */
    public void record(String agentDid, RecipeForgeIngester.CompletedRun run) {
        if (agentDid == null || agentDid.isBlank() || run == null) return;
        byDid.computeIfAbsent(agentDid, k -> new ConcurrentLinkedQueue<>()).add(run);
    }

    /**
     * Remove and return all completed runs recorded for {@code agentDid} since the last drain.
     * Returns an empty list when there are none (or the DID is null/blank).
     */
    public List<RecipeForgeIngester.CompletedRun> drain(String agentDid) {
        if (agentDid == null || agentDid.isBlank()) return List.of();
        Queue<RecipeForgeIngester.CompletedRun> q = byDid.remove(agentDid);
        if (q == null || q.isEmpty()) return List.of();
        return new ArrayList<>(q);
    }

    /** Test/diagnostic: number of pending runs for a DID without draining. */
    public int pending(String agentDid) {
        if (agentDid == null) return 0;
        Queue<RecipeForgeIngester.CompletedRun> q = byDid.get(agentDid);
        return q == null ? 0 : q.size();
    }
}
