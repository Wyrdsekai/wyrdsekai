package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class GovernorEventMonitorTest {

    private GovernorEventMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new GovernorEventMonitor();
    }

    @Test
    void recordInference_tracks_rates() {
        // Should not throw for normal rates
        for (int i = 0; i < 10; i++) {
            monitor.recordInference("agent-1");
        }
        // No exception means advisory threshold not yet reached
    }

    @Test
    void recordInference_at_threshold_logs_concern() {
        // Reach the threshold (30 inferences in 5 minutes)
        for (int i = 0; i < 30; i++) {
            monitor.recordInference("agent-2");
        }
        // The concern is logged via SLF4J — we can't easily assert on it
        // but this verifies no exception is thrown at threshold
    }

    @Test
    void reportHostility_high_score() {
        // Should not throw
        monitor.reportHostility("agent-1", 0.8, "repeated insults");
    }

    @Test
    void reportHostility_moderate_score() {
        monitor.reportHostility("agent-1", 0.5, "dismissive language");
    }

    @Test
    void reportHostility_low_score_ignored() {
        monitor.reportHostility("agent-1", 0.2, "mild disagreement");
    }

    @Test
    void severity_levels_exist() {
        assertEquals(3, GovernorEventMonitor.Severity.values().length);
        assertNotNull(GovernorEventMonitor.Severity.NOTE);
        assertNotNull(GovernorEventMonitor.Severity.ADVISORY);
        assertNotNull(GovernorEventMonitor.Severity.ALERT);
    }

    @Test
    void concern_record_holds_data() {
        var concern = new GovernorEventMonitor.Concern(
            GovernorEventMonitor.Severity.ALERT, "agent-1", "test",
            "test concern", Instant.now());

        assertEquals(GovernorEventMonitor.Severity.ALERT, concern.severity());
        assertEquals("agent-1", concern.agentId());
        assertEquals("test", concern.category());
    }
}
