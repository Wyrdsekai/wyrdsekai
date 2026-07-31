package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * chart rendering: Vega-Lite spec
 * shape + ASCII fallback + ID generation are pinned here.
 */
class ChartServiceTest {

    private final ChartService svc = new ChartService();

    @Test
    void bar_chart_emits_vega_lite_spec_with_data_and_encoding() {
        var data = List.of(
            Map.<String, Object>of("category", "alpha", "value", 12),
            Map.<String, Object>of("category", "beta", "value", 7));
        var art = svc.bar(data, Map.of("title", "Counts", "xLabel", "name", "yLabel", "n"));
        assertThat(art.kind()).isEqualTo("bar");
        assertThat(art.mime()).isEqualTo(ChartService.VEGA_MIME);
        assertThat(art.title()).isEqualTo("Counts");
        @SuppressWarnings("unchecked")
        var spec = (Map<String, Object>) art.payload();
        assertThat(spec).containsKey("$schema");
        assertThat(spec.get("mark")).isEqualTo("bar");
        var dataMap = (Map<?, ?>) spec.get("data");
        assertThat((List<?>) dataMap.get("values")).hasSize(2);
    }

    @Test
    void line_chart_uses_quantitative_encoding_by_default() {
        var data = List.of(
            Map.<String, Object>of("x", 1, "y", 1.0),
            Map.<String, Object>of("x", 2, "y", 4.0),
            Map.<String, Object>of("x", 3, "y", 9.0));
        var art = svc.line(data, Map.of());
        var spec = (Map<?, ?>) art.payload();
        var enc = (Map<?, ?>) spec.get("encoding");
        var x = (Map<?, ?>) enc.get("x");
        assertThat(x.get("type")).isEqualTo("quantitative");
    }

    @Test
    void scatter_chart_marks_filled_points() {
        var data = List.of(Map.<String, Object>of("x", 1, "y", 2));
        var art = svc.scatter(data, null);
        var spec = (Map<?, ?>) art.payload();
        var mark = (Map<?, ?>) spec.get("mark");
        assertThat(mark.get("type")).isEqualTo("point");
        assertThat(mark.get("filled")).isEqualTo(true);
    }

    @Test
    void pie_chart_uses_arc_mark_and_theta_encoding() {
        var data = List.of(
            Map.<String, Object>of("category", "a", "value", 1),
            Map.<String, Object>of("category", "b", "value", 2));
        var art = svc.pie(data, Map.of());
        var spec = (Map<?, ?>) art.payload();
        var mark = (Map<?, ?>) spec.get("mark");
        assertThat(mark.get("type")).isEqualTo("arc");
        @SuppressWarnings("unchecked")
        var enc = (Map<String, Object>) spec.get("encoding");
        assertThat(enc).containsKey("theta");
        assertThat(enc).containsKey("color");
    }

    @Test
    void heatmap_uses_rect_mark() {
        var data = List.of(
            Map.<String, Object>of("x", "Mon", "y", "9", "value", 1.0),
            Map.<String, Object>of("x", "Mon", "y", "10", "value", 0.5));
        var art = svc.heatmap(data, Map.of());
        var spec = (Map<?, ?>) art.payload();
        assertThat(spec.get("mark")).isEqualTo("rect");
    }

    @Test
    void histogram_default_bins_uses_sturges_rule() {
        var values = List.<Number>of(1, 2, 3, 4, 5, 6, 7, 8);
        var art = svc.histogram(values, Map.of());
        var spec = (Map<?, ?>) art.payload();
        var enc = (Map<?, ?>) spec.get("encoding");
        var x = (Map<?, ?>) enc.get("x");
        var bin = (Map<?, ?>) x.get("bin");
        assertThat((Integer) bin.get("maxbins")).isGreaterThanOrEqualTo(3);
    }

    @Test
    void vega_passthrough_preserves_user_spec_and_adds_schema() {
        var customSpec = Map.<String, Object>of(
            "mark", "rule",
            "data", Map.of("values", List.of(Map.of("v", 1))));
        var art = svc.vega(customSpec);
        var spec = (Map<?, ?>) art.payload();
        assertThat(spec.get("mark")).isEqualTo("rule");
        assertThat(spec.get("$schema").toString()).contains("vega-lite");
    }

    @Test
    void ascii_renders_horizontal_bars_when_categorical() {
        var data = List.of(
            Map.<String, Object>of("category", "alpha", "value", 10),
            Map.<String, Object>of("category", "beta", "value", 5));
        var art = svc.ascii(data, Map.of("title", "Test"));
        assertThat(art.mime()).isEqualTo(ChartService.ASCII_MIME);
        var text = art.payload().toString();
        assertThat(text).contains("Test").contains("alpha").contains("#");
    }

    @Test
    void ascii_renders_sparkline_when_no_category() {
        var data = List.of(
            Map.<String, Object>of("y", 1.0),
            Map.<String, Object>of("y", 5.0),
            Map.<String, Object>of("y", 3.0));
        var art = svc.ascii(data, Map.of());
        var text = art.payload().toString();
        assertThat(text).contains("min=").contains("max=");
    }

    @Test
    void each_call_produces_unique_id() {
        var a1 = svc.bar(List.of(), Map.of());
        var a2 = svc.bar(List.of(), Map.of());
        assertThat(a1.id()).isNotEqualTo(a2.id());
        assertThat(a1.id()).startsWith("chart_");
    }

    @Test
    void empty_or_null_inputs_do_not_throw() {
        var art = svc.bar(null, null);
        assertThat(art.kind()).isEqualTo("bar");
        var ascii = svc.ascii(List.of(), Map.of());
        assertThat(ascii.payload().toString()).contains("empty");
    }
}
