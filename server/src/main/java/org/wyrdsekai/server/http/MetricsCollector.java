package org.wyrdsekai.server.http;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics collector for Prometheus-format export.
 * Tracks JVM metrics, room count, event rate, inference queue depth, and custom gauges.
 */
public final class MetricsCollector {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    // --- Counter methods ---

    public void increment(String name) {
        counters.computeIfAbsent(name, _ -> new AtomicLong(0)).incrementAndGet();
    }

    public void add(String name, long value) {
        counters.computeIfAbsent(name, _ -> new AtomicLong(0)).addAndGet(value);
    }

    public long getCounter(String name) {
        var counter = counters.get(name);
        return counter != null ? counter.get() : 0;
    }

    // --- Gauge methods ---

    public void setGauge(String name, long value) {
        gauges.computeIfAbsent(name, _ -> new AtomicLong(0)).set(value);
    }

    public long getGauge(String name) {
        var gauge = gauges.get(name);
        return gauge != null ? gauge.get() : 0;
    }

    // --- Prometheus format export ---

    public String prometheusFormat() {
        var sb = new StringBuilder();

        // JVM metrics from ManagementFactory
        var memMXBean = ManagementFactory.getMemoryMXBean();
        var heap = memMXBean.getHeapMemoryUsage();
        var threadMXBean = ManagementFactory.getThreadMXBean();
        var runtimeMXBean = ManagementFactory.getRuntimeMXBean();

        sb.append("# HELP jvm_heap_bytes_used JVM heap memory used in bytes\n");
        sb.append("# TYPE jvm_heap_bytes_used gauge\n");
        sb.append("jvm_heap_bytes_used ").append(heap.getUsed()).append("\n\n");

        sb.append("# HELP jvm_heap_bytes_max JVM heap memory max in bytes\n");
        sb.append("# TYPE jvm_heap_bytes_max gauge\n");
        sb.append("jvm_heap_bytes_max ").append(heap.getMax()).append("\n\n");

        sb.append("# HELP jvm_threads_current Current thread count\n");
        sb.append("# TYPE jvm_threads_current gauge\n");
        sb.append("jvm_threads_current ").append(threadMXBean.getThreadCount()).append("\n\n");

        sb.append("# HELP jvm_uptime_seconds JVM uptime in seconds\n");
        sb.append("# TYPE jvm_uptime_seconds gauge\n");
        sb.append("jvm_uptime_seconds ").append(runtimeMXBean.getUptime() / 1000.0).append("\n\n");

        // Custom counters
        for (var entry : counters.entrySet()) {
            var name = sanitizeName(entry.getKey());
            sb.append("# TYPE wyrdsekai_").append(name).append(" counter\n");
            sb.append("wyrdsekai_").append(name).append(" ").append(entry.getValue().get()).append("\n\n");
        }

        // Custom gauges
        for (var entry : gauges.entrySet()) {
            var name = sanitizeName(entry.getKey());
            sb.append("# TYPE wyrdsekai_").append(name).append(" gauge\n");
            sb.append("wyrdsekai_").append(name).append(" ").append(entry.getValue().get()).append("\n\n");
        }

        return sb.toString();
    }

    /** Count of registered counters. */
    public int counterCount() {
        return counters.size();
    }

    /** Count of registered gauges. */
    public int gaugeCount() {
        return gauges.size();
    }

    private static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
