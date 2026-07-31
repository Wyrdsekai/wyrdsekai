package org.wyrdsekai.core.agent.affordance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * the mechanism. Ranks the <b>already-permitted</b>
 * candidate tools by need-relative relevance and returns the top-K, with the
 * OODA-decided verb forced to the front.
 *
 * <p><b>The §4 boundary is structural here:</b> {@link #rank} only reorders and
 * truncates the {@code candidates} it is given — it never adds a tool, and it has no
 * access to {@code ActionPolicy} tiers / CONSENT gates / grants. Relevance in;
 * permission stays upstream. (Tuning the affordance store can change which permitted
 * tools rank high; it can never make a forbidden tool reachable.)</p>
 *
 * <p>Pure: no actor, no IO. Caller passes the live need pressures (drives + tanks),
 * the candidate {@code (name, payload)} pairs, and a resolver name → {@link
 * ToolAffordance} (store override, else {@link AffordanceSeed}).</p>
 */
public final class ToolAffordanceRanker {

    /**
     * How hard a plain match to the request pushes a tool up the menu.
     *
     * <p>Drive scores land around 0.3–1.5 (baseSalience plus served-need × pressure), so a
     * weight of 2.0 means a tool that unmistakably answers the question outranks a tool at
     * full drive pressure. That is deliberate: the agent should still be drawn toward what
     * it needs, but it must never be UNABLE to reach the thing it was asked for. A partial
     * match (0.3–0.5) only nudges, and ties still fall back to need.</p>
     */
    private static final double RELEVANCE_WEIGHT = 2.0;

    private ToolAffordanceRanker() {}

    /**
     * @param needPressures current drive/tank levels (e.g. collectDriveLevels + generativity)
     * @param forcedFront   a verb to pin at position 0 if it's among the candidates
     *                      (the OODA already decided it) — may be null
     * @param candidates    permitted tool names, in their current order
     * @param resolve       name → ToolAffordance (store-or-seed)
     * @param topK          keep at most this many; &le;0 keeps all
     * @return the ranked, truncated name list (a sublist of {@code candidates})
     */
    public static List<String> rank(Map<String, Double> needPressures, String forcedFront,
            List<String> candidates, Function<String, ToolAffordance> resolve, int topK) {
        return rank(needPressures, forcedFront, candidates, resolve, topK, null);
    }

    /**
     * As above, plus a RELEVANCE score for the thing that was actually asked.
     *
     * <p>Ranking on drive-pressure alone means the menu is shaped by the agent's mood and
     * not by the request. Measured on second-node 2026-07-13: asked "what is 17 times 3?", mia's
     * eight surfaced tools were {@code summon_familiar, dispatch_bunshin, bunshin_check_in}
     * — the calculator was one of her 110 tools and had no path onto the menu at all. She
     * could not call a tool she was never shown, so she delegated the arithmetic to the
     * coding backend, which reported SUCCESS having done nothing. That is the root of the
     * whole "talks but doesn't do" family: the model can only choose from what it is shown.
     *
     * <p>Relevance is ADDED to need, never substituted for it. The need-ranked surface is
     * load-bearing (the 2026-05-30 ablation: a flat menu makes small models pick whatever
     * ranks high in the abstract) — an agent still reaches for what it is drawn to. This
     * only guarantees that what the person actually asked for can also be reached.
     *
     * @param relevance name → 0..1 relevance to the current request; null = need-only
     */
    public static List<String> rank(Map<String, Double> needPressures, String forcedFront,
            List<String> candidates, Function<String, ToolAffordance> resolve, int topK,
            Function<String, Double> relevance) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        var pressures = needPressures == null ? Map.<String, Double>of() : needPressures;

        // Stable order index so equal scores keep their original sequence.
        var idx = new HashMap<String, Integer>();
        for (int i = 0; i < candidates.size(); i++) idx.putIfAbsent(candidates.get(i), i);

        // Relevance outweighs a drive at full pressure. A tool that plainly answers the
        // question must not lose its slot to a tool the agent merely feels like using —
        // otherwise a factual request still gets a mood-shaped menu, which is the bug.
        var ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator
            .comparingDouble((String name) -> -(resolve.apply(name).score(pressures)
                + (relevance == null ? 0.0 : RELEVANCE_WEIGHT * relevance.apply(name))))
            .thenComparingInt(idx::get));

        // The OODA already chose this verb upstream — don't let the model re-litigate
        // it against the rest of the menu; pin it first.
        if (forcedFront != null && ranked.remove(forcedFront)) ranked.add(0, forcedFront);

        if (topK > 0 && ranked.size() > topK) return new ArrayList<>(ranked.subList(0, topK));
        return ranked;
    }

    /** The need with the highest current pressure (for the instrument's "dominant need"). */
    public static String dominantNeed(Map<String, Double> needPressures) {
        if (needPressures == null || needPressures.isEmpty()) return null;
        return needPressures.entrySet().stream()
            .max(Comparator.comparingDouble(Map.Entry::getValue))
            .map(Map.Entry::getKey).orElse(null);
    }
}
