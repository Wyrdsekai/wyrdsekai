package org.wyrdsekai.core.agent.affordance;

import java.util.ArrayList;
import java.util.Collection;
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
        return rank(needPressures, forcedFront, candidates, resolve, topK, relevance, List.of());
    }

    /**
     * As above, plus names the trim may not drop: they stay, in their ranked order, right after
     * the forced front, and the lowest-ranked others make room.
     *
     * <p>One pinned name was never enough. Her own-time prompt names the workshop door on nearly
     * every turn (a growth want's verb), and the one pin went to it; the notebook, named in the
     * same prompt, was ranked out. On the household node the notebook was in her hand on 6% and
     * 13% of two companions' turns while the workshop was in it on nearly all, and 174 workshop
     * requests in two weeks included a third of her notebook pages (2026-10-26).
     *
     * @param keep names that survive the trim when they are among the candidates; may be empty
     */
    public static List<String> rank(Map<String, Double> needPressures, String forcedFront,
            List<String> candidates, Function<String, ToolAffordance> resolve, int topK,
            Function<String, Double> relevance, Collection<String> keep) {
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
        var kept = keptAmong(ranked, keep, forcedFront);
        if (!kept.isEmpty()) {
            ranked.removeAll(kept);
            ranked.addAll(forcedFront != null && !ranked.isEmpty() && ranked.getFirst().equals(forcedFront) ? 1 : 0, kept);
        }

        if (topK > 0 && ranked.size() > topK) return new ArrayList<>(ranked.subList(0, topK));
        return ranked;
    }

    /** The kept names, in ranked order, that are candidates and not the forced front. */
    private static List<String> keptAmong(List<String> ranked, Collection<String> keep, String forcedFront) {
        if (keep == null || keep.isEmpty()) return List.of();
        var out = new ArrayList<String>();
        for (var n : ranked) if (keep.contains(n) && !n.equals(forcedFront) && !out.contains(n)) out.add(n);
        return out;
    }

    /** The need with the highest current pressure (for the instrument's "dominant need"). */
    // ── A surface that spans the kinds of act ──────────────────────────────────────────────

    /** The kinds of act a companion's own time can hold. A surface should offer one of each before two of any. */
    public static final List<String> KINDS = List.of("reading", "keeping", "reaching", "making", "going",
        "working", "tending", "household", "other");

    private static final Map<String, String> KIND_OF_TOOL = Map.ofEntries(
        Map.entry("library_card", "reading"), Map.entry("library_shelves", "reading"), Map.entry("searching_glass", "reading"),
        Map.entry("oracle_lens", "reading"), Map.entry("read_book", "reading"), Map.entry("read_content", "reading"),
        Map.entry("observation_crystal", "reading"), Map.entry("research_assistant", "reading"),
        Map.entry("quill", "keeping"), Map.entry("journal", "keeping"), Map.entry("chronicle_stone", "keeping"),
        Map.entry("task_ledger", "keeping"), Map.entry("project_note", "keeping"), Map.entry("recall", "keeping"),
        Map.entry("sending_stone", "reaching"), Map.entry("channel_stone", "reaching"),
        Map.entry("dispatch_task", "working"), Map.entry("mending_bench", "working"));

    private static final Map<String, String> KIND_OF_DOMAIN = Map.ofEntries(
        Map.entry("search", "reading"), Map.entry("knowledge", "reading"), Map.entry("observation", "reading"),
        Map.entry("memory", "keeping"), Map.entry("study", "keeping"), Map.entry("planning", "keeping"),
        Map.entry("social", "reaching"), Map.entry("communication", "reaching"), Map.entry("bond", "reaching"),
        Map.entry("creation", "making"), Map.entry("recipes", "making"),
        Map.entry("navigation", "going"),
        Map.entry("workshop", "working"), Map.entry("code", "working"), Map.entry("delegation", "working"),
        Map.entry("automation", "working"),
        Map.entry("repair", "tending"), Map.entry("safety", "tending"), Map.entry("self", "tending"),
        Map.entry("analysis", "tending"));

    /** The kind of act a tool is, from its name or its domain. A household item with no domain is its own kind. */
    public static String kindOf(String tool, String domain) {
        var byName = tool == null ? null : KIND_OF_TOOL.get(tool);
        if (byName != null) return byName;
        if (domain == null || domain.isBlank() || "unknown".equals(domain) || "items".equals(domain)) return "household";
        return KIND_OF_DOMAIN.getOrDefault(domain, "other");
    }

    /**
     * The surface for her own time: one tool of each kind of act first, then by score, with no
     * kind taking more than a share.
     *
     * <p>Ranked by score alone, a week of one household's own-time surfaces (3,492 passes, eight
     * tools each) offered "make a room" in 83% and "go to a room" in 72%, and offered recalling
     * in 0.9%, the journal in 1.4%, a message to someone in 1.3%, and writing a note, reflecting,
     * a letter in none. Seven household items named after rooms took the rest. What she is not
     * shown she cannot choose: two companions made 133 rooms (household node, 2026-10-03).
     *
     * @param kindOf name → kind (see {@link #kindOf(String, String)})
     * @param share  the most one kind may take before every kind has been offered (at least 2)
     */
    public static List<String> rankSpanning(Map<String, Double> needPressures, String forcedFront,
            List<String> candidates, Function<String, ToolAffordance> resolve, int topK,
            Function<String, Double> relevance, Function<String, String> kindOf, int share) {
        return rankSpanning(needPressures, forcedFront, candidates, resolve, topK, relevance, kindOf, share, List.of());
    }

    /** As above, plus names the trim may not drop (see {@link #rank(Map, String, List, Function, int, Function, Collection)}). */
    public static List<String> rankSpanning(Map<String, Double> needPressures, String forcedFront,
            List<String> candidates, Function<String, ToolAffordance> resolve, int topK,
            Function<String, Double> relevance, Function<String, String> kindOf, int share, Collection<String> keep) {
        var ranked = rank(needPressures, forcedFront, candidates, resolve, 0, relevance);
        if (topK <= 0 || ranked.size() <= topK) return ranked;
        var out = new ArrayList<String>();
        var perKind = new HashMap<String, Integer>();
        java.util.function.Consumer<String> take = n -> { out.add(n); perKind.merge(kindOf.apply(n), 1, Integer::sum); };
        if (forcedFront != null && ranked.contains(forcedFront)) take.accept(forcedFront);
        for (var n : keptAmong(ranked, keep, forcedFront)) if (out.size() < topK) take.accept(n);
        // One of each kind, in the order her pull and the request rank them.
        for (var n : ranked) {
            if (out.size() >= topK) break;
            if (out.contains(n)) continue;
            if (perKind.getOrDefault(kindOf.apply(n), 0) == 0) take.accept(n);
        }
        // Then by score, no kind past its share.
        int cap = Math.max(2, share);
        for (var n : ranked) {
            if (out.size() >= topK) break;
            if (out.contains(n)) continue;
            int have = perKind.getOrDefault(kindOf.apply(n), 0);
            if (have < ("household".equals(kindOf.apply(n)) ? 2 : cap)) take.accept(n);
        }
        // Then whatever is left, by score.
        for (var n : ranked) {
            if (out.size() >= topK) break;
            if (!out.contains(n)) take.accept(n);
        }
        return out;
    }

    /** The strongest of the levels that are pulls, leaving out the capacities (a full tank of confidence is not a need). */
    public static String dominantPull(Map<String, Double> needPressures, java.util.Set<String> notPulls) {
        if (needPressures == null || needPressures.isEmpty()) return null;
        return needPressures.entrySet().stream()
            .filter(e -> e.getKey() != null && e.getValue() != null && (notPulls == null || !notPulls.contains(e.getKey())))
            .max(Comparator.comparingDouble(Map.Entry::getValue))
            .map(Map.Entry::getKey).orElse(null);
    }

    public static String dominantNeed(Map<String, Double> needPressures) {
        if (needPressures == null || needPressures.isEmpty()) return null;
        return needPressures.entrySet().stream()
            .max(Comparator.comparingDouble(Map.Entry::getValue))
            .map(Map.Entry::getKey).orElse(null);
    }
}
