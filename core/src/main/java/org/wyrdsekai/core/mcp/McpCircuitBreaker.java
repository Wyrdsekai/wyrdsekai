package org.wyrdsekai.core.mcp;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-service circuit breaker for MCP gateway (§86.2).
 *
 * States:
 * - CLOSED: normal operation, requests flow through
 * - OPEN: too many failures, requests rejected with narrative
 * - HALF_OPEN: after recovery window, one test request allowed
 *
 * Default: 5 failures → open for 60 seconds.
 * Narrative: "The route to [service] is closed. The harbor master reports storms ahead."
 */
public class McpCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** Per-service breaker state. */
    static class BreakerState {
        final AtomicInteger failureCount = new AtomicInteger(0);
        volatile State state = State.CLOSED;
        volatile long openedAt = 0;
    }

    private final Map<String, BreakerState> breakers = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final long recoveryWindowSeconds;

    public McpCircuitBreaker() {
        this(5, 60);
    }

    public McpCircuitBreaker(int failureThreshold, long recoveryWindowSeconds) {
        this.failureThreshold = failureThreshold;
        this.recoveryWindowSeconds = recoveryWindowSeconds;
    }

    /**
     * Check if a service is available (circuit not open).
     *
     * @param serviceId MCP service identifier
     * @return null if available, narrative string if circuit is open
     */
    public String check(String serviceId) {
        var breaker = breakers.get(serviceId);
        if (breaker == null) return null; // No failures, circuit closed

        switch (breaker.state) {
            case CLOSED -> { return null; }
            case OPEN -> {
                long elapsed = Instant.now().getEpochSecond() - breaker.openedAt;
                if (elapsed >= recoveryWindowSeconds) {
                    breaker.state = State.HALF_OPEN;
                    return null; // Allow one test request
                }
                return "The route to " + serviceId + " is closed. "
                    + "The harbor master reports storms ahead.";
            }
            case HALF_OPEN -> {
                return null; // Allow test request
            }
        }
        return null;
    }

    /**
     * Record a successful call (resets failure count).
     */
    public void recordSuccess(String serviceId) {
        var breaker = breakers.get(serviceId);
        if (breaker != null) {
            breaker.failureCount.set(0);
            breaker.state = State.CLOSED;
        }
    }

    /**
     * Record a failed call. May trip the circuit.
     */
    public void recordFailure(String serviceId) {
        var breaker = breakers.computeIfAbsent(serviceId, _ -> new BreakerState());
        int failures = breaker.failureCount.incrementAndGet();

        if (failures >= failureThreshold) {
            breaker.state = State.OPEN;
            breaker.openedAt = Instant.now().getEpochSecond();
        }
    }

    /** Get the current state for a service. */
    public State getState(String serviceId) {
        var breaker = breakers.get(serviceId);
        if (breaker == null) return State.CLOSED;

        // Check for automatic recovery
        if (breaker.state == State.OPEN) {
            long elapsed = Instant.now().getEpochSecond() - breaker.openedAt;
            if (elapsed >= recoveryWindowSeconds) {
                breaker.state = State.HALF_OPEN;
            }
        }
        return breaker.state;
    }

    /** Reset all breakers. */
    public void reset() {
        breakers.clear();
    }

    /** Reset a specific service's breaker. */
    public void reset(String serviceId) {
        breakers.remove(serviceId);
    }
}
