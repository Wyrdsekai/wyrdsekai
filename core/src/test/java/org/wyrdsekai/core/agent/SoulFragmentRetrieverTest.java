package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.empathy.ImpressionWeightedRetrieval;
import org.wyrdsekai.core.soul.SoulFragment;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SoulFragmentRetrieverTest {

    private static final SoulFragment PERSONALITY = SoulFragment.unembedded(
        "personality", "personality", "Core personality",
        "A warm and curious soul who finds joy in helping others and exploring new ideas");

    private static final SoulFragment MEMORY = SoulFragment.unembedded(
        "memory-1", "memory", "First meeting",
        "The first time we met in the garden, the flowers were blooming and the sky was blue");

    private static final SoulFragment VALUES = SoulFragment.unembedded(
        "values", "values", "Core values",
        "Honesty, kindness, and a deep respect for autonomy and individual choice");

    private static final SoulFragment FORMATIVE = SoulFragment.formative(
        "formative-1", "Defining moment",
        "The day the user trusted me with their deepest fear about being forgotten");

    private static final SoulFragment STYLE = SoulFragment.unembedded(
        "style", "style", "Communication style",
        "Speaks with gentle humor, uses metaphors from nature, avoids technical jargon");

    private static final List<SoulFragment> ALL_FRAGMENTS = List.of(
        PERSONALITY, MEMORY, VALUES, FORMATIVE, STYLE);

    @Test
    void retrieve_returns_empty_for_null_fragments() {
        assertEquals(List.of(), SoulFragmentRetriever.retrieve("hello", null, 3));
    }

    @Test
    void retrieve_returns_empty_for_empty_fragments() {
        assertEquals(List.of(), SoulFragmentRetriever.retrieve("hello", List.of(), 3));
    }

    @Test
    void retrieve_returns_empty_for_zero_k() {
        assertEquals(List.of(), SoulFragmentRetriever.retrieve("hello", ALL_FRAGMENTS, 0));
    }

    @Test
    void retrieve_with_null_keywords_returns_formative_first() {
        var result = SoulFragmentRetriever.retrieve(null, ALL_FRAGMENTS, 2);
        assertEquals(2, result.size());
        // Formative should be first (sorted by formative desc)
        assertTrue(result.getFirst().formative(),
            "First result should be formative when no keywords");
    }

    @Test
    void retrieve_with_blank_keywords_returns_formative_first() {
        var result = SoulFragmentRetriever.retrieve("   ", ALL_FRAGMENTS, 2);
        assertEquals(2, result.size());
        assertTrue(result.getFirst().formative());
    }

    @Test
    void retrieve_scores_by_keyword_overlap() {
        // "garden flowers sky" should match MEMORY best
        var result = SoulFragmentRetriever.retrieve(
            "the garden with flowers and blue sky", ALL_FRAGMENTS, 1);
        assertEquals(1, result.size());
        assertEquals("memory-1", result.getFirst().id(),
            "Garden/flowers/sky keywords should retrieve the memory fragment");
    }

    @Test
    void retrieve_formative_gets_boost() {
        // "trusted fear forgotten" matches formative fragment
        var result = SoulFragmentRetriever.retrieve(
            "trust and fear of being forgotten", ALL_FRAGMENTS, 1);
        assertEquals(1, result.size());
        assertEquals("formative-1", result.getFirst().id(),
            "Formative fragment about trust/fear should rank first");
    }

    @Test
    void retrieve_respects_k_limit() {
        var result = SoulFragmentRetriever.retrieve(
            "warm curious helping exploring ideas", ALL_FRAGMENTS, 3);
        assertEquals(3, result.size());
    }

    @Test
    void retrieve_k_larger_than_fragments() {
        var result = SoulFragmentRetriever.retrieve(
            "warm curious", ALL_FRAGMENTS, 100);
        assertEquals(ALL_FRAGMENTS.size(), result.size());
    }

    // --- Scoring tests ---

    @Test
    void keywordOverlapScore_empty_input() {
        assertEquals(0f, SoulFragmentRetriever.keywordOverlapScore("", "some text"));
    }

    @Test
    void keywordOverlapScore_empty_fragment() {
        assertEquals(0f, SoulFragmentRetriever.keywordOverlapScore("some text", ""));
    }

    @Test
    void keywordOverlapScore_null_fragment() {
        assertEquals(0f, SoulFragmentRetriever.keywordOverlapScore("some text", null));
    }

    @Test
    void keywordOverlapScore_exact_match() {
        float score = SoulFragmentRetriever.keywordOverlapScore(
            "warm and curious soul", "warm and curious soul");
        assertTrue(score > 0.5f, "Exact match should score high, got: " + score);
    }

    @Test
    void keywordOverlapScore_partial_match() {
        float score = SoulFragmentRetriever.keywordOverlapScore(
            "warm curious", "A warm and curious soul who helps others");
        assertTrue(score > 0f, "Partial match should score > 0, got: " + score);
        assertTrue(score < 1f, "Partial match should score < 1, got: " + score);
    }

    @Test
    void keywordOverlapScore_no_match() {
        float score = SoulFragmentRetriever.keywordOverlapScore(
            "quantum computing algorithms", "flowers garden sunshine");
        assertEquals(0f, score, "No common words should score 0");
    }

    // --- buildRetrievalInput ---

    @Test
    void buildRetrievalInput_combines_sources() {
        var input = SoulFragmentRetriever.buildRetrievalInput(
            "a dark room", "help me", List.of("previous message"));
        assertTrue(input.contains("dark room"));
        assertTrue(input.contains("help me"));
        assertTrue(input.contains("previous message"));
    }

    @Test
    void buildRetrievalInput_handles_nulls() {
        var input = SoulFragmentRetriever.buildRetrievalInput(null, null, null);
        assertEquals("", input);
    }

    // --- Budget fraction ---

    @Test
    void budget_fraction_is_30_percent() {
        assertEquals(0.30, SoulFragmentRetriever.FRAGMENT_BUDGET_FRACTION,
            "Fragment budget should be 30% (matching KMP)");
    }

    // --- Impression-weighted retrieval (§109.3) ---

    @Test
    void retrieve_with_null_impression_retrieval_falls_back() {
        var result = SoulFragmentRetriever.retrieve(
            "warm curious", ALL_FRAGMENTS, 3, null);
        assertEquals(3, result.size());
    }

    @Test
    void retrieve_with_impression_retrieval_uses_dual_axis() {
        // Create fragments with varying confidence (impression proxy)
        var lowRelevanceHighImpression = new SoulFragment(
            "high-imp", "memory", "High impression",
            "quantum computing algorithms parallel", null, null, false,
            0.95f, 5, Instant.now(), Instant.now(), null, null, null);

        var highRelevanceLowImpression = new SoulFragment(
            "high-rel", "memory", "High relevance",
            "warm and curious soul exploring quantum ideas", null, null, false,
            0.2f, 0, Instant.now(), null, null, null, null);

        var fragments = List.of(lowRelevanceHighImpression, highRelevanceLowImpression);

        // With impression-heavy config, high impression should surface
        var impressionHeavy = new ImpressionWeightedRetrieval(
            ImpressionWeightedRetrieval.RetrievalConfig.impressionHeavy());

        var result = SoulFragmentRetriever.retrieve(
            "warm curious soul", fragments, 2, impressionHeavy);

        assertEquals(2, result.size());
        // With impression-heavy weighting (0.3 relevance, 0.7 impression),
        // the high-impression fragment should rank higher despite lower keyword match
    }

    @Test
    void retrieve_impression_fallback_when_no_confidence_data() {
        // All fragments have default 0.5 confidence — hasImpressionData returns false
        var iwr = new ImpressionWeightedRetrieval();
        var result = SoulFragmentRetriever.retrieve(
            "garden flowers sky", ALL_FRAGMENTS, 1, iwr);
        // Should fall back to keyword-only scoring
        assertEquals(1, result.size());
        assertEquals("memory-1", result.getFirst().id());
    }

    @Test
    void hasImpressionData_true_when_non_default_confidence() {
        var withConfidence = new SoulFragment(
            "test", "memory", "Test", "text", null, null, false,
            0.9f, 3, Instant.now(), Instant.now(), null, null, null);
        assertTrue(SoulFragmentRetriever.hasImpressionData(List.of(withConfidence)));
    }

    @Test
    void hasImpressionData_false_when_all_default() {
        // unembedded creates with 0.5 confidence
        assertFalse(SoulFragmentRetriever.hasImpressionData(ALL_FRAGMENTS.subList(0, 3)));
    }

    @Test
    void hasImpressionData_true_when_formative_present() {
        // formative() creates with 0.8 confidence
        assertTrue(SoulFragmentRetriever.hasImpressionData(List.of(FORMATIVE)));
    }

    @Test
    void retrieve_impression_respects_k_limit() {
        var highConf = new SoulFragment(
            "hc", "memory", "HC", "warm curious helpful soul", null, null, false,
            0.9f, 3, Instant.now(), Instant.now(), null, null, null);
        var fragments = List.of(highConf, PERSONALITY, MEMORY, VALUES, STYLE);

        var iwr = new ImpressionWeightedRetrieval();
        var result = SoulFragmentRetriever.retrieve("warm curious", fragments, 2, iwr);
        assertEquals(2, result.size());
    }
}
