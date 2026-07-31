package org.wyrdsekai.core.resilience;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    @Test
    void closedState_passesThrough() {
        var cb = new CircuitBreaker("test", 3, Duration.ofSeconds(30), 1);
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        var result = cb.execute(() -> "hello", () -> "fallback");
        assertEquals("hello", result);
        assertEquals(1, cb.getTotalSuccesses());
    }

    @Test
    void opensAfterNFailures() {
        var cb = new CircuitBreaker("test", 3, Duration.ofSeconds(30), 1);

        for (int i = 0; i < 3; i++) {
            try {
                cb.execute(() -> { throw new RuntimeException("fail"); });
            } catch (RuntimeException ignored) {}
        }

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertEquals(3, cb.getTotalFailures());
    }

    @Test
    void openState_rejectsFast() {
        var cb = new CircuitBreaker("test", 1, Duration.ofSeconds(60), 1);

        // Trigger open
        try {
            cb.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {}

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Should use fallback
        var result = cb.execute(() -> "action", () -> "fallback");
        assertEquals("fallback", result);
        assertEquals(1, cb.getTotalRejected());
    }

    @Test
    void openState_throwsWhenNoFallback() {
        var cb = new CircuitBreaker("test", 1, Duration.ofSeconds(60), 1);

        try {
            cb.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {}

        assertThrows(CircuitBreaker.CircuitBreakerOpenException.class,
            () -> cb.execute(() -> "action"));
    }

    @Test
    void halfOpen_afterTimeout() {
        var cb = new CircuitBreaker("test", 1, Duration.ofMillis(50), 1);

        // Trigger open
        try {
            cb.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {}
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Wait for open duration to expire
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());
    }

    @Test
    void successfulHalfOpen_closes() {
        var cb = new CircuitBreaker("test", 1, Duration.ofMillis(50), 1);

        // Trigger open
        try {
            cb.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {}

        // Wait for half-open
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());

        // Successful call should close
        var result = cb.execute(() -> "recovered", () -> "fallback");
        assertEquals("recovered", result);
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void failedHalfOpen_reopens() {
        var cb = new CircuitBreaker("test", 1, Duration.ofMillis(50), 1);

        // Trigger open
        try {
            cb.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {}

        // Wait for half-open
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());

        // Failed call should reopen
        try {
            cb.execute(() -> { throw new RuntimeException("still failing"); });
        } catch (RuntimeException ignored) {}
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    void manualReset() {
        var cb = new CircuitBreaker("test", 1, Duration.ofSeconds(60), 1);

        // Trigger open
        try {
            cb.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {}
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        cb.reset();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertEquals(0, cb.getFailureCount());

        // Should work again
        var result = cb.execute(() -> "working");
        assertEquals("working", result);
    }

    @Test
    void concurrentSafety() throws InterruptedException {
        var cb = new CircuitBreaker("test", 100, Duration.ofSeconds(30), 1);
        int threads = 10;
        int opsPerThread = 100;
        var latch = new CountDownLatch(threads);
        var errors = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    try {
                        cb.execute(() -> "ok");
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
                latch.countDown();
            }).start();
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(threads * opsPerThread, cb.getTotalSuccesses());
        assertEquals(0, errors.get());
    }

    @Test
    void asyncVersion_works() {
        var cb = new CircuitBreaker("test", 3, Duration.ofSeconds(30), 1);

        var future = cb.executeAsync(
            () -> CompletableFuture.completedFuture("async-result"),
            () -> CompletableFuture.completedFuture("async-fallback")
        );

        assertEquals("async-result", future.join());
        assertEquals(1, cb.getTotalSuccesses());
    }

    @Test
    void asyncVersion_fallbackOnOpen() {
        var cb = new CircuitBreaker("test", 1, Duration.ofSeconds(60), 1);

        // Trigger open with sync call
        try {
            cb.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {}

        var future = cb.executeAsync(
            () -> CompletableFuture.completedFuture("action"),
            () -> CompletableFuture.completedFuture("async-fallback")
        );

        assertEquals("async-fallback", future.join());
    }

    @Test
    void fallbackInvokedOnActionFailure() {
        var cb = new CircuitBreaker("test", 5, Duration.ofSeconds(30), 1);

        var result = cb.execute(
            () -> { throw new RuntimeException("action failed"); },
            () -> "fallback-value"
        );

        assertEquals("fallback-value", result);
        assertEquals(1, cb.getTotalFailures());
    }
}
