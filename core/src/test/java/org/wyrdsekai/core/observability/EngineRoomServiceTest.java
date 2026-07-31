package org.wyrdsekai.core.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EngineRoomServiceTest {

    private EngineRoomService service;

    @BeforeEach
    void setUp() {
        service = new EngineRoomService();
    }

    @Test void initially_empty() {
        assertThat(service.metricCount()).isEqualTo(0);
        assertThat(service.allAlerts()).isEmpty();
    }

    @Test void record_metric() {
        service.record("rooms.count", 5.0);

        assertThat(service.metricCount()).isEqualTo(1);
        assertThat(service.getGauge("rooms.count")).isPresent();
        assertThat(service.getGauge("rooms.count").getAsDouble()).isEqualTo(5.0);
    }

    @Test void record_with_tags() {
        service.record("room.events", 42, Map.of("room", "nexus"));

        var history = service.getHistory("room.events", 10);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).tags()).containsEntry("room", "nexus");
    }

    @Test void gauge_latest_wins() {
        service.setGauge("agents.active", 3.0);
        service.setGauge("agents.active", 5.0);

        assertThat(service.getGauge("agents.active").getAsDouble()).isEqualTo(5.0);
    }

    @Test void gauge_not_found() {
        assertThat(service.getGauge("missing")).isEmpty();
    }

    @Test void metric_history_limited() {
        for (int i = 0; i < 10; i++) {
            service.record("metric", i);
        }

        var history = service.getHistory("metric", 5);
        assertThat(history).hasSize(5);
        // Last 5 entries (indices 5-9)
        assertThat(history.get(0).value()).isEqualTo(5.0);
    }

    @Test void threshold_triggers_alert() {
        service.addThreshold(new EngineRoomService.Threshold(
            "heap.used", EngineRoomService.AlertSeverity.WARNING, 80.0,
            EngineRoomService.ThresholdDirection.ABOVE));

        service.record("heap.used", 85.0);

        var alerts = service.activeAlerts();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).severity()).isEqualTo(EngineRoomService.AlertSeverity.WARNING);
        assertThat(alerts.get(0).actualValue()).isEqualTo(85.0);
    }

    @Test void threshold_below() {
        service.addThreshold(new EngineRoomService.Threshold(
            "agents.active", EngineRoomService.AlertSeverity.CRITICAL, 1.0,
            EngineRoomService.ThresholdDirection.BELOW));

        service.record("agents.active", 0.0);

        assertThat(service.activeAlerts()).hasSize(1);
        assertThat(service.activeAlerts().get(0).severity())
            .isEqualTo(EngineRoomService.AlertSeverity.CRITICAL);
    }

    @Test void threshold_not_triggered() {
        service.addThreshold(new EngineRoomService.Threshold(
            "heap.used", EngineRoomService.AlertSeverity.WARNING, 80.0,
            EngineRoomService.ThresholdDirection.ABOVE));

        service.record("heap.used", 50.0);

        assertThat(service.activeAlerts()).isEmpty();
    }

    @Test void acknowledge_alert() {
        service.addThreshold(new EngineRoomService.Threshold(
            "test", EngineRoomService.AlertSeverity.WARNING, 0.0,
            EngineRoomService.ThresholdDirection.ABOVE));
        service.record("test", 1.0);

        var alert = service.activeAlerts().get(0);
        service.acknowledgeAlert(alert.id());

        assertThat(service.activeAlerts()).isEmpty();
        assertThat(service.allAlerts()).hasSize(1);
        assertThat(service.allAlerts().get(0).acknowledged()).isTrue();
    }

    @Test void acknowledge_nonexistent() {
        assertThat(service.acknowledgeAlert("ghost")).isFalse();
    }

    @Test void health_snapshot() {
        service.setGauge("rooms.count", 8.0);
        service.setGauge("agents.active", 3.0);

        var health = service.healthSnapshot();
        assertThat(health.uptimeSeconds()).isGreaterThanOrEqualTo(0);
        assertThat(health.heapUsedMb()).isGreaterThan(0);
        assertThat(health.heapMaxMb()).isGreaterThan(0);
        assertThat(health.threadCount()).isGreaterThan(0);
        assertThat(health.roomCount()).isEqualTo(8);
        assertThat(health.activeAgents()).isEqualTo(3);
    }

    @Test void threshold_count() {
        assertThat(service.thresholdCount()).isEqualTo(0);
        service.addThreshold(new EngineRoomService.Threshold(
            "test", EngineRoomService.AlertSeverity.INFO, 0.0,
            EngineRoomService.ThresholdDirection.ABOVE));
        assertThat(service.thresholdCount()).isEqualTo(1);
    }

    @Test void describe_includes_uptime() {
        var desc = service.describe();
        assertThat(desc).contains("Engine Room");
        assertThat(desc).contains("Uptime");
        assertThat(desc).contains("Heap");
    }
}
