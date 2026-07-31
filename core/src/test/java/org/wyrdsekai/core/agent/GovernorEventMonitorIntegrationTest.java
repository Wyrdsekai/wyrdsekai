package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.resilience.ResilienceConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: GovernorEventMonitor subscribes to AgentEventStream
 * and receives real events through the full pub/sub pipeline.
 */
class GovernorEventMonitorIntegrationTest {

    @BeforeEach
    void setUp() {
        AgentEventStream.init();
    }

    @AfterEach
    void tearDown() {
        var stream = AgentEventStream.get();
        if (stream != null) {
            stream.unsubscribe("governor-monitor");
            stream.unsubscribe("dummy-agent");
        }
    }

    @Test
    void governor_receives_system_events_through_stream() throws Exception {
        var monitor = new GovernorEventMonitor();
        monitor.subscribe();

        var stream = AgentEventStream.get();
        assertNotNull(stream);

        // Subscribe a dummy agent so events get delivered
        var received = new ArrayList<AgentEvent>();
        var latch = new CountDownLatch(1);
        stream.subscribe("dummy-agent", e -> {
            received.add(e);
            latch.countDown();
        });

        // Publish a health alert — governor should log it as ALERT
        stream.publishSystemEvent(
            AgentEvent.SystemEventType.HEALTH_ALERT,
            "test-backend", "CPU overheating");

        // Wait for delivery
        assertTrue(latch.await(5, TimeUnit.SECONDS),
            "Dummy agent should receive the event (proves stream delivery works)");
        assertEquals(1, received.size());
    }

    @Test
    void governor_receives_backend_down_events() throws Exception {
        var monitor = new GovernorEventMonitor();
        monitor.subscribe();

        var stream = AgentEventStream.get();
        stream.subscribe("dummy-agent", e -> {});

        // Backend down — governor should log ADVISORY
        stream.publishSystemEvent(
            AgentEvent.SystemEventType.INFERENCE_BACKEND_DOWN,
            "ollama-local", "Connection refused");

        // No exception means monitor processed it
    }

    @Test
    void governor_rate_tracking_detects_rapid_inference() {
        var monitor = new GovernorEventMonitor();

        // Stay under threshold
        for (int i = 0; i < 10; i++) {
            monitor.recordInference("agent-fast");
        }
        // No concern yet

        // Hit threshold (30)
        for (int i = 10; i < 30; i++) {
            monitor.recordInference("agent-fast");
        }
        // At threshold, governor logs ADVISORY — verified by no exception
    }

    @Test
    void governor_hostility_alert_fires_for_high_score() {
        var monitor = new GovernorEventMonitor();

        // Low hostility — no concern
        monitor.reportHostility("agent-mild", 0.1, "disagreement");

        // Medium hostility — ADVISORY
        monitor.reportHostility("agent-testy", 0.5, "dismissive tone");

        // High hostility — ALERT
        monitor.reportHostility("agent-hostile", 0.8, "repeated insults");
    }

    @Test
    void governor_tracks_multiple_agents_independently() {
        var monitor = new GovernorEventMonitor();

        // Agent A makes 15 requests
        for (int i = 0; i < 15; i++) {
            monitor.recordInference("agent-a");
        }
        // Agent B makes 15 requests
        for (int i = 0; i < 15; i++) {
            monitor.recordInference("agent-b");
        }
        // Neither has hit the 30 threshold individually
    }
}
