package org.wyrdsekai.core.resilience;

/**
 * Simple token bucket rate limiter. No external dependencies.
 *
 * <p>Tokens refill at a fixed rate. Each {@link #tryAcquire()} consumes one token.
 * When no tokens are available, the call returns false (non-blocking).
 * Thread-safe via {@code synchronized}.</p>
 */
public class TokenBucketRateLimiter {

    private final double tokensPerSecond;
    private final double maxTokens;
    private double availableTokens;
    private long lastRefillTimestamp;

    /**
     * @param tokensPerSecond refill rate
     * @param maxTokens       bucket capacity (also initial token count)
     */
    public TokenBucketRateLimiter(double tokensPerSecond, double maxTokens) {
        this.tokensPerSecond = tokensPerSecond;
        this.maxTokens = maxTokens;
        this.availableTokens = maxTokens;
        this.lastRefillTimestamp = System.nanoTime();
    }

    /**
     * Try to acquire a single permit.
     *
     * @return true if a token was available
     */
    public synchronized boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Try to acquire the given number of permits.
     *
     * @param permits number of tokens to consume
     * @return true if enough tokens were available
     */
    public synchronized boolean tryAcquire(int permits) {
        refill();
        if (availableTokens >= permits) {
            availableTokens -= permits;
            return true;
        }
        return false;
    }

    /** Current available tokens (for metrics/testing). */
    public synchronized double getAvailableTokens() {
        refill();
        return availableTokens;
    }

    /** Maximum bucket capacity. */
    public double getMaxTokens() {
        return maxTokens;
    }

    /** Configured rate. */
    public double getTokensPerSecond() {
        return tokensPerSecond;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillTimestamp) / 1_000_000_000.0;
        availableTokens = Math.min(maxTokens, availableTokens + elapsedSeconds * tokensPerSecond);
        lastRefillTimestamp = now;
    }
}
