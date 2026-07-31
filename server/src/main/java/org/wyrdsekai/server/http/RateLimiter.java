package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple sliding-window rate limiter for Javalin.
 * Tracks request counts per IP per path prefix over a configurable window.
 *
 * Install as a {@code before} handler:
 *   app.before(rateLimiter::handle);
 *
 * Returns 429 Too Many Requests when a client exceeds the configured limit.
 * Health/ready/metrics endpoints are never rate-limited.
 */
public final class RateLimiter implements Handler {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** Limits per path prefix. Key = prefix, value = max requests per window. */
    private final Map<String, Integer> limits;
    private final long windowMs;

    /** IP → (prefix → counter). Cleared every window. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>> counters =
        new ConcurrentHashMap<>();

    /**
     * @param limits   Map of path prefix → max requests per window (e.g. "/api/auth" → 20)
     * @param windowMs Window duration in milliseconds (e.g. 60_000 for 1 minute)
     */
    public RateLimiter(Map<String, Integer> limits, long windowMs) {
        this.limits = Map.copyOf(limits);
        this.windowMs = windowMs;

        // Background cleanup — reset all counters every window
        var cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "rate-limiter-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(counters::clear, windowMs, windowMs, TimeUnit.MILLISECONDS);
    }

    /** Default production limits: auth=20/min, soul=60/min, inference=30/min, ws=10/min. */
    public static RateLimiter defaultLimits() {
        return new RateLimiter(Map.of(
            "/api/auth", 20,
            "/api/soul", 60,
            "/api/inference", 30,
            "/ws", 10
        ), 60_000);
    }

    @Override
    public void handle(Context ctx) {
        var path = ctx.path();

        // Never rate-limit health/ready/metrics
        if (path.equals("/health") || path.equals("/ready") || path.equals("/metrics")) {
            return;
        }

        var ip = ctx.ip();
        var matchedPrefix = matchPrefix(path);
        if (matchedPrefix == null) return; // No limit configured for this path

        var limit = limits.get(matchedPrefix);
        var ipCounters = counters.computeIfAbsent(ip, k -> new ConcurrentHashMap<>());
        var counter = ipCounters.computeIfAbsent(matchedPrefix, k -> new AtomicInteger(0));

        var count = counter.incrementAndGet();
        if (count > limit) {
            log.warn("Rate limit exceeded: ip={}, path={}, count={}/{}", ip, matchedPrefix, count, limit);
            ctx.status(429)
                .header("Retry-After", String.valueOf(windowMs / 1000))
                .json(Map.of("error", "Too many requests. Try again in " + (windowMs / 1000) + "s."));
            // Prevent further processing
            throw new HttpResponseException(429, "Too Many Requests");
        }
    }

    private String matchPrefix(String path) {
        String best = null;
        for (var prefix : limits.keySet()) {
            if (path.startsWith(prefix)) {
                if (best == null || prefix.length() > best.length()) {
                    best = prefix;
                }
            }
        }
        return best;
    }
}
