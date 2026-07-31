package org.wyrdsekai.server.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthRoutesTest {

    @Test void initially_not_ready() {
        var metrics = new MetricsCollector();
        var health = new HealthRoutes(metrics);
        assertThat(health.isReady()).isFalse();
    }

    @Test void set_ready() {
        var metrics = new MetricsCollector();
        var health = new HealthRoutes(metrics);
        health.setReady(true);
        assertThat(health.isReady()).isTrue();
    }

    @Test void set_not_ready() {
        var metrics = new MetricsCollector();
        var health = new HealthRoutes(metrics);
        health.setReady(true);
        health.setReady(false);
        assertThat(health.isReady()).isFalse();
    }
}
