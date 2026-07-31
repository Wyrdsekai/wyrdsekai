package org.wyrdsekai.core.search;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Multilingual embedding sanity check — verifies the bundled
 * {@code paraphrase-multilingual-MiniLM-L12-v2} model produces non-trivial
 * cross-language similarity between same-meaning pairs.
 *
 * <p> motivates the swap from English-only
 * MiniLM-L6-v2. The thresholds below are calibrated against the bundled
 * quantized model — same-meaning pairs across different languages should
 * cluster well above unrelated pairs (regardless of language).
 */
class EmbeddingServiceMultilingualTest {

    private static EmbeddingService service;

    @BeforeAll
    static void setUp() {
        service = EmbeddingService.init();
        Assumptions.assumeTrue(service != null,
            "EmbeddingService not available (multilingual model missing?)");
    }

    @AfterAll
    static void tearDown() {
        if (service != null) service.close();
    }

    @Test
    void modelVersionIsMultilingual() {
        // Migration tool keys off this — must contain "multilingual" so it's
        // visually distinguishable from the legacy MiniLM-L6-v2 string in
        // soul_fragments.embedding_model.
        assertThat(EmbeddingService.currentModelVersion())
            .containsIgnoringCase("multilingual");
    }

    @Test
    void englishHelloEmbeds() {
        var v = service.embed("hello world");
        assertThat(v).hasSize(EmbeddingService.dimension());
        assertThat(v.stream().anyMatch(f -> f != 0f)).isTrue();
    }

    @Test
    void japaneseHelloEmbeds() {
        var v = service.embed("こんにちは世界");
        assertThat(v).hasSize(EmbeddingService.dimension());
        assertThat(v.stream().anyMatch(f -> f != 0f)).isTrue();
    }

    @Test
    void spanishHelloEmbeds() {
        var v = service.embed("hola mundo");
        assertThat(v).hasSize(EmbeddingService.dimension());
        assertThat(v.stream().anyMatch(f -> f != 0f)).isTrue();
    }

    @Test
    void crossLingualSameMeaningClustersAboveUnrelated() {
        // Same meaning, different languages should be more similar to each other
        // than either is to an unrelated English sentence. This is the core
        // multilingual property — without it, the swap is pointless.
        float enJp = service.similarity("hello world", "こんにちは世界");
        float enEs = service.similarity("hello world", "hola mundo");
        float enUnrelated = service.similarity("hello world",
            "the quantum entanglement experiment produced unexpected results");

        // Cross-lingual hello pairs should beat unrelated by a clear margin.
        assertThat(enJp).isGreaterThan(enUnrelated + 0.05f);
        assertThat(enEs).isGreaterThan(enUnrelated + 0.05f);
        // Calibrated lower bound: real models on these pairs come in well above
        // 0.5; if we drop below that something's wrong with pooling/normalize.
        assertThat(enEs).isGreaterThan(0.5f);
    }

    @Test
    void crossLingualEmotionPair() {
        // Sad in en/es/ja should cluster vs an unrelated technical sentence.
        float enEs = service.similarity("I feel very sad today",
            "me siento muy triste hoy");
        float enJp = service.similarity("I feel very sad today",
            "今日はとても悲しい");
        float unrelated = service.similarity("I feel very sad today",
            "implementing a binary search tree in java");
        assertThat(enEs).isGreaterThan(unrelated);
        assertThat(enJp).isGreaterThan(unrelated);
    }

    @Test
    void identicalTextHasMaxSimilarity() {
        // Sanity — model is at least deterministic and self-similar.
        float sim = service.similarity("Masumi is building Wyrdsekai",
            "Masumi is building Wyrdsekai");
        assertThat(sim).isGreaterThan(0.95f);
    }

    @Test
    void embeddingIsNormalized() {
        var v = service.embed("a multilingual sentence for normalization check");
        double norm = 0;
        for (float f : v) norm += f * f;
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(0.01));
    }
}
