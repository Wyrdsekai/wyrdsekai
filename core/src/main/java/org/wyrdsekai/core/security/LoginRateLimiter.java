package org.wyrdsekai.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * #12 (2026-07-19 OSS hardening) — brute-force throttle for the password auth
 * surfaces (SSH :7022, NATS login). Before this there was NO limit: an attacker
 * could try passwords as fast as bcrypt allows against any account, on either
 * surface.
 *
 * <p>Keys are opaque strings the caller chooses — typically {@code "ip:<addr>"}
 * and {@code "acct:<username>"} — so a surface can throttle by source address
 * AND by targeted account independently. Each key accumulates failures within a
 * sliding window; on reaching {@link Policy#maxFailures} it is locked out for
 * {@link Policy#lockoutMs}. A success clears the key.</p>
 *
 * <p>State is in-memory per process — adequate for a self-hosted household node.
 * Methods are lightweight and synchronised; auth is low-frequency.</p>
 */
public final class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    /**
     * @param maxFailures failures within {@code windowMs} before lockout
     * @param windowMs    sliding failure-count window
     * @param lockoutMs   how long a key stays locked once tripped
     */
    public record Policy(int maxFailures, long windowMs, long lockoutMs) {
        public static final Policy DEFAULT = new Policy(5, 60_000L, 300_000L);
    }

    private static final class State {
        int failures;
        long windowStart;
        long lockedUntil;
    }

    private final Policy policy;
    private final ConcurrentHashMap<String, State> byKey = new ConcurrentHashMap<>();

    public LoginRateLimiter() {
        this(Policy.DEFAULT);
    }

    public LoginRateLimiter(Policy policy) {
        this.policy = policy == null ? Policy.DEFAULT : policy;
    }

    /** @return true if {@code key} is currently locked out. */
    public synchronized boolean isLocked(String key) {
        if (key == null) return false;
        var s = byKey.get(key);
        if (s == null) return false;
        return s.lockedUntil > System.currentTimeMillis();
    }

    /** @return true if ANY of the keys is currently locked out. */
    public boolean anyLocked(String... keys) {
        for (var k : keys) {
            if (isLocked(k)) return true;
        }
        return false;
    }

    /**
     * Milliseconds until the longest-held lockout among {@code keys} expires,
     * or 0 when nothing is locked. Surfaces exist so a throttled surface can
     * TELL the user how long to wait: a lockout that merely drops the
     * connection is indistinguishable from a broken server, which is exactly
     * how a mistyped password once got mistaken for "SSH renders nothing"
     * (2026-07-25).
     */
    public synchronized long lockRemainingMs(String... keys) {
        long now = System.currentTimeMillis();
        long worst = 0;
        for (var k : keys) {
            if (k == null) continue;
            var s = byKey.get(k);
            if (s != null && s.lockedUntil > now) {
                worst = Math.max(worst, s.lockedUntil - now);
            }
        }
        return worst;
    }

    /** Record a failed attempt against {@code key}; may trip a lockout. */
    public synchronized void recordFailure(String key) {
        if (key == null) return;
        long now = System.currentTimeMillis();
        var s = byKey.computeIfAbsent(key, k -> new State());
        if (s.windowStart == 0 || now - s.windowStart > policy.windowMs()) {
            s.failures = 0;
            s.windowStart = now;
        }
        s.failures++;
        if (s.failures >= policy.maxFailures()) {
            s.lockedUntil = now + policy.lockoutMs();
            s.failures = 0;
            s.windowStart = now;
            log.warn("Login rate-limit tripped for key '{}' — locked out for {}ms",
                key, policy.lockoutMs());
        }
    }

    public void recordFailureAll(String... keys) {
        for (var k : keys) recordFailure(k);
    }

    /** Clear a key after a successful auth. */
    public synchronized void recordSuccess(String key) {
        if (key != null) byKey.remove(key);
    }

    public void recordSuccessAll(String... keys) {
        for (var k : keys) recordSuccess(k);
    }
}
