package org.wyrdsekai.core.util;

import java.util.HashSet;
import java.util.Set;

/**
 * Token-set similarity over short natural-language strings — the embedder-free
 * heuristic this codebase already used in one place and now needs in several.
 *
 * <p>The motivating defect (2026-08-17): every guard in the memory path checked
 * for byte IDENTITY where it needed to check SIMILARITY. The speech repeat guard
 * dropped only byte-identical lines, the forge encoded one memory node per
 * utterance without asking whether the utterance was new, and the episodic
 * fragment writer laid down paraphrase after paraphrase of the same scene. A
 * companion stuck in a proactive-speech loop therefore accreted 56 near-identical
 * identity fragments over eight days — each one distinct to the letter, all of
 * them the same thought. Exact-match dedup cannot see that; this can.
 *
 * <p>Tokenization matches the pre-existing heuristic exactly (lowercase, split on
 * non-word characters, keep tokens of 3+ characters) so callers that migrate onto
 * this class see no change in behaviour. Word ORDER is deliberately ignored: the
 * duplicates worth catching are reorderings ("the words didn't need permission
 * this time" / "those words didn't need permission that night").
 */
public final class TextSimilarity {

    private TextSimilarity() {}

    /** Jaccard similarity of the two token sets, in [0,1]. Two blank strings are identical. */
    public static double jaccard(String a, String b) {
        var tokensA = tokenize(a);
        var tokensB = tokenize(b);
        if (tokensA.isEmpty() && tokensB.isEmpty()) return 1.0;
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0;
        var intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);
        var union = new HashSet<>(tokensA);
        union.addAll(tokensB);
        if (union.isEmpty()) return 1.0;
        return (double) intersection.size() / union.size();
    }

    /** {@code 1 − jaccard} — the distance form, for callers that band on deviation. */
    public static double distance(String a, String b) {
        return 1.0 - jaccard(a, b);
    }

    /**
     * Overlap coefficient (Szymkiewicz–Simpson): shared tokens over the SMALLER token
     * set. Unlike Jaccard it doesn't punish a pair for differing in length, which is
     * what a reworded sentence does — measured on the live corpus that produced this
     * class, one thought said two ways scores 0.75 here against 0.57 Jaccard, while an
     * unrelated pair scores 0.20 against 0.08. The wider margin is the whole reason
     * duplicate detection uses this and deviation banding uses Jaccard.
     */
    public static double overlap(String a, String b) {
        return overlap(tokens(a), tokens(b));
    }

    /**
     * Overlap over pre-computed token sets — for callers comparing the same texts
     * repeatedly (a top-k selection is quadratic in candidates, and re-tokenizing
     * inside that loop is pure waste on a per-turn path).
     */
    public static double overlap(Set<String> tokensA, Set<String> tokensB) {
        if (tokensA == null || tokensB == null || tokensA.isEmpty() || tokensB.isEmpty()) {
            return 0.0;
        }
        var smaller = tokensA.size() <= tokensB.size() ? tokensA : tokensB;
        var larger = smaller == tokensA ? tokensB : tokensA;
        int shared = 0;
        for (var t : smaller) if (larger.contains(t)) shared++;
        return (double) shared / smaller.size();
    }

    /** The substantive tokens of a text — lowercased words of 3+ characters. */
    public static Set<String> tokens(String s) {
        return tokenize(s);
    }

    /** Below this many substantive tokens, containment is too easy to be evidence —
     *  "thank you" sits inside almost any longer line. */
    private static final int MIN_TOKENS_FOR_OVERLAP = 5;

    /**
     * True when the two strings say substantially the same thing, by
     * {@link #overlap}. Very short texts fall back to {@link #jaccard}, which cannot
     * be satisfied by mere containment, and a text with no substantive tokens is never
     * a duplicate of anything (absence of evidence, not evidence of sameness).
     *
     * <p>This is a LEXICAL test and its reach is bounded: it catches rewordings that
     * still reuse most of the same words. Measured against the corpus this was built
     * for, that is the strong tail — a model paraphrasing one thought across days
     * varies its vocabulary enough that roughly half of such pairs fall below any
     * threshold safely clear of unrelated text. Catching those needs embeddings, not
     * tokens; callers must not treat this as a complete guard.
     *
     * @param threshold overlap at or above which the pair counts as a duplicate.
     *                  Callers own this number and should measure it on real data.
     */
    public static boolean nearDuplicate(String a, String b, double threshold) {
        var tokensA = tokenize(a);
        var tokensB = tokenize(b);
        if (tokensA.isEmpty() || tokensB.isEmpty()) return false;
        if (Math.min(tokensA.size(), tokensB.size()) < MIN_TOKENS_FOR_OVERLAP) {
            return jaccard(a, b) >= threshold;
        }
        return overlap(a, b) >= threshold;
    }

    private static Set<String> tokenize(String s) {
        if (s == null || s.isBlank()) return Set.of();
        var parts = s.toLowerCase().split("\\W+");
        var out = new HashSet<String>();
        for (var p : parts) {
            if (p.length() >= 3) out.add(p);
        }
        return out;
    }
}
