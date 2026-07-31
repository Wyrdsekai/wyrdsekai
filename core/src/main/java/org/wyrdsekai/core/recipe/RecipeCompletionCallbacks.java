package org.wyrdsekai.core.recipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A small process-wide registry of one-shot completion callbacks keyed by recipe
 * queue id. {@link RecipeScheduler#onCompletion} fires the callback (and removes it)
 * when a run reaches a terminal state, passing {@code true} on SUCCEEDED.
 *
 * <p>This keeps the scheduler decoupled from any recipe-specific follow-up: a caller
 * that enqueues a recipe and needs to react to its terminal outcome registers a
 * callback against the {@link QueuedRecipe#id()} it just enqueued, capturing whatever
 * context it needs in the closure. The first consumer is — the argot
 * re-bake trigger registers {@code succeeded -> ArgotRebakeService.complete(...)} so a
 * successful bake marks the new codebook version baked (resetting drift) and the
 * derived-key file is shredded on any terminal outcome.
 *
 * <p>Callbacks are one-shot: fired at most once, then removed. Unfired callbacks (e.g.
 * the process restarts before the run finishes) simply leak harmlessly — the argot loop
 * re-derives drift from in-memory state on the next consolidation tick, and the recipe's
 * own {@code cleanup-key} step plus the runtime's belt-and-suspenders shred the keyfile.
 */
public final class RecipeCompletionCallbacks {

    private static final Logger log = LoggerFactory.getLogger(RecipeCompletionCallbacks.class);

    private static final Map<String, Consumer<Boolean>> CALLBACKS = new ConcurrentHashMap<>();

    private RecipeCompletionCallbacks() {}

    /** Register a one-shot callback for {@code queueId}. Null id/callback is ignored. */
    public static void register(String queueId, Consumer<Boolean> onTerminal) {
        if (queueId == null || queueId.isBlank() || onTerminal == null) return;
        CALLBACKS.put(queueId, onTerminal);
    }

    /**
     * Fire (and remove) the callback for {@code queueId}, if any. {@code succeeded} is
     * {@code true} only when the run reached SUCCEEDED. Never throws — a misbehaving
     * callback is logged and swallowed so it can't break the scheduler.
     */
    public static void fireAndRemove(String queueId, boolean succeeded) {
        if (queueId == null) return;
        var cb = CALLBACKS.remove(queueId);
        if (cb == null) return;
        try {
            cb.accept(succeeded);
        } catch (Exception e) {
            log.warn("RecipeCompletionCallbacks: callback for {} threw: {}", queueId, e.toString());
        }
    }

    /** Drop a pending callback without firing it (e.g. the enqueue failed). */
    public static void cancel(String queueId) {
        if (queueId != null) CALLBACKS.remove(queueId);
    }

    /** Pending callback count — test/observability hook. */
    public static int pending() { return CALLBACKS.size(); }

    /** Drop all pending callbacks — test isolation only. */
    public static void resetForTests() { CALLBACKS.clear(); }
}
