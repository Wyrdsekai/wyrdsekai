package org.wyrdsekai.core.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Generic circuit breaker with three states: CLOSED (normal), OPEN (failing, reject fast),
 * HALF_OPEN (testing recovery).
 *
 * <p>When the failure count exceeds {@code failureThreshold} in CLOSED state, the breaker
 * opens and stays open for {@code openDuration}. After that period, it transitions to
 * HALF_OPEN and allows {@code halfOpenPermits} test requests through. If they succeed,
 * the breaker closes. If any fail, it reopens.</p>
 *
 * <p>Thread-safe: uses volatile state + atomic counters. No external dependencies.</p>
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final Duration openDuration;
    private final int halfOpenPermits;

    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenAttempts = new AtomicInteger(0);
    private volatile Instant openedAt;

    // Metrics
    private final AtomicInteger totalRejected = new AtomicInteger(0);
    private final AtomicInteger totalSuccesses = new AtomicInteger(0);
    private final AtomicInteger totalFailures = new AtomicInteger(0);

    public CircuitBreaker(String name, int failureThreshold, Duration openDuration, int halfOpenPermits) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.halfOpenPermits = halfOpenPermits;
    }

    /**
     * Create a circuit breaker using values from {@link ResilienceConfig}.
     * Falls back to hardcoded defaults if no config is set (e.g., in tests).
     */
    public CircuitBreaker(String name) {
        this(name,
            ResilienceConfig.get().cbFailureThreshold(),
            Duration.ofSeconds(ResilienceConfig.get().cbOpenDurationSeconds()),
            ResilienceConfig.get().cbHalfOpenPermits());
    }

    /**
     * Execute an action through the circuit breaker. If the breaker is open and the fallback
     * is non-null, invoke the fallback instead. If the breaker is open and no fallback is
     * provided, throws {@link CircuitBreakerOpenException}.
     */
    public <T> T execute(Supplier<T> action, Supplier<T> fallback) {
        if (!tryAcquire()) {
            totalRejected.incrementAndGet();
            if (fallback != null) {
                log.debug("Circuit breaker '{}' OPEN — using fallback", name);
                return fallback.get();
            }
            throw new CircuitBreakerOpenException(name);
        }

        try {
            T result = action.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            if (fallback != null) {
                log.debug("Circuit breaker '{}' action failed — using fallback: {}", name, e.getMessage());
                return fallback.get();
            }
            throw e;
        }
    }

    /**
     * Execute an action without a fallback.
     */
    public <T> T execute(Supplier<T> action) {
        return execute(action, null);
    }

    /**
     * Async version of execute. Returns a CompletableFuture that completes with the action
     * result or the fallback result if the breaker is open or the action fails.
     */
    public <T> CompletableFuture<T> executeAsync(Supplier<CompletableFuture<T>> action,
                                                  Supplier<CompletableFuture<T>> fallback) {
        if (!tryAcquire()) {
            totalRejected.incrementAndGet();
            if (fallback != null) {
                log.debug("Circuit breaker '{}' OPEN — using async fallback", name);
                return fallback.get();
            }
            return CompletableFuture.failedFuture(new CircuitBreakerOpenException(name));
        }

        try {
            return action.get().whenComplete((result, ex) -> {
                if (ex != null) {
                    onFailure();
                } else {
                    onSuccess();
                }
            });
        } catch (Exception e) {
            onFailure();
            if (fallback != null) {
                return fallback.get();
            }
            return CompletableFuture.failedFuture(e);
        }
    }

    public State getState() {
        // Check for state transition from OPEN -> HALF_OPEN
        if (state == State.OPEN && openedAt != null) {
            if (Instant.now().isAfter(openedAt.plus(openDuration))) {
                transitionToHalfOpen();
            }
        }
        return state;
    }

    public String getName() {
        return name;
    }

    /** Manual reset to CLOSED state. */
    public void reset() {
        state = State.CLOSED;
        failureCount.set(0);
        halfOpenSuccessCount.set(0);
        halfOpenAttempts.set(0);
        openedAt = null;
        log.info("Circuit breaker '{}' manually reset to CLOSED", name);
    }

    // Metrics accessors
    public int getTotalRejected() { return totalRejected.get(); }
    public int getTotalSuccesses() { return totalSuccesses.get(); }
    public int getTotalFailures() { return totalFailures.get(); }
    public int getFailureCount() { return failureCount.get(); }

    // --- Internal ---

    private boolean tryAcquire() {
        return switch (getState()) {
            case CLOSED -> true;
            case OPEN -> false;
            case HALF_OPEN -> halfOpenAttempts.incrementAndGet() <= halfOpenPermits;
        };
    }

    private void onSuccess() {
        totalSuccesses.incrementAndGet();
        switch (state) {
            case HALF_OPEN -> {
                if (halfOpenSuccessCount.incrementAndGet() >= halfOpenPermits) {
                    transitionToClosed();
                }
            }
            case CLOSED -> failureCount.set(0); // reset consecutive failures on success
            default -> {}
        }
    }

    private void onFailure() {
        totalFailures.incrementAndGet();
        switch (state) {
            case CLOSED -> {
                if (failureCount.incrementAndGet() >= failureThreshold) {
                    transitionToOpen();
                }
            }
            case HALF_OPEN -> transitionToOpen(); // any failure in half-open reopens
            default -> {}
        }
    }

    private void transitionToOpen() {
        state = State.OPEN;
        openedAt = Instant.now();
        halfOpenSuccessCount.set(0);
        halfOpenAttempts.set(0);
        log.warn("Circuit breaker '{}' OPENED (failures: {})", name, failureCount.get());
    }

    private void transitionToHalfOpen() {
        state = State.HALF_OPEN;
        halfOpenSuccessCount.set(0);
        halfOpenAttempts.set(0);
        log.info("Circuit breaker '{}' -> HALF_OPEN (testing recovery)", name);
    }

    private void transitionToClosed() {
        state = State.CLOSED;
        failureCount.set(0);
        halfOpenSuccessCount.set(0);
        halfOpenAttempts.set(0);
        openedAt = null;
        log.info("Circuit breaker '{}' CLOSED (recovered)", name);
    }

    /** Exception thrown when the circuit breaker is open and no fallback is provided. */
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String breakerName) {
            super("Circuit breaker '" + breakerName + "' is OPEN");
        }
    }
}
