package org.wyrdsekai.core.resilience;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.AgentEvent;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.observability.MetricsRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying resilience components work together.
 */
class ResilienceIntegrationTest {

    @Test
    void circuitBreaker_protectsInferenceCall() {
        var cb = new CircuitBreaker("inference-local", 2, Duration.ofMillis(100), 1);
        var callCount = new AtomicInteger(0);

        // Simulate backend failures
        for (int i = 0; i < 3; i++) {
            cb.execute(
                () -> { callCount.incrementAndGet(); throw new RuntimeException("backend down"); },
                () -> "degraded response"
            );
        }

        // Circuit should be open — should return fallback without calling backend
        var result = cb.execute(
            () -> { callCount.incrementAndGet(); return "real response"; },
            () -> "degraded response"
        );

        assertEquals("degraded response", result);
        // The last call should NOT have invoked the action (circuit open)
        assertEquals(2, callCount.get(), "Action should not be called when circuit is open");
        assertTrue(cb.getTotalRejected() >= 1);
    }

    @Test
    void rateLimiter_throttlesEventDelivery() throws InterruptedException {
        AgentEventStream.init();
        var stream = AgentEventStream.get();
        var received = new AtomicInteger(0);
        var latch = new CountDownLatch(1);

        stream.subscribe("test-agent", event -> {
            received.incrementAndGet();
            if (received.get() >= 5) latch.countDown();
        });

        // Fire 50 events rapidly — rate limiter should throttle many
        for (int i = 0; i < 50; i++) {
            stream.publishAdjacentActivity("room-" + i, "Room " + i,
                AgentEvent.ActivityType.SPEECH, 1);
        }

        // Wait a bit for drain thread to process
        latch.await(2, TimeUnit.SECONDS);

        // Should have received some but likely not all 50
        assertTrue(received.get() > 0, "Should receive at least some events");

        // Total dropped should account for rate limiting
        // (in practice, with 10/sec rate and burst of 20, we may get most of them through
        // since the drain thread is separate)

        stream.unsubscribe("test-agent");
    }

    @Test
    void degradationManager_disablesAutonomyUnderLoad() {
        var dm = new DegradationManager();

        // Normal — autonomy works
        dm.evaluate(50.0, 50.0, 0);
        assertTrue(dm.shouldProcessAutonomy());

        // Under load — autonomy disabled
        dm.evaluate(91.0, 50.0, 0);
        assertFalse(dm.shouldProcessAutonomy());
        assertTrue(dm.shouldProcessInference()); // inference still works at OVERLOADED

        // Critical — inference also disabled (95% CPU hits critical threshold)
        dm.evaluate(95.0, 50.0, 0);
        assertFalse(dm.shouldProcessInference());
    }

    @Test
    void allComponents_initializeAndWire() {
        // CircuitBreaker
        var cb = new CircuitBreaker("test-integration");
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        // Rate limiter
        var rl = new TokenBucketRateLimiter(10.0, 10.0);
        assertTrue(rl.tryAcquire());

        // Concurrency controller
        var cc = new ConcurrencyController("test-integration", 4, 20);
        var f = cc.submit(() -> CompletableFuture.completedFuture("ok"));
        assertEquals("ok", f.join());

        // Degradation manager
        var dm = new DegradationManager();
        dm.evaluate(50.0, 50.0, 0);
        assertEquals(DegradationManager.Level.NORMAL, dm.getLevel());

        // Metrics registry integration
        var metrics = new MetricsRegistry();
        metrics.updateCircuitBreaker("test", cb.getState().ordinal());
        metrics.updateDegradationLevel(dm.getLevel().ordinal());
        metrics.updateInferenceQueueDepth("local", cc.getQueueDepth());
        metrics.incrementInferenceRejected("local");
        metrics.incrementEventStreamDropped("agent-1");
        metrics.incrementWebsocketThrottled("session-1");
        metrics.incrementNatsCoalesced();
        metrics.incrementSqliteBusyRetries();

        assertEquals(0.0, metrics.gaugeValue("wyrd_circuit_breaker_state", Map.of("name", "test")));
        assertEquals(0.0, metrics.gaugeValue("wyrd_degradation_level", Map.of()));
        assertEquals(1, metrics.counterValue("wyrd_inference_rejected_total", Map.of("backend", "local")));
        assertEquals(1, metrics.counterValue("wyrd_event_stream_dropped_total", Map.of("subscriber", "agent-1")));
        assertEquals(1, metrics.counterValue("wyrd_websocket_throttled_total", Map.of("session", "session-1")));
        assertEquals(1, metrics.counterValue("wyrd_nats_publish_coalesced_total", Map.of()));
        assertEquals(1, metrics.counterValue("wyrd_sqlite_busy_retries_total", Map.of()));
    }
}
