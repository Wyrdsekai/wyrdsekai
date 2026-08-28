package org.wyrdsekai.core.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Track-C C4 — gap-detection trigger source.
 *
 * <p>Pure-logic mapping from a {@code ChronicleService.detectAll}
 * finding (or any other "sustained gap" signal) into recipe enqueues.
 * The chronicle hook calls
 * {@link #plan(String, String, List)} with the gap key + the agent the
 * gap was observed on; this class consults the enrollment list
 * (typically {@link RecipeEnrollmentStore#listByGapKey}) and emits
 * the matching enqueues.</p>
 *
 * <p>Gap keys are free-form strings — by convention {@code
 * "<classifier>.<failure-mode>"} (e.g. {@code "task_present.misroute"},
 * {@code "request_type.substrate_sensitivity"}). Recipes declare which
 * keys they can heal in their enrollment row's {@code gap_keys} set.
 * Multiple recipes can match a single finding (different agents in the
 * same zone share a gap); each gets its own queue row.</p>
 */
public final class RecipeGapTrigger {

    private RecipeGapTrigger() {}

    /**
     * Translate a chronicle gap finding into a list of planned enqueues.
     *
     * @param gapKey          conventional key (e.g. "task_present.misroute").
     *                        Empty/null → no plan.
     * @param sourceAgentDid  the agent the gap was observed on; used as
     *                        {@code trigger_reason} attribution + filters
     *                        enrollments to that agent (or unscoped
     *                        enrollments with agent_did=null).
     * @param matching        enrollments that declared {@code gapKey} —
     *                        typically {@code store.listByGapKey(gapKey)}.
     */
    public static List<QueuedRecipe> plan(String gapKey, String sourceAgentDid,
            List<RecipeEnrollment> matching) {
        if (gapKey == null || gapKey.isBlank()) return List.of();
        if (matching == null || matching.isEmpty()) return List.of();
        var out = new ArrayList<QueuedRecipe>();
        for (var e : matching) {
            if (!e.enabled()) continue;
            // Match enrollment-agent OR an unscoped (agent_did=null) enrollment
            // that applies to whoever observed the gap.
            boolean applies = e.agentDid() == null
                || e.agentDid().equals(sourceAgentDid);
            if (!applies) continue;
            out.add(QueuedRecipe.newEntry(
                UUID.randomUUID().toString(),
                e.recipeId(), paramsFor(gapKey),
                "gap:" + gapKey
                    + (sourceAgentDid != null ? " (agent=" + sourceAgentDid + ")"
                                              : ""),
                QueuedRecipe.TriggerSource.GAP,
                e.agentDid() != null ? e.agentDid() : sourceAgentDid,
                e.cadenceTier(),
                e.consecutiveSuccesses()));
        }
        return out;
    }

    /**
     * Derive run params from the gap key.
     *
     * <p>The key convention is {@code "<classifier>.<failure-mode>"}, so the key already
     * names the head that misrouted — and {@code retrain-classifier-head} declares
     * {@code head} as a required param with no default for exactly that reason. The
     * trigger passed {@link Map#of()} instead, so every gap-fired run failed fast on
     * "missing required params: head", and three of those tripped the consecutive-deploy
     * -failure ceiling, pausing the recipe until a steward cleared it (found live
     * 2026-08-18 after 14 failed runs). The one piece of information the gap key exists
     * to carry was being dropped one line before it was needed.
     *
     * <p>A key with no {@code .} yields no params rather than a guess.
     */
    static Map<String, Object> paramsFor(String gapKey) {
        int dot = gapKey.lastIndexOf('.');
        if (dot <= 0) return Map.of();
        var head = gapKey.substring(0, dot);
        return head.isBlank() ? Map.of() : Map.of("head", head);
    }
}
