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
                e.recipeId(), Map.of(),
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
}
