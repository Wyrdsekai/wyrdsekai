package org.wyrdsekai.server.ws;

/**
 * Per-session token bucket rate limiter.
 * Refills at a fixed rate. Thread-safe via synchronized.
 */
public final class SessionRateLimiter {
    private final int maxTokens;
    private final double refillPerMs;
    private double tokens;
    private long lastRefillTime;

    public SessionRateLimiter(int maxTokens, int refillPerSecond) {
        this.maxTokens = maxTokens;
        this.refillPerMs = refillPerSecond / 1000.0;
        this.tokens = maxTokens;
        this.lastRefillTime = System.currentTimeMillis();
    }

    public synchronized boolean tryConsume() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.currentTimeMillis();
        double elapsed = now - lastRefillTime;
        tokens = Math.min(maxTokens, tokens + elapsed * refillPerMs);
        lastRefillTime = now;
    }
}
