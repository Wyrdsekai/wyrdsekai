package org.wyrdsekai.core.observability;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Engine Room observability service (§55).
 * Collects metrics, manages alerts, and detects anomalies.
 * Feeds the engine-room.js room script for in-world observability.
 */
public class EngineRoomService {

    /** A metric data point. */
    public record MetricPoint(String name, double value, Instant timestamp, Map<String, String> tags) {}

    /** An alert triggered by a metric threshold. */
    public record Alert(
        String id,
        String metricName,
        AlertSeverity severity,
        String message,
        double threshold,
        double actualValue,
        Instant triggeredAt,
        boolean acknowledged
    ) {}

    public enum AlertSeverity { INFO, WARNING, CRITICAL }

    /** A configured threshold that triggers alerts. */
    public record Threshold(String metricName, AlertSeverity severity,
                             double value, ThresholdDirection direction) {}

    public enum ThresholdDirection { ABOVE, BELOW }

    /** System health snapshot. */
    public record HealthSnapshot(
        long uptimeSeconds,
        double heapUsedMb,
        double heapMaxMb,
        int threadCount,
        double cpuLoad,
        int roomCount,
        int activeAgents,
        int pendingInference,
        Map<String, Double> customMetrics
    ) {}

    private final Map<String, Deque<MetricPoint>> metricHistory = new ConcurrentHashMap<>();
    private final List<Alert> alerts = Collections.synchronizedList(new ArrayList<>());
    private final List<Threshold> thresholds = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Double> gauges = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY_PER_METRIC = 300; // ~5 minutes at 1/second
    private int nextAlertId = 1;

    /** Record a metric value. */
    public void record(String name, double value) {
        record(name, value, Map.of());
    }

    /** Record a metric value with tags. */
    public void record(String name, double value, Map<String, String> tags) {
        var point = new MetricPoint(name, value, Instant.now(), tags);
        metricHistory.computeIfAbsent(name, _ -> new ConcurrentLinkedDeque<>()).addLast(point);
        gauges.put(name, value);

        // Trim history
        var history = metricHistory.get(name);
        while (history.size() > MAX_HISTORY_PER_METRIC) {
            history.pollFirst();
        }

        // Check thresholds
        checkThresholds(name, value);
    }

    /** Set a gauge value (latest-wins, no history). */
    public void setGauge(String name, double value) {
        gauges.put(name, value);
    }

    /** Get current gauge value. */
    public OptionalDouble getGauge(String name) {
        var val = gauges.get(name);
        return val != null ? OptionalDouble.of(val) : OptionalDouble.empty();
    }

    /** Get metric history for a named metric. */
    public List<MetricPoint> getHistory(String name, int limit) {
        var history = metricHistory.get(name);
        if (history == null) return List.of();
        var list = new ArrayList<>(history);
        if (list.size() <= limit) return list;
        return list.subList(list.size() - limit, list.size());
    }

    /** Add an alert threshold. */
    public void addThreshold(Threshold threshold) {
        thresholds.add(threshold);
    }

    /** Get active (unacknowledged) alerts. */
    public List<Alert> activeAlerts() {
        synchronized (alerts) {
            return alerts.stream().filter(a -> !a.acknowledged()).toList();
        }
    }

    /** Get all alerts. */
    public List<Alert> allAlerts() {
        synchronized (alerts) {
            return List.copyOf(alerts);
        }
    }

    /** Acknowledge an alert. */
    public boolean acknowledgeAlert(String alertId) {
        synchronized (alerts) {
            for (int i = 0; i < alerts.size(); i++) {
                if (alerts.get(i).id().equals(alertId)) {
                    var old = alerts.get(i);
                    alerts.set(i, new Alert(old.id(), old.metricName(), old.severity(),
                        old.message(), old.threshold(), old.actualValue(),
                        old.triggeredAt(), true));
                    return true;
                }
            }
        }
        return false;
    }

    /** Build a system health snapshot. */
    public HealthSnapshot healthSnapshot() {
        var runtime = Runtime.getRuntime();
        var mxBean = ManagementFactory.getRuntimeMXBean();
        var threadMxBean = ManagementFactory.getThreadMXBean();

        double heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0);
        double heapMax = runtime.maxMemory() / (1024.0 * 1024.0);
        long uptime = mxBean.getUptime() / 1000;
        int threads = threadMxBean.getThreadCount();

        return new HealthSnapshot(
            uptime, heapUsed, heapMax, threads,
            gauges.getOrDefault("system.cpu_load", 0.0),
            gauges.getOrDefault("rooms.count", 0.0).intValue(),
            gauges.getOrDefault("agents.active", 0.0).intValue(),
            gauges.getOrDefault("inference.pending", 0.0).intValue(),
            Map.copyOf(gauges)
        );
    }

    /** Number of tracked metrics. */
    public int metricCount() {
        return metricHistory.size();
    }

    /** Number of configured thresholds. */
    public int thresholdCount() {
        return thresholds.size();
    }

    /** Human-readable summary for engine-room display. */
    public String describe() {
        var health = healthSnapshot();
        var sb = new StringBuilder("=== Engine Room ===\n\n");
        sb.append("Uptime: ").append(health.uptimeSeconds() / 60).append(" min\n");
        sb.append("Heap: ").append(String.format("%.1f", health.heapUsedMb()))
            .append("/").append(String.format("%.0f", health.heapMaxMb())).append(" MB\n");
        sb.append("Threads: ").append(health.threadCount()).append("\n");
        sb.append("Rooms: ").append(health.roomCount()).append("\n");
        sb.append("Active agents: ").append(health.activeAgents()).append("\n");
        sb.append("Pending inference: ").append(health.pendingInference()).append("\n");

        var active = activeAlerts();
        if (!active.isEmpty()) {
            sb.append("\nAlerts (").append(active.size()).append("):\n");
            active.stream().limit(5).forEach(a ->
                sb.append("  [").append(a.severity()).append("] ")
                    .append(a.message()).append("\n"));
        }

        return sb.toString().stripTrailing();
    }

    private void checkThresholds(String metricName, double value) {
        for (var threshold : thresholds) {
            if (!threshold.metricName().equals(metricName)) continue;
            boolean triggered = switch (threshold.direction()) {
                case ABOVE -> value > threshold.value();
                case BELOW -> value < threshold.value();
            };
            if (triggered) {
                var alertId = "alert-" + nextAlertId++;
                var message = metricName + " is " + String.format("%.2f", value)
                    + " (" + threshold.direction().name().toLowerCase()
                    + " threshold " + String.format("%.2f", threshold.value()) + ")";
                synchronized (alerts) {
                    alerts.add(new Alert(alertId, metricName, threshold.severity(),
                        message, threshold.value(), value, Instant.now(), false));
                }
            }
        }
    }
}
