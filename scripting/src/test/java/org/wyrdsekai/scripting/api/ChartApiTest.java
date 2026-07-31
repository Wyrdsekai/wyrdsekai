package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ItemWorldApi.ChartApi} surface
 * tests. Pin: capability gating, provider delegation, ASCII implicit-Tier-1.
 */
class ChartApiTest {

    @Test
    void chart_bar_routes_through_provider_when_capability_held() {
        var captured = new AtomicReference<String>();
        var provider = new StubChartProvider() {
            @Override public Map<String, Object> chartBar(List<Map<String, Object>> data,
                                                            Map<String, Object> opts) {
                captured.set("bar");
                return Map.of("ok", true, "kind", "bar", "id", "chart_x");
            }
        };
        var api = new ItemWorldApi(provider, ItemCapabilitySet.of(List.of("chart.render")));
        var res = api.chart.bar(List.of(Map.of("category", "a", "value", 1)));
        assertThat(captured.get()).isEqualTo("bar");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("kind")).isEqualTo("bar");
    }

    @Test
    void chart_bar_denied_without_capability() {
        var api = new ItemWorldApi(new StubChartProvider(),
            ItemCapabilitySet.of(List.of()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            api.chart.bar(List.of(Map.of("category", "a", "value", 1))))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void chart_line_scatter_pie_heatmap_histogram_all_gated() {
        var api = new ItemWorldApi(new StubChartProvider(),
            ItemCapabilitySet.of(List.of()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api.chart.line(List.of()))
            .isInstanceOf(CapabilityDeniedError.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api.chart.scatter(List.of()))
            .isInstanceOf(CapabilityDeniedError.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api.chart.pie(List.of()))
            .isInstanceOf(CapabilityDeniedError.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api.chart.heatmap(List.of()))
            .isInstanceOf(CapabilityDeniedError.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api.chart.histogram(List.<Number>of()))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void chart_ascii_does_not_require_capability() {
        var captured = new AtomicReference<Boolean>(false);
        var provider = new StubChartProvider() {
            @Override public Map<String, Object> chartAscii(List<Map<String, Object>> data,
                                                              Map<String, Object> opts) {
                captured.set(true);
                return Map.of("ok", true, "payload", "###");
            }
        };
        var api = new ItemWorldApi(provider, ItemCapabilitySet.of(List.of()));
        var res = api.chart.ascii(List.of(Map.of("y", 1)));
        assertThat(captured.get()).isTrue();
        assertThat(res.get("payload")).isEqualTo("###");
    }

    @Test
    void chart_vega_passes_user_spec_through_to_provider() {
        var captured = new AtomicReference<Map<String, Object>>();
        var provider = new StubChartProvider() {
            @Override public Map<String, Object> chartVega(Map<String, Object> spec) {
                captured.set(spec);
                return Map.of("ok", true);
            }
        };
        var api = new ItemWorldApi(provider, ItemCapabilitySet.of(List.of("chart.render")));
        var spec = Map.<String, Object>of("mark", "rule");
        api.chart.vega(spec);
        assertThat(captured.get().get("mark")).isEqualTo("rule");
    }

    @Test
    void chart_default_provider_returns_not_wired_error() {
        var api = new ItemWorldApi(new StubChartProvider(),
            ItemCapabilitySet.of(List.of("chart.render")));
        var res = api.chart.bar(List.of());
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat(res.get("error").toString()).contains("not wired");
    }

    @Test
    void chart_overload_without_opts_passes_empty_map() {
        var capturedOpts = new AtomicReference<Map<String, Object>>();
        var provider = new StubChartProvider() {
            @Override public Map<String, Object> chartBar(List<Map<String, Object>> data,
                                                            Map<String, Object> opts) {
                capturedOpts.set(opts);
                return Map.of("ok", true);
            }
        };
        var api = new ItemWorldApi(provider, ItemCapabilitySet.of(List.of("chart.render")));
        api.chart.bar(List.of());
        assertThat(capturedOpts.get()).isNotNull();
        assertThat(capturedOpts.get()).isEmpty();
    }

    /** Minimal provider that lets the default chart-not-wired stubs through. */
    private static class StubChartProvider implements ItemWorldApiProvider {
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
    }
}
