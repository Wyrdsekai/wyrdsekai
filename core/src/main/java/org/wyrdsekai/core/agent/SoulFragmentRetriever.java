package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.empathy.ImpressionWeightedRetrieval;
import org.wyrdsekai.core.soul.FragmentKind;
import org.wyrdsekai.core.soul.SoulFragment;
import org.wyrdsekai.core.util.TextSimilarity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Retrieves top-k soul fragments by keyword overlap scoring,
 * optionally enhanced with impression-weighted dual-axis retrieval (§109.3).
 * Fallback when no embedding model is available (phone, tests, server).
 *
 * Experiment 17 validated: MEDIUM resident + top-3 retrieval beats
 * DEEP flat (26.4% vs 28.4% divergence, 76.2% vs 72.2% semantic).
 * Top-5 degrades (30.0%) — context pollution.
 *
 * Formative fragments (§109.4) get a 1.5x scoring boost.
 * When impression scores are available (via fragment confidence as proxy),
 * dual-axis scoring surfaces emotionally significant fragments.
 */
public final class SoulFragmentRetriever {

    /** Budget cap: soul fragments may use at most 30% of remaining tokens. */
    static final double FRAGMENT_BUDGET_FRACTION = 0.30;

    /**
     * default top-k for EPISODIC scene memories in the
     * blended retrieval. Kept small so EPISODIC doesn't crowd consolidated
     * NARRATIVE out of the prompt; the design memo's v1 recipe is
     * top-2 EPISODIC + top-{retrievalK} NARRATIVE merged.
     */
    public static final int DEFAULT_EPISODIC_K = 2;

    private SoulFragmentRetriever() {}

    /**
     * Retrieve top-k fragments by keyword overlap with context.
     * Falls back to keyword-only scoring (no impression weighting).
     *
     * @param contextKeywords  Keywords from room state + trigger + recent history
     * @param fragments        All soul fragments from the manifest
     * @param k                Maximum fragments to return (experiment 17: k=3 optimal)
     * @return Scored and ranked fragments, at most k
     */
    public static List<SoulFragment> retrieve(String contextKeywords,
                                               List<SoulFragment> fragments, int k) {
        return retrieve(contextKeywords, fragments, k, null);
    }

    /**
     * Retrieve top-k fragments with dual-axis scoring: relevance x impression depth.
     * When impressionRetrieval is non-null and fragments have confidence data,
     * the ImpressionWeightedRetrieval system re-ranks candidates using both
     * keyword relevance and impression depth (confidence as proxy).
     *
     * @param contextKeywords     Keywords from room state + trigger + recent history
     * @param fragments           All soul fragments from the manifest
     * @param k                   Maximum fragments to return
     * @param impressionRetrieval Impression-weighted retrieval system (nullable — falls back to keyword-only)
     * @return Scored and ranked fragments, at most k
     */
    public static List<SoulFragment> retrieve(String contextKeywords,
                                               List<SoulFragment> fragments, int k,
                                               ImpressionWeightedRetrieval impressionRetrieval) {
        if (fragments == null || fragments.isEmpty() || k <= 0) return List.of();
        // A superseded fragment is what she USED to be described as. It stays in the
        // record; it does not get to shape the next turn. SoulFragment has carried
        // isSuperseded()/isCurrent() since the beginning and nothing ever called them
        // (verified 2026-08-17) — so retirement was expressible and never expressed.
        fragments = fragments.stream().filter(f -> f != null && f.isCurrent()).toList();
        if (fragments.isEmpty()) return List.of();

        if (contextKeywords == null || contextKeywords.isBlank()) {
            // No context — return formative fragments first, then by order
            return fragments.stream()
                .sorted((a, b) -> Boolean.compare(b.formative(), a.formative()))
                .limit(k)
                .toList();
        }

        var inputWords = tokenize(contextKeywords);
        if (inputWords.isEmpty()) return fragments.stream().limit(k).toList();

        // Phase 1: compute keyword relevance scores for all fragments
        var relevanceScores = new HashMap<String, Double>();
        var fragmentById = new HashMap<String, SoulFragment>();
        for (var f : fragments) {
            float score = keywordOverlapScore(inputWords, f.text());
            if (f.formative()) score *= 1.5f;
            relevanceScores.put(f.id(), (double) score);
            fragmentById.put(f.id(), f);
        }

        // Phase 2: if impression retrieval is available and fragments have confidence,
        // use dual-axis scoring (relevance x impression depth)
        if (impressionRetrieval != null && hasImpressionData(fragments)) {
            var impressionScores = new HashMap<String, Double>();
            for (var f : fragments) {
                // Use effectiveConfidence as impression depth proxy:
                // high-confidence fragments are emotionally reinforced memories
                impressionScores.put(f.id(), (double) f.effectiveConfidence());
            }

            var ranked = impressionRetrieval.rankCandidates(relevanceScores, impressionScores);
            var ordered = new ArrayList<SoulFragment>();
            for (var candidate : ranked) {
                var frag = fragmentById.get(candidate.fragmentId());
                if (frag != null) ordered.add(frag);
            }
            return takeDiverse(ordered, k);
        }

        // Fallback: keyword-only scoring (original path)
        record Scored(SoulFragment fragment, float score) {}
        var scored = new ArrayList<Scored>();
        for (var entry : relevanceScores.entrySet()) {
            scored.add(new Scored(fragmentById.get(entry.getKey()), entry.getValue().floatValue()));
        }

        return takeDiverse(scored.stream()
            .sorted((a, b) -> Float.compare(b.score(), a.score()))
            .map(Scored::fragment)
            .toList(), k);
    }

