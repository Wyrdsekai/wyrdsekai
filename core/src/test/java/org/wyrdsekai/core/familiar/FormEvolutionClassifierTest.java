package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link FormEvolutionClassifier} — cosine + Jaccard fallback distance,
 * and the semver-bump recommendation bands (§21).
 */
class FormEvolutionClassifierTest {

    @Test
    void identicalPromptsGetPatchWithZeroDeviation() {
        var r = FormEvolutionClassifier.classify(
            "research obscure library knowledge",
            "research obscure library knowledge",
            null);
        assertEquals(FormEvolutionClassifier.Recommendation.PATCH, r.recommendation());
        assertEquals(0.0, r.deviation());
        assertFalse(r.usedEmbedding());
    }

    @Test
    void smallTweakStaysInPatchBand() {
        var r = FormEvolutionClassifier.classify(
            "research obscure library knowledge with care and detail",
            "research obscure library knowledge with care and detail and patience",
            null);
        assertTrue(r.deviation() <= FormEvolutionClassifier.PATCH_CEILING,
            "small tweak should land in patch; got " + r.deviation());
        assertEquals(FormEvolutionClassifier.Recommendation.PATCH, r.recommendation());
    }

    @Test
    void wholesaleRewriteSuggestsMajor() {
        var r = FormEvolutionClassifier.classify(
            "research obscure library knowledge carefully and thoroughly",
            "entirely different goal building something new xyz",
            null);
        assertTrue(r.deviation() > FormEvolutionClassifier.MINOR_CEILING,
            "wholesale rewrite should exceed minor ceiling; got " + r.deviation());
        assertEquals(FormEvolutionClassifier.Recommendation.MAJOR, r.recommendation());
    }

    @Test
    void cosineEmbedderPreferredWhenAvailable() {
        FormEvolutionClassifier.EmbeddingFn embedder = s -> {
            // Deterministic tiny "embedding": first 4 char-codes as floats
            var v = new float[4];
            for (int i = 0; i < 4 && i < s.length(); i++) v[i] = s.charAt(i);
            return v;
        };
        var r = FormEvolutionClassifier.classify("abcd", "abce", embedder);
        assertTrue(r.usedEmbedding(), "should have used embedder");
        assertTrue(r.deviation() < 0.1,
            "nearly-identical strings should have tiny cosine distance; got " + r.deviation());
    }

    @Test
    void embedderNullVectorFallsBackToJaccard() {
        FormEvolutionClassifier.EmbeddingFn broken = s -> null;
        var r = FormEvolutionClassifier.classify("alpha beta gamma", "alpha beta delta", broken);
        assertFalse(r.usedEmbedding(), "null vector must force fallback");
    }

    @Test
    void versionBumpLabelsMatchRecommendation() {
        assertEquals("patch",
            FormEvolutionClassifier.toVersionBump(FormEvolutionClassifier.Recommendation.PATCH));
        assertEquals("minor",
            FormEvolutionClassifier.toVersionBump(FormEvolutionClassifier.Recommendation.MINOR));
        assertEquals("major",
            FormEvolutionClassifier.toVersionBump(FormEvolutionClassifier.Recommendation.MAJOR));
    }

    @Test
    void nullPromptsTreatedAsEmpty() {
        var r = FormEvolutionClassifier.classify(null, null, null);
        assertEquals(FormEvolutionClassifier.Recommendation.PATCH, r.recommendation());
        // Empty vs non-empty — Jaccard returns 1.0
        var r2 = FormEvolutionClassifier.classify(null, "some content to compare", null);
        assertEquals(1.0, r2.deviation());
        assertEquals(FormEvolutionClassifier.Recommendation.MAJOR, r2.recommendation());
    }

    @Test
    void cosineDistanceClampsBelowZeroOrAboveOne() {
        var v = new float[] {1f, 0f, 0f};
        assertEquals(0.0, FormEvolutionClassifier.cosineDistance(v, v), 1e-6);
        var opposite = new float[] {-1f, 0f, 0f};
        assertEquals(1.0, FormEvolutionClassifier.cosineDistance(v, opposite), 1e-6);
    }

    @Test
    void custom_thresholds_reclassify_deviation() {
        // Strings chosen so ~33% of tokens differ (deviation ≈ 0.33 → MINOR band)
        var oldP = "research for obscure library knowledge";
        var newP = "research for obscure library source";

        // Default thresholds (patch 0.20 / minor 0.50): MINOR
        var r1 = FormEvolutionClassifier.classify(oldP, newP, null);
        assertThat(r1.recommendation()).isEqualTo(FormEvolutionClassifier.Recommendation.MINOR);

        // Strict thresholds push the same deviation into MAJOR
        var strict = new FormEvolutionClassifier.Thresholds(0.10, 0.20);
        var r2 = FormEvolutionClassifier.classify(oldP, newP, null, strict);
        assertThat(r2.recommendation()).isEqualTo(FormEvolutionClassifier.Recommendation.MAJOR);

        // Lax thresholds pull it down to PATCH
        var lax = new FormEvolutionClassifier.Thresholds(0.9, 0.95);
        var r3 = FormEvolutionClassifier.classify(oldP, newP, null, lax);
        assertThat(r3.recommendation()).isEqualTo(FormEvolutionClassifier.Recommendation.PATCH);
    }

    @Test
    void thresholds_record_enforces_ordering_invariants() {
        // minor < patch → auto-clamped to patch
        var t = new FormEvolutionClassifier.Thresholds(0.5, 0.3);
        assertThat(t.minorCeiling()).isEqualTo(0.5);
        // patch > 1 → clamped
        var t2 = new FormEvolutionClassifier.Thresholds(0.8, 1.5);
        assertThat(t2.minorCeiling()).isEqualTo(1.0);
    }

    @Test
    void jaccardDistanceIgnoresShortTokens() {
        // "a b c" has no tokens ≥3 chars — treated as empty
        var d = FormEvolutionClassifier.jaccardDistance("a b c", "foo bar");
        assertEquals(1.0, d, 0.01,
            "empty-vs-non-empty jaccard returns 1.0");
    }
}
