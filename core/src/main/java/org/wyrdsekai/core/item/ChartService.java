package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * chart rendering as Vega-Lite specs +
 * ASCII fallback.
 *
 * <p>Stateless renderer: produces a small {@link ChartArtifact} record per
 * call. The authoritative output is a Vega-Lite v5 JSON spec (clients that
 * speak Vega render natively). For terminal-only consumers (telnet/SSH/plain
 * web) the service also renders a minimal ASCII fallback so the chart is at
 * least *legible* without a renderer.</p>
 *
 * <p>No external rendering dependency — Vega-Lite specs are emitted as
 * {@link Map} structures (Jackson-serialised by callers); ASCII is a tiny
 * scale-and-bar routine. The MVP supports the six block-§4.35 chart kinds
 * (bar / line / scatter / pie / heatmap / histogram) plus a {@code vega}
 * passthrough.</p>
 *
 * <p>Persistence is intentionally absent — chart artifacts are typically
 * either transient (rendered into a scroll, surfaced once, garbage-collected)
 * or stored via {@link ArtifactService#create} when the caller wants
 * durability.</p>
 */
public final class ChartService {

    private static final Logger log = LoggerFactory.getLogger(ChartService.class);

    public static final String VEGA_MIME = "application/vnd.vega.v5+json";
    public static final String ASCII_MIME = "text/plain";

    /** Result of any chart-rendering call. */
    public record ChartArtifact(
        String id,
        String kind,         // "bar" | "line" | "scatter" | "pie" | "heatmap" | "histogram" | "vega" | "ascii"
        String title,
        String mime,         // VEGA_MIME or ASCII_MIME
        Object payload       // Map (Vega-Lite spec) or String (ASCII art)
    ) {}

    public ChartService() {}

    // ─── Public API ─────────────────────────────────────────────

    /** §4.35 {@code world.chart.bar}. */
    public ChartArtifact bar(List<Map<String, Object>> data, Map<String, Object> opts) {
        var spec = vegaSkeleton("bar", title(opts));
        spec.put("data", Map.of("values", safeList(data)));
        spec.put("mark", "bar");
        spec.put("encoding", Map.of(
            "x", axisDef(opts, "x", "ordinal", "category"),
            "y", axisDef(opts, "y", "quantitative", "value")));
        return new ChartArtifact(newId(), "bar", title(opts), VEGA_MIME, spec);
    }

    /** §4.35 {@code world.chart.line}. */
    public ChartArtifact line(List<Map<String, Object>> data, Map<String, Object> opts) {
        var spec = vegaSkeleton("line", title(opts));
        spec.put("data", Map.of("values", safeList(data)));
        spec.put("mark", Map.of("type", "line", "point", true));
        spec.put("encoding", Map.of(
            "x", axisDef(opts, "x", "quantitative", "x"),
            "y", axisDef(opts, "y", "quantitative", "y")));
        return new ChartArtifact(newId(), "line", title(opts), VEGA_MIME, spec);
    }

    /** §4.35 {@code world.chart.scatter}. */
    public ChartArtifact scatter(List<Map<String, Object>> data, Map<String, Object> opts) {
        var spec = vegaSkeleton("scatter", title(opts));
        spec.put("data", Map.of("values", safeList(data)));
        spec.put("mark", Map.of("type", "point", "filled", true));
        spec.put("encoding", Map.of(
            "x", axisDef(opts, "x", "quantitative", "x"),
            "y", axisDef(opts, "y", "quantitative", "y")));
        return new ChartArtifact(newId(), "scatter", title(opts), VEGA_MIME, spec);
    }

    /** §4.35 {@code world.chart.pie}. */
    public ChartArtifact pie(List<Map<String, Object>> data, Map<String, Object> opts) {
        var spec = vegaSkeleton("pie", title(opts));
        spec.put("data", Map.of("values", safeList(data)));
        spec.put("mark", Map.of("type", "arc", "tooltip", true));
        spec.put("encoding", Map.of(
            "theta", Map.of("field", optString(opts, "valueField", "value"),
                "type", "quantitative"),
            "color", Map.of("field", optString(opts, "categoryField", "category"),
                "type", "nominal")));
        return new ChartArtifact(newId(), "pie", title(opts), VEGA_MIME, spec);
    }

    /** §4.35 {@code world.chart.heatmap}. */
    public ChartArtifact heatmap(List<Map<String, Object>> data, Map<String, Object> opts) {
        var spec = vegaSkeleton("heatmap", title(opts));
        spec.put("data", Map.of("values", safeList(data)));
        spec.put("mark", "rect");
        spec.put("encoding", Map.of(
            "x", axisDef(opts, "x", "ordinal", "x"),
            "y", axisDef(opts, "y", "ordinal", "y"),
            "color", Map.of("field", optString(opts, "valueField", "value"),
                "type", "quantitative")));
        return new ChartArtifact(newId(), "heatmap", title(opts), VEGA_MIME, spec);
    }

    /** §4.35 {@code world.chart.histogram}. Auto-bins via Sturges' rule. */
    public ChartArtifact histogram(List<Number> values, Map<String, Object> opts) {
        var safeValues = new ArrayList<Map<String, Object>>();
        if (values != null) {
            for (var v : values) {
                if (v != null) safeValues.add(Map.of("value", v.doubleValue()));
            }
        }
        var spec = vegaSkeleton("histogram", title(opts));
        spec.put("data", Map.of("values", safeValues));
        spec.put("mark", "bar");
        int bins = (int) optNumber(opts, "bins",
            Math.max(1, (int) Math.ceil(Math.log(Math.max(2, safeValues.size())) / Math.log(2)) + 1)).longValue();
        spec.put("encoding", Map.of(
            "x", Map.of("bin", Map.of("maxbins", bins),
                "field", "value", "type", "quantitative",
                "title", optString(opts, "xLabel", "value")),
            "y", Map.of("aggregate", "count", "type", "quantitative",
                "title", optString(opts, "yLabel", "count"))));
        return new ChartArtifact(newId(), "histogram", title(opts), VEGA_MIME, spec);
    }

    /** §4.35 {@code world.chart.vega} — pass-through for raw Vega-Lite specs. */
    public ChartArtifact vega(Map<String, Object> vegaSpec) {
        if (vegaSpec == null) {
            return new ChartArtifact(newId(), "vega", "(empty)", VEGA_MIME, Map.of());
        }
        // Defensive copy + ensure $schema sentinel so older clients can detect.
        var copy = new LinkedHashMap<String, Object>(vegaSpec);
        copy.putIfAbsent("$schema", "https://vega.github.io/schema/vega-lite/v5.json");
        var t = copy.get("title");
        return new ChartArtifact(newId(), "vega",
            t == null ? "(vega)" : t.toString(), VEGA_MIME, copy);
    }

    /** §4.35 {@code world.chart.ascii} — terminal-renderable text chart. */
    public ChartArtifact ascii(List<Map<String, Object>> data, Map<String, Object> opts) {
        var rendered = renderAscii(data, opts);
        return new ChartArtifact(newId(), "ascii", title(opts), ASCII_MIME, rendered);
    }

    // ─── ASCII renderer (sparkline + bar) ───────────────────────

    /**
     * Render either a sparkline (single-series with x/y) or a horizontal
     * bar chart (categorical). Picks based on whether data points carry
     * a {@code category} key.
     */
    private static String renderAscii(List<Map<String, Object>> data, Map<String, Object> opts) {
        if (data == null || data.isEmpty()) return "(empty)";
        boolean categorical = data.getFirst().containsKey("category");
        if (categorical) return renderHorizontalBars(data, opts);
        return renderSparkline(data, opts);
    }

    private static String renderHorizontalBars(List<Map<String, Object>> data, Map<String, Object> opts) {
        int width = (int) optNumber(opts, "width", 40L).longValue();
        double max = 0.0;
        for (var row : data) {
            var v = row.get("value");
            if (v instanceof Number n) max = Math.max(max, Math.abs(n.doubleValue()));
        }
        if (max <= 0.0) max = 1.0;
        int labelWidth = 0;
        for (var row : data) {
            var c = row.get("category");
            if (c != null) labelWidth = Math.max(labelWidth, c.toString().length());
        }
        labelWidth = Math.min(labelWidth, 16);
        var sb = new StringBuilder();
        var t = title(opts);
        if (t != null && !t.isBlank()) sb.append(t).append('\n');
        for (var row : data) {
            var c = row.get("category");
            var v = row.get("value");
            double val = (v instanceof Number n) ? n.doubleValue() : 0.0;
            int bar = (int) Math.round((Math.abs(val) / max) * width);
            var label = c == null ? "" : c.toString();
            if (label.length() > labelWidth) label = label.substring(0, labelWidth);
            sb.append(String.format("%-" + labelWidth + "s ", label));
            for (int i = 0; i < bar; i++) sb.append('#');
            sb.append(' ').append(val).append('\n');
        }
        return sb.toString();
    }

    /** Braille sparkline; falls back to a 7-level char ramp. */
    private static String renderSparkline(List<Map<String, Object>> data, Map<String, Object> opts) {
        var ramp = new char[]{'▁','▂','▃','▄','▅','▆','▇','█'};
        var values = new ArrayList<Double>();
        for (var row : data) {
            var v = row.get("y");
            if (v == null) v = row.get("value");
            if (v instanceof Number n) values.add(n.doubleValue());
        }
        if (values.isEmpty()) return "(empty)";
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double span = Math.max(1e-9, max - min);
        var sb = new StringBuilder();
        var t = title(opts);
        if (t != null && !t.isBlank()) sb.append(t).append('\n');
        for (var v : values) {
            int idx = (int) Math.min(ramp.length - 1,
                Math.max(0, Math.floor((v - min) / span * (ramp.length - 1))));
            sb.append(ramp[idx]);
        }
        sb.append('\n').append("min=").append(min).append(" max=").append(max);
        return sb.toString();
    }

    // ─── Vega-Lite spec helpers ─────────────────────────────────

    private static LinkedHashMap<String, Object> vegaSkeleton(String kind, String title) {
        var m = new LinkedHashMap<String, Object>();
        m.put("$schema", "https://vega.github.io/schema/vega-lite/v5.json");
        if (title != null && !title.isBlank()) m.put("title", title);
        m.put("description", "Wyrdsekai " + kind + " chart");
        return m;
    }

    private static Map<String, Object> axisDef(Map<String, Object> opts, String axis,
                                                  String type, String defaultField) {
        var fieldKey = axis + "Field";
        var labelKey = axis + "Label";
        return Map.of(
            "field", optString(opts, fieldKey, defaultField),
            "type", optString(opts, axis + "Type", type),
            "title", optString(opts, labelKey, defaultField));
    }

    private static String title(Map<String, Object> opts) {
        return optString(opts, "title", "");
    }

    private static String optString(Map<String, Object> opts, String key, String def) {
        if (opts == null) return def;
        var v = opts.get(key);
        return v == null ? def : v.toString();
    }

    private static Number optNumber(Map<String, Object> opts, String key, Number def) {
        if (opts == null) return def;
        var v = opts.get(key);
        if (v instanceof Number n) return n;
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (Exception _) { return def; }
        }
        return def;
    }

    private static <T> List<T> safeList(List<T> in) {
        return in == null ? List.of() : in;
    }

    private static String newId() {
        return "chart_" + UUID.randomUUID().toString().substring(0, 12);
    }
}
