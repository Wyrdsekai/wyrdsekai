package org.wyrdsekai.server.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsCollectorTest {

    private MetricsCollector metrics;

    @BeforeEach
    void setUp() {
        metrics = new MetricsCollector();
    }

    @Test void increment_counter() {
        metrics.increment("requests_total");
        metrics.increment("requests_total");
        assertThat(metrics.getCounter("requests_total")).isEqualTo(2);
    }

    @Test void add_counter() {
        metrics.add("bytes_sent", 1024);
        metrics.add("bytes_sent", 512);
        assertThat(metrics.getCounter("bytes_sent")).isEqualTo(1536);
    }

    @Test void unset_counter_returns_zero() {
        assertThat(metrics.getCounter("nonexistent")).isEqualTo(0);
    }

    @Test void set_gauge() {
        metrics.setGauge("room_count", 42);
        assertThat(metrics.getGauge("room_count")).isEqualTo(42);
    }

    @Test void gauge_overwrite() {
        metrics.setGauge("active_sessions", 10);
        metrics.setGauge("active_sessions", 5);
        assertThat(metrics.getGauge("active_sessions")).isEqualTo(5);
    }

    @Test void unset_gauge_returns_zero() {
        assertThat(metrics.getGauge("nonexistent")).isEqualTo(0);
    }

    @Test void prometheus_format_contains_jvm_metrics() {
        var output = metrics.prometheusFormat();
        assertThat(output).contains("jvm_heap_bytes_used");
        assertThat(output).contains("jvm_heap_bytes_max");
        assertThat(output).contains("jvm_threads_current");
        assertThat(output).contains("jvm_uptime_seconds");
    }

    @Test void prometheus_format_contains_custom_counters() {
        metrics.increment("events_processed");
        var output = metrics.prometheusFormat();
        assertThat(output).contains("wyrdsekai_events_processed");
        assertThat(output).contains("counter");
    }

    @Test void prometheus_format_contains_custom_gauges() {
        metrics.setGauge("room_count", 10);
        var output = metrics.prometheusFormat();
        assertThat(output).contains("wyrdsekai_room_count");
        assertThat(output).contains("gauge");
    }

    @Test void counter_count() {
        metrics.increment("a");
        metrics.increment("b");
        assertThat(metrics.counterCount()).isEqualTo(2);
    }

    @Test void gauge_count() {
        metrics.setGauge("x", 1);
        assertThat(metrics.gaugeCount()).isEqualTo(1);
    }
}
