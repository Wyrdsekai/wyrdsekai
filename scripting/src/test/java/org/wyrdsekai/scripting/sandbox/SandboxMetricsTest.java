package org.wyrdsekai.scripting.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxMetricsTest {

    private SandboxMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new SandboxMetrics();
    }

    @Test void initially_empty() {
        assertThat(metrics.trackedRoomCount()).isEqualTo(0);
    }

    @Test void record_execution() {
        metrics.recordExecution("nexus", 1_000_000, 100, 1024, false);

        var m = metrics.getMetrics("nexus");
        assertThat(m.executionCount()).isEqualTo(1);
        assertThat(m.totalCpuNanos()).isEqualTo(1_000_000);
        assertThat(m.totalStatements()).isEqualTo(100);
        assertThat(m.peakMemoryBytes()).isEqualTo(1024);
        assertThat(m.errorCount()).isEqualTo(0);
        assertThat(m.lastExecution()).isNotNull();
    }

    @Test void multiple_executions_accumulate() {
        metrics.recordExecution("nexus", 1_000_000, 100, 1024, false);
        metrics.recordExecution("nexus", 2_000_000, 200, 2048, false);
        metrics.recordExecution("nexus", 3_000_000, 300, 512, true);

        var m = metrics.getMetrics("nexus");
        assertThat(m.executionCount()).isEqualTo(3);
        assertThat(m.totalCpuNanos()).isEqualTo(6_000_000);
        assertThat(m.totalStatements()).isEqualTo(600);
        assertThat(m.peakMemoryBytes()).isEqualTo(2048); // max, not last
        assertThat(m.errorCount()).isEqualTo(1);
    }

    @Test void average_cpu() {
        metrics.recordExecution("nexus", 3_000_000, 100, 1024, false);
        metrics.recordExecution("nexus", 6_000_000, 200, 1024, false);

        var m = metrics.getMetrics("nexus");
        // averageCpuMs = (9_000_000 / 1_000_000) / 2 = 4
        assertThat(m.averageCpuMs()).isEqualTo(4);
    }

    @Test void average_statements() {
        metrics.recordExecution("nexus", 1000, 100, 1024, false);
        metrics.recordExecution("nexus", 1000, 300, 1024, false);

        assertThat(metrics.getMetrics("nexus").averageStatements()).isEqualTo(200);
    }

    @Test void error_rate() {
        metrics.recordExecution("nexus", 1000, 100, 1024, true);
        metrics.recordExecution("nexus", 1000, 100, 1024, false);
        metrics.recordExecution("nexus", 1000, 100, 1024, true);

        assertThat(metrics.getMetrics("nexus").errorRate()).isCloseTo(0.666, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test void unknown_room_returns_zeros() {
        var m = metrics.getMetrics("unknown");
        assertThat(m.executionCount()).isEqualTo(0);
        assertThat(m.lastExecution()).isNull();
    }

    @Test void multiple_rooms_tracked() {
        metrics.recordExecution("nexus", 1000, 10, 100, false);
        metrics.recordExecution("vault", 2000, 20, 200, false);

        assertThat(metrics.trackedRoomCount()).isEqualTo(2);
        assertThat(metrics.getMetrics("nexus").executionCount()).isEqualTo(1);
        assertThat(metrics.getMetrics("vault").executionCount()).isEqualTo(1);
    }

    @Test void all_metrics() {
        metrics.recordExecution("nexus", 1000, 10, 100, false);
        metrics.recordExecution("vault", 2000, 20, 200, false);

        var all = metrics.allMetrics();
        assertThat(all).hasSize(2);
        assertThat(all).containsKey("nexus");
        assertThat(all).containsKey("vault");
    }

    @Test void reset_clears_room() {
        metrics.recordExecution("nexus", 1000, 10, 100, false);
        metrics.reset("nexus");

        assertThat(metrics.trackedRoomCount()).isEqualTo(0);
        assertThat(metrics.getMetrics("nexus").executionCount()).isEqualTo(0);
    }
}