    /**
     * Maximal-marginal-relevance mix: the weight on relevance, with the remainder on
     * being unlike what is already selected. 1.0 reproduces pure relevance ranking;
     * 0.5 is the balanced setting, and it is what this needs — measured against the
     * saturated corpus that motivated the fix, a weaker novelty weight still let five
     * rewordings of one thought outrank a genuinely distinct memory, because each
     * rewording was individually more relevant than the distinct one.
     */
    static final double DIVERSITY_LAMBDA = 0.5;

    /**
     * Fill {@code k} slots from an already-relevance-ranked list, preferring fragments
     * unlike the ones already chosen (maximal marginal relevance).
     *
     * <p>Relevance ranking alone assumes the corpus is varied. When it isn't, the
     * top-k collapses: a companion whose runaway proactive-speech loop had written 56
     * paraphrases of one sentence into her fragments got the same thought back in
     * every prompt, which shaped the next utterance, which became the next fragment —
     * an autophagic loop that tightened for eight days (live-diagnosed 2026-08-17).
     * Relevance says "closest to context"; a prompt needs "closest AND not already
     * said."
     *
     * <p>Deliberately a RELATIVE criterion rather than a similarity threshold. Measured
     * on that corpus, the loop's paraphrases were lexically varied enough that any
     * absolute cutoff safely clear of unrelated text would have missed about half of
     * them. Penalising each candidate by its similarity to what is already selected
     * needs no cutoff: in a varied corpus the penalty is near zero and ordering barely
     * changes, while in a saturated one the second slot actively goes to the most
     * different thing available. It also always returns {@code k} fragments when
     * {@code k} exist — diversity reorders the prompt, it never thins it.
     *
     * <p>This is the RETRIEVAL end of the record/input split: nothing is withheld from
     * her record, it just stops one thought from authoring every turn.
     */
    static List<SoulFragment> takeDiverse(List<SoulFragment> ranked, int k) {
        if (ranked == null || ranked.isEmpty() || k <= 0) return List.of();
        var remaining = new ArrayList<SoulFragment>();
        for (var f : ranked) if (f != null) remaining.add(f);
        if (remaining.isEmpty()) return List.of();

        // Tokenize once per candidate: selection is quadratic in candidates and this
        // runs on the per-turn prompt path.
        var tokens = new ArrayList<Set<String>>(remaining.size());
        for (var f : remaining) tokens.add(TextSimilarity.tokens(f.text()));

        var chosen = new ArrayList<SoulFragment>();
        var chosenTokens = new ArrayList<Set<String>>();
        // Rank position stands in for relevance: the list arrives sorted, and a
        // position-based score keeps this independent of whichever scorer produced it.
        while (chosen.size() < k && !remaining.isEmpty()) {
            int bestIndex = 0;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < remaining.size(); i++) {
                double relevance = 1.0 - ((double) i / remaining.size());
                double redundancy = 0.0;
                for (var takenTokens : chosenTokens) {
                    redundancy = Math.max(redundancy,
                        TextSimilarity.overlap(takenTokens, tokens.get(i)));
                }
                double score = DIVERSITY_LAMBDA * relevance
                    - (1.0 - DIVERSITY_LAMBDA) * redundancy;
                if (score > bestScore) {
                    bestScore = score;
                    bestIndex = i;
                }
            }
            chosen.add(remaining.remove(bestIndex));
            chosenTokens.add(tokens.remove(bestIndex));
        }
        return List.copyOf(chosen);
    }

    /**
     * blended retrieval across two separate pools so
     * {@link FragmentKind#EPISODIC} scene memories don't crowd consolidated
     * {@link FragmentKind#NARRATIVE} (and friends) out of the prompt top-k.
     * Returns top-{@code kNarrative} from the non-EPISODIC pool followed by
     * top-{@code kEpisodic} from the EPISODIC pool. Each pool runs through
     * the full keyword-scoring pipeline ({@link #retrieve}); the merge is
     * append-only and preserves per-pool ordering.
     *
     * <p>v1 recipe per the design memo: {@code kNarrative} = the manifest's
     * {@code retrievalK} (default 3), {@code kEpisodic} = {@link #DEFAULT_EPISODIC_K}
     * (2). Tune later if EPISODIC vs NARRATIVE balance proves wrong in lived
     * use.</p>
     *
     * @param contextKeywords  the same keywords as {@link #retrieve}
     * @param fragments        the full fragment list — partitioned internally
     * @param kNarrative       non-EPISODIC pool cap (typically retrievalK)
     * @param kEpisodic        EPISODIC pool cap (typically {@link #DEFAULT_EPISODIC_K})
     * @return narrative-pool top-k followed by episodic-pool top-k; never
     *         null and never deduplicated across pools (the kind split is
     *         disjoint by construction).
     */
    public static List<SoulFragment> retrieveBlended(String contextKeywords,
                                                      List<SoulFragment> fragments,
                                                      int kNarrative,
                                                      int kEpisodic) {
        if (fragments == null || fragments.isEmpty()) return List.of();
        // Partition by kind. Anything non-EPISODIC stays in the "narrative" pool
        // so legacy fragments (kind=NARRATIVE), DEXTERITY, CONVENTION, and
        // STRUCTURAL all participate together — only EPISODIC is segregated.
        var episodicPool = new ArrayList<SoulFragment>();
        var narrativePool = new ArrayList<SoulFragment>();
        for (var f : fragments) {
            if (f == null) continue;
            if (f.kind() == FragmentKind.EPISODIC) episodicPool.add(f);
            else narrativePool.add(f);
        }
        var blended = new ArrayList<SoulFragment>();
        if (kNarrative > 0 && !narrativePool.isEmpty()) {
            blended.addAll(retrieve(contextKeywords, narrativePool, kNarrative));
        }
        if (kEpisodic > 0 && !episodicPool.isEmpty()) {
            blended.addAll(retrieve(contextKeywords, episodicPool, kEpisodic));
        }
        return List.copyOf(blended);
    }

    /** Check if any fragment has non-default confidence (impression data is available). */
    static boolean hasImpressionData(List<SoulFragment> fragments) {
        return fragments.stream().anyMatch(f -> f.confidence() != null && f.confidence() != 0.5f);
    }

    /**
     * Build retrieval input keywords from room context and trigger.
     */
    public static String buildRetrievalInput(String roomDescription,
                                              String triggerText,
                                              List<String> recentTexts) {
        var sb = new StringBuilder();
        if (roomDescription != null) sb.append(roomDescription).append(" ");
        if (triggerText != null) sb.append(triggerText).append(" ");
        if (recentTexts != null) {
            for (var t : recentTexts) sb.append(t).append(" ");
        }
        return sb.toString().trim();
    }

    // --- Scoring ---

    static float keywordOverlapScore(String input, String fragmentText) {
        return keywordOverlapScore(tokenize(input), fragmentText);
    }

    static float keywordOverlapScore(Set<String> inputWords, String fragmentText) {
        if (inputWords.isEmpty() || fragmentText == null || fragmentText.isBlank()) return 0f;

        var fragmentWords = tokenize(fragmentText);
        if (fragmentWords.isEmpty()) return 0f;

        long overlap = inputWords.stream().filter(fragmentWords::contains).count();
        // Jaccard-like: overlap / union
        var union = new HashSet<>(inputWords);
        union.addAll(fragmentWords);
        return (float) overlap / union.size();
    }

    private static Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("[\\s\\p{Punct}]+"))
            .filter(w -> w.length() > 2)  // skip short noise words
            .collect(Collectors.toSet());
    }
}
