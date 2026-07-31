package org.wyrdsekai.core.resilience;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    void permitsAvailableInitially() {
        var limiter = new TokenBucketRateLimiter(10.0, 10.0);
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.getAvailableTokens() > 8.0); // consumed 1
    }

    @Test
    void depletesAfterBurst() {
        var limiter = new TokenBucketRateLimiter(10.0, 5.0);

        int acquired = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire()) acquired++;
        }

        assertEquals(5, acquired); // bucket capacity is 5
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        var limiter = new TokenBucketRateLimiter(100.0, 5.0); // 100/sec = fast refill

        // Deplete
        for (int i = 0; i < 5; i++) limiter.tryAcquire();
        assertFalse(limiter.tryAcquire());

        // Wait for refill (100/sec means 1 token every 10ms)
        Thread.sleep(100); // should get ~10 tokens, capped at 5

        assertTrue(limiter.tryAcquire());
    }

    @Test
    void concurrentAccessSafe() throws InterruptedException {
        var limiter = new TokenBucketRateLimiter(1000.0, 100.0);
        int threads = 10;
        var latch = new CountDownLatch(threads);
        var acquired = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                for (int i = 0; i < 20; i++) {
                    if (limiter.tryAcquire()) acquired.incrementAndGet();
                }
                latch.countDown();
            }).start();
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        // Should have acquired exactly 100 (bucket capacity) + whatever refilled
        assertTrue(acquired.get() >= 100, "Should acquire at least bucket capacity");
        assertTrue(acquired.get() <= 200, "Should not exceed total attempts");
    }

    @Test
    void customRate() {
        var limiter = new TokenBucketRateLimiter(1.0, 1.0); // 1 per second
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire()); // only 1 token available

        assertEquals(1.0, limiter.getMaxTokens());
        assertEquals(1.0, limiter.getTokensPerSecond());
    }

    @Test
    void multiPermitAcquire() {
        var limiter = new TokenBucketRateLimiter(100.0, 10.0);
        assertTrue(limiter.tryAcquire(5));
        assertTrue(limiter.tryAcquire(5));
        assertFalse(limiter.tryAcquire(1)); // depleted
    }
}
