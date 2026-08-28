package org.wyrdsekai.core.familiar;

import org.wyrdsekai.core.util.TextSimilarity;

import java.util.Arrays;
import java.util.function.Function;

/**
 * Classifies a thought-form revision as patch, minor, or major
 * based on system-prompt intent deviation.
 *
 * <p>. The spec's ideal is cosine distance on embeddings
 * of old-prompt vs new-prompt. When a real embedder is plumbed through, pass
 * an {@link EmbeddingFn}. When it isn't available (cold start, test, or
 * agent with no embedder configured), the classifier falls back to a
 * token-Jaccard heuristic that correlates reasonably with intent-drift for
 * English-language prompts. Either way, the agent retains final say — this
 * is a suggestion, not a mandate (§21: "Agent chooses based on its own
 * sense of identity-of-the-form").</p>
 */
public final class FormEvolutionClassifier {

    /** Default deviation ≤ this → semver patch (§21). */
    public static final double PATCH_CEILING = 0.20;
    /** Default deviation ≤ this but > PATCH_CEILING → semver minor (§21). */
    public static final double MINOR_CEILING = 0.50;
    /** Deviation above minor ceiling → new-lineage suggested (major). */

    /**
     * Per-agent threshold record (§21). Agent may adjust within user-configured
     * bounds; floors/ceilings come from FamiliarConfig. Null = use defaults.
     */
    public record Thresholds(double patchCeiling, double minorCeiling) {
        public Thresholds {
            if (patchCeiling < 0) patchCeiling = 0;
            if (minorCeiling < patchCeiling) minorCeiling = patchCeiling;
            if (minorCeiling > 1.0) minorCeiling = 1.0;
        }
        public static Thresholds defaults() {
            return new Thresholds(PATCH_CEILING, MINOR_CEILING);
        }
    }

    public enum Recommendation { PATCH, MINOR, MAJOR }

    /** Pluggable embedding callback. Returns a fixed-length vector or null. */
    @FunctionalInterface
    public interface EmbeddingFn extends Function<String, float[]> {
        EmbeddingFn NONE = s -> null;
    }

    public record Result(
        Recommendation recommendation,
        double deviation,
        boolean usedEmbedding,
        String rationale
    ) {}

    private FormEvolutionClassifier() {}

    /**
     * Classify a revision's deviation. If {@code embedder} produces vectors
     * for both prompts, uses cosine distance. Otherwise falls back to
     * 1 − Jaccard(oldTokens, newTokens).
     */
    public static Result classify(String oldPrompt, String newPrompt, EmbeddingFn embedder) {
        return classify(oldPrompt, newPrompt, embedder, Thresholds.defaults());
    }

    /**
     * Classify with caller-supplied thresholds. Agent-level §21 override goes
     * through here; {@link #classify(String, String, EmbeddingFn)} preserves
     * backward-compatible defaults.
     */
    public static Result classify(String oldPrompt, String newPrompt,
                                   EmbeddingFn embedder, Thresholds thresholds) {
        if (thresholds == null) thresholds = Thresholds.defaults();
        if (oldPrompt == null) oldPrompt = "";
        if (newPrompt == null) newPrompt = "";
        if (oldPrompt.equals(newPrompt)) {
            return new Result(Recommendation.PATCH, 0.0, false,
                "no-op revision — prompts identical");
        }

        double deviation;
        boolean usedEmbedding = false;
        if (embedder != null) {
            var oldVec = safeEmbed(embedder, oldPrompt);
            var newVec = safeEmbed(embedder, newPrompt);
            if (oldVec != null && newVec != null && oldVec.length == newVec.length
                    && oldVec.length > 0) {
                deviation = cosineDistance(oldVec, newVec);
                usedEmbedding = true;
            } else {
                deviation = jaccardDistance(oldPrompt, newPrompt);
            }
        } else {
            deviation = jaccardDistance(oldPrompt, newPrompt);
        }

        Recommendation rec;
        String rationale;
        if (deviation <= thresholds.patchCeiling()) {
            rec = Recommendation.PATCH;
            rationale = "deviation " + fmt(deviation) + " ≤ patch ceiling "
                + fmt(thresholds.patchCeiling());
        } else if (deviation <= thresholds.minorCeiling()) {
            rec = Recommendation.MINOR;
            rationale = "deviation " + fmt(deviation) + " in minor band ("
                + fmt(thresholds.patchCeiling()) + "–" + fmt(thresholds.minorCeiling()) + ")";
        } else {
            rec = Recommendation.MAJOR;
            rationale = "deviation " + fmt(deviation) + " > minor ceiling "
                + fmt(thresholds.minorCeiling()) + " — intent has shifted significantly, "
                + "consider new lineage";
        }
        return new Result(rec, deviation, usedEmbedding, rationale);
    }

    /** Convert the classifier's recommendation into the semver-bump string. */
    public static String toVersionBump(Recommendation rec) {
        return switch (rec) {
            case PATCH -> "patch";
            case MINOR -> "minor";
            case MAJOR -> "major";
        };
    }

    // ── Distance computations ──────────────────────────────────────────────

    static double cosineDistance(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 1.0;
        var sim = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        // cosine distance = 1 − similarity, clamped
        return Math.max(0.0, Math.min(1.0, 1.0 - sim));
    }

    /**
     * Token-level 1 − Jaccard as a lexical fallback for semantic distance.
     * Tokens: lowercased words ≥ 3 chars, split on non-alphanumeric. This
     * correlates poorly with true semantic distance on short prompts but
     * handles the common case (agent rewrites most of the prompt) fine.
     */
    static double jaccardDistance(String a, String b) {
        return TextSimilarity.distance(a, b);
    }

    private static float[] safeEmbed(EmbeddingFn fn, String s) {
        try { return fn.apply(s); } catch (Exception e) { return null; }
    }

    private static String fmt(double d) {
        return String.format("%.2f", d);
    }
}
