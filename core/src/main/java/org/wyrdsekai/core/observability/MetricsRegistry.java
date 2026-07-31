package org.wyrdsekai.core.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Prometheus-style metrics registry (§105.3).
 * Counters, gauges, and histograms for Wyrdsekai system metrics.
 */
public class MetricsRegistry {

    /** A counter metric (monotonically increasing). */
    public record CounterValue(String name, Map<String, String> labels, long value) {}

    /** A gauge metric (can go up or down). */
    public record GaugeValue(String name, Map<String, String> labels, double value) {}

    /** A histogram summary. */
    public record HistogramSummary(
        String name,
        Map<String, String> labels,
        long count,
        double sum,
        double min,
        double max,
        double p50,
        double p95,
        double p99
    ) {}

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, Double> gauges = new ConcurrentHashMap<>();
    private final Map<String, List<Double>> histograms = new ConcurrentHashMap<>();

    // ── Counters ──

    /** Increment a counter. */
    public long increment(String name, Map<String, String> labels) {
        var key = metricKey(name, labels);
        return counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    /** Increment by a specific amount. */
    public long incrementBy(String name, Map<String, String> labels, long amount) {
        var key = metricKey(name, labels);
        return counters.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(amount);
    }

    /** Get a counter value. */
    public long counterValue(String name, Map<String, String> labels) {
        var key = metricKey(name, labels);
        var counter = counters.get(key);
        return counter != null ? counter.get() : 0;
    }

    // ── Gauges ──

    /** Set a gauge value. */
    public void gauge(String name, Map<String, String> labels, double value) {
        var key = metricKey(name, labels);
        gauges.put(key, value);
    }

    /** Get a gauge value. */
    public double gaugeValue(String name, Map<String, String> labels) {
        var key = metricKey(name, labels);
        return gauges.getOrDefault(key, 0.0);
    }

    // ── Histograms ──

    /** Record a histogram observation. */
    public void observe(String name, Map<String, String> labels, double value) {
        var key = metricKey(name, labels);
        histograms.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
            .add(value);
    }

    /** Record a duration observation. */
    public void observeDuration(String name, Map<String, String> labels, Duration duration) {
        observe(name, labels, duration.toMillis() / 1000.0);
    }

    /** Get histogram summary. */
    public Optional<HistogramSummary> histogramSummary(String name, Map<String, String> labels) {
        var key = metricKey(name, labels);
        var values = histograms.get(key);
        if (values == null || values.isEmpty()) return Optional.empty();

        List<Double> sorted;
        synchronized (values) {
            sorted = new ArrayList<>(values);
        }
        Collections.sort(sorted);

        return Optional.of(new HistogramSummary(
            name, labels, sorted.size(),
            sorted.stream().mapToDouble(Double::doubleValue).sum(),
            sorted.get(0),
            sorted.get(sorted.size() - 1),
            percentile(sorted, 0.50),
            percentile(sorted, 0.95),
            percentile(sorted, 0.99)
        ));
    }

    // ── Standard Wyrdsekai Metrics ──

    /** Record inference call. */
    public void recordInference(String agentDid, String model, Duration duration, int tokens) {
        var labels = Map.of("agent", agentDid, "model", model);
        observeDuration("wyrd_inference_duration_seconds", labels, duration);
        incrementBy("wyrd_inference_tokens_total", labels, tokens);
    }

    /** Record MCP tool call. */
    public void recordMcpCall(String service, String tool, boolean success) {
        var labels = Map.of("service", service, "tool", tool);
        increment("wyrd_mcp_calls_total", labels);
        if (!success) increment("wyrd_mcp_errors_total", labels);
    }

    /** Update vitality gauge. */
    public void updateVitality(String agentDid, String tank, double value) {
        gauge("wyrd_vitality_level", Map.of("agent", agentDid, "tank", tank), value);
    }

    /** Record Forge consolidation. */
    public void recordForge(String agentDid, Duration duration) {
        observeDuration("wyrd_forge_duration_seconds", Map.of("agent", agentDid), duration);
    }

    /** Update bond count gauge. */
    public void updateBondCount(String agentDid, int count) {
        gauge("wyrd_bond_count", Map.of("agent", agentDid), count);
    }

    /** Record A2A message. */
    public void recordA2aMessage(String direction) {
        increment("wyrd_a2a_messages_total", Map.of("direction", direction));
    }

    /** Update room occupancy. */
    public void updateRoomOccupancy(String roomId, int count) {
        gauge("wyrd_room_occupancy", Map.of("room", roomId), count);
    }

    /** Update context token usage. */
    public void updateContextTokens(String agentDid, int tokens) {
        gauge("wyrd_context_tokens_used", Map.of("agent", agentDid), tokens);
    }

    // ── Resilience Metrics ──

    /** Update circuit breaker state gauge (0=CLOSED, 1=OPEN, 2=HALF_OPEN). */
    public void updateCircuitBreaker(String breakerName, int stateOrdinal) {
        gauge("wyrd_circuit_breaker_state", Map.of("name", breakerName), stateOrdinal);
    }

    /** Increment inference rejected counter. */
    public void incrementInferenceRejected(String backend) {
        increment("wyrd_inference_rejected_total", Map.of("backend", backend));
    }

    /** Update inference queue depth gauge. */
    public void updateInferenceQueueDepth(String backend, int depth) {
        gauge("wyrd_inference_queue_depth", Map.of("backend", backend), depth);
    }

    /** Increment event stream dropped counter. */
    public void incrementEventStreamDropped(String subscriber) {
        increment("wyrd_event_stream_dropped_total", Map.of("subscriber", subscriber));
    }

    /** Increment WebSocket throttled counter. */
    public void incrementWebsocketThrottled(String session) {
        increment("wyrd_websocket_throttled_total", Map.of("session", session));
    }

    /** Update degradation level gauge. */
    public void updateDegradationLevel(int level) {
        gauge("wyrd_degradation_level", Map.of(), level);
    }

    /** Increment NATS coalesced counter. */
    public void incrementNatsCoalesced() {
        increment("wyrd_nats_publish_coalesced_total", Map.of());
    }

    /** Update room subscriber count gauge. */
    public void updateRoomSubscriberCount(String roomId, int count) {
        gauge("wyrd_room_subscriber_count", Map.of("room", roomId), count);
    }

    /** Increment SQLite busy retries counter. */
    public void incrementSqliteBusyRetries() {
        increment("wyrd_sqlite_busy_retries_total", Map.of());
    }

    // ── Export ──

    /** Export all metrics in OpenMetrics text format. */
    public String exportOpenMetrics() {
        var sb = new StringBuilder();

        counters.forEach((key, value) ->
            sb.append(key).append(" ").append(value.get()).append("\n"));
        gauges.forEach((key, value) ->
            sb.append(key).append(" ").append(value).append("\n"));
        histograms.forEach((key, values) -> {
            var summary = histogramSummary(extractName(key), extractLabels(key));
            summary.ifPresent(s -> {
                sb.append(key).append("_count ").append(s.count()).append("\n");
                sb.append(key).append("_sum ").append(s.sum()).append("\n");
            });
        });

        return sb.toString();
    }

    public int metricCount() {
        return counters.size() + gauges.size() + histograms.size();
    }

    // ── Internal ──

    private String metricKey(String name, Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) return name;
        var sorted = new TreeMap<>(labels);
        var sb = new StringBuilder(name).append("{");
        var first = true;
        for (var entry : sorted.entrySet()) {
            if (!first) sb.append(",");
            sb.append(entry.getKey()).append("=\"").append(entry.getValue()).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private double percentile(List<Double> sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private String extractName(String key) {
        int idx = key.indexOf('{');
        return idx > 0 ? key.substring(0, idx) : key;
    }

    private Map<String, String> extractLabels(String key) {
        int start = key.indexOf('{');
        if (start < 0) return Map.of();
        // Simplified — not full parser
        return Map.of();
    }
}
