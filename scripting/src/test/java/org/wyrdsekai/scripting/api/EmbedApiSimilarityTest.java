package org.wyrdsekai.scripting.api;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code embed.similarity} is pure math
 * (Tier 1, implicit cap). {@code embed.encode} is Tier 4 (gated). These
 * tests pin the math + the gating behaviour.
 */
class EmbedApiSimilarityTest {

    private final ItemWorldApi.EmbedApi embed = new ItemWorldApi.EmbedApi(
        new StubProvider(), ItemCapabilitySet.UNRESTRICTED);

    @Test
    void identical_vectors_have_cosine_one() {
        var v = List.<Number>of(1.0, 0.0, 0.0);
        assertThat(embed.similarity(v, v)).isEqualTo(1.0, Offset.offset(1e-9));
    }

    @Test
    void orthogonal_vectors_have_cosine_zero() {
        var a = List.<Number>of(1.0, 0.0);
        var b = List.<Number>of(0.0, 1.0);
        assertThat(embed.similarity(a, b)).isZero();
    }

    @Test
    void anti_parallel_vectors_have_cosine_minus_one() {
        var a = List.<Number>of(1.0, 0.0);
        var b = List.<Number>of(-1.0, 0.0);
        assertThat(embed.similarity(a, b)).isEqualTo(-1.0, Offset.offset(1e-9));
    }

    @Test
    void empty_or_null_input_returns_zero() {
        assertThat(embed.similarity(null, List.of(1))).isZero();
        assertThat(embed.similarity(List.of(), List.of(1))).isZero();
        assertThat(embed.similarity(List.of(1), null)).isZero();
    }

    @Test
    void zero_vector_does_not_divide_by_zero() {
        var z = List.<Number>of(0.0, 0.0);
        var v = List.<Number>of(1.0, 1.0);
        assertThat(embed.similarity(z, v)).isZero();
    }

    @Test
    void encode_requires_capability() {
        var restricted = new ItemWorldApi.EmbedApi(
            new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> restricted.encode("hello"))
            .isInstanceOf(CapabilityDeniedError.class)
            .hasMessageContaining("embed.encode");
    }

    @Test
    void encode_with_cap_returns_provider_vector() {
        var caps = ItemCapabilitySet.of(List.of("embed.encode"));
        var api = new ItemWorldApi.EmbedApi(new StubProvider(), caps);
        // StubProvider returns a 4-dim vector for any text
        var v = api.encode("test");
        assertThat(v).containsExactly(0.1, 0.2, 0.3, 0.4);
    }

    @Test
    void mismatched_length_uses_shorter() {
        var a = List.<Number>of(1.0, 2.0, 3.0);
        var b = List.<Number>of(1.0, 2.0);
        // dot of first two components / norms of first two components of each
        var sim = embed.similarity(a, b);
        assertThat(sim).isBetween(0.9, 1.0);
    }

    private static final class StubProvider implements ItemWorldApiProvider {
        @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
        @Override public String webFetch(String url, int max) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String t) {}
        @Override public void agentRemember(String c) {}
        @Override public void agentTell(String t, String m) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }
        @Override public List<Double> embedEncode(String text) {
            return List.of(0.1, 0.2, 0.3, 0.4);
        }
    }
}
