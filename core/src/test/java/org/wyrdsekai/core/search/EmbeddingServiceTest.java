package org.wyrdsekai.core.search;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the in-process ONNX embedding service (MiniLM-L6-v2).
 * Requires the model file in classpath: models/minilm-l6-v2-q8.onnx
 */
class EmbeddingServiceTest {

    private static EmbeddingService service;

    @BeforeAll
    static void setUp() {
        service = EmbeddingService.init();
        Assumptions.assumeTrue(service != null, "EmbeddingService not available (model missing?)");
    }

    @AfterAll
    static void tearDown() {
        if (service != null) service.close();
    }

    @Test
    void embeddingHas384Dimensions() {
        var embedding = service.embed("Hello world");
        assertThat(embedding).hasSize(384);
    }

    @Test
    void embeddingIsNormalized() {
        var embedding = service.embed("test sentence");
        double norm = 0;
        for (float v : embedding) norm += v * v;
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(0.01));
    }

    @Test
    void similarTextHasHighSimilarity() {
        float sim = service.similarity(
            "I prefer dark mode for all interfaces",
            "User always prefers dark mode on every application");
        assertThat(sim).isGreaterThan(0.7f);
    }

    @Test
    void differentTextHasLowerSimilarity() {
        float simSimilar = service.similarity(
            "I prefer dark mode for all interfaces",
            "User always prefers dark mode on every application");
        float simDifferent = service.similarity(
            "I prefer dark mode for all interfaces",
            "The quantum entanglement experiment produced unexpected results");
        // Similar texts should score much higher than unrelated
        assertThat(simSimilar).isGreaterThan(simDifferent);
        assertThat(simSimilar - simDifferent).isGreaterThan(0.05f);
    }

    @Test
    void identicalTextHasMaxSimilarity() {
        float sim = service.similarity(
            "Operator is building Wyrdsekai",
            "Operator is building Wyrdsekai");
        assertThat(sim).isGreaterThan(0.95f);
    }

    @Test
    void emptyTextReturnsZeroVector() {
        var embedding = service.embed("");
        assertThat(embedding).hasSize(384);
        // Zero vector
        assertThat(embedding.stream().allMatch(v -> v == 0f)).isTrue();
    }

    @Test
    void nullTextReturnsZeroVector() {
        var embedding = service.embed(null);
        assertThat(embedding).hasSize(384);
    }

    @Test
    void semanticallyRelatedConceptsCluster() {
        // Emotions should be more similar to each other than to code
        float emotionSim = service.similarity(
            "feeling sad and lonely",
            "experiencing grief and sorrow");
        float codeSim = service.similarity(
            "feeling sad and lonely",
            "implementing a binary search tree in Java");
        assertThat(emotionSim).isGreaterThan(codeSim);
    }

    @Test
    void embeddingIsDeterministic() {
        var e1 = service.embed("test reproducibility");
        var e2 = service.embed("test reproducibility");
        assertThat(e1).isEqualTo(e2);
    }

    @Test
    void longTextTruncatedGracefully() {
        // 1000-word text should work without error (truncated to MAX_SEQ_LENGTH)
        var longText = "This is a test sentence. ".repeat(200);
        var embedding = service.embed(longText);
        assertThat(embedding).hasSize(384);
        // Should not be zero vector
        assertThat(embedding.stream().anyMatch(v -> v != 0f)).isTrue();
    }
}
