package org.wyrdsekai.daemon.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonStatsTest {

    @Test
    void initialState() {
        var stats = new DaemonStats();
        assertThat(stats.requestsServed()).isZero();
        assertThat(stats.tokensGenerated()).isZero();
        assertThat(stats.activeRequests()).isZero();
        assertThat(stats.avgLatencyMs()).isZero();
    }

    @Test
    void recordCompletion_updatesCounters() {
        var stats = new DaemonStats();
        stats.recordRequestStart();
        assertThat(stats.activeRequests()).isEqualTo(1);

        stats.recordCompletion(1000, 50);
        assertThat(stats.requestsServed()).isEqualTo(1);
        assertThat(stats.tokensGenerated()).isEqualTo(50);
        assertThat(stats.activeRequests()).isZero();
        assertThat(stats.avgLatencyMs()).isEqualTo(1000.0);
    }

    @Test
    void avgLatency_exponentialMovingAverage() {
        var stats = new DaemonStats();

        // First completion: EMA = latency
        stats.recordRequestStart();
        stats.recordCompletion(1000, 10);
        assertThat(stats.avgLatencyMs()).isEqualTo(1000.0);

        // Second completion: EMA smooths toward new value
        stats.recordRequestStart();
        stats.recordCompletion(2000, 10);
        // EMA = 0.1 * 2000 + 0.9 * 1000 = 200 + 900 = 1100
        assertThat(stats.avgLatencyMs()).isCloseTo(1100.0, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void recordFailure_decrementsActive() {
        var stats = new DaemonStats();
        stats.recordRequestStart();
        stats.recordRequestStart();
        assertThat(stats.activeRequests()).isEqualTo(2);

        stats.recordFailure();
        assertThat(stats.activeRequests()).isEqualTo(1);
    }

    @Test
    void uptime_isPositive() {
        var stats = new DaemonStats();
        assertThat(stats.uptime().toMillis()).isGreaterThanOrEqualTo(0);
        assertThat(stats.uptimeFormatted()).contains("h");
    }

    @Test
    void queueDepth_settable() {
        var stats = new DaemonStats();
        stats.setQueueDepth(5);
        assertThat(stats.queueDepth()).isEqualTo(5);
    }
}
