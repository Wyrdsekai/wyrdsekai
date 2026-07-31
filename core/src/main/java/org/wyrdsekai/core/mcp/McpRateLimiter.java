package org.wyrdsekai.core.mcp;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Token bucket rate limiter for MCP gateway (§86.2).
 *
 * Three scopes:
 * - Per-agent: 10 calls/min (default)
 * - Per-service: 60 calls/min (default)
 * - Per-zone: 200 calls/min (default)
 *
 * Uses a sliding window approach: count requests in the current minute.
 * When exceeded, returns narrative feedback for in-world experience.
 */
public class McpRateLimiter {

    /** Bucket key → (minute → count). */
    private final Map<String, WindowCounter> buckets = new ConcurrentHashMap<>();

    private final int defaultPerAgent;
    private final int defaultPerService;
    private final int defaultPerZone;

    public McpRateLimiter() {
        this(10, 60, 200);
    }

    public McpRateLimiter(int perAgent, int perService, int perZone) {
        this.defaultPerAgent = perAgent;
        this.defaultPerService = perService;
        this.defaultPerZone = perZone;
    }

    /**
     * Check if a request is allowed. Does NOT consume a token.
     *
     * @param agentId   Agent making the request
     * @param serviceId MCP service being called
     * @param zoneId    Zone the agent is in
     * @param overrides Per-service rate limit overrides
     * @return null if allowed, narrative string if rate-limited
     */
    public String check(String agentId, String serviceId, String zoneId,
                         Map<String, Integer> overrides) {
        long minute = currentMinute();

        // Check per-agent limit
        int agentLimit = defaultPerAgent;
        if (overrides != null && overrides.containsKey("per_agent")) {
            agentLimit = overrides.get("per_agent");
        }
        String agentKey = "agent:" + agentId;
        if (count(agentKey, minute) >= agentLimit) {
            return "You must wait. The harbor master asks for patience.";
        }

        // Check per-service limit
        int serviceLimit = defaultPerService;
        if (overrides != null && overrides.containsKey("per_service")) {
            serviceLimit = overrides.get("per_service");
        }
        String serviceKey = "service:" + serviceId;
        if (count(serviceKey, minute) >= serviceLimit) {
            return "The docks are busy. Your request has been queued.";
        }

        // Check per-zone limit
        int zoneLimit = defaultPerZone;
        if (overrides != null && overrides.containsKey("per_zone")) {
            zoneLimit = overrides.get("per_zone");
        }
        String zoneKey = "zone:" + zoneId;
        if (count(zoneKey, minute) >= zoneLimit) {
            return "The harbor master has flagged unusual traffic. Requests are being throttled.";
        }

        return null; // allowed
    }

    /**
     * Record a request (consume tokens from all three buckets).
     */
    public void record(String agentId, String serviceId, String zoneId) {
        long minute = currentMinute();
        increment("agent:" + agentId, minute);
        increment("service:" + serviceId, minute);
        increment("zone:" + zoneId, minute);
    }

    /** Get remaining calls for an agent in the current window. */
    public int remainingForAgent(String agentId) {
        return Math.max(0, defaultPerAgent - count("agent:" + agentId, currentMinute()));
    }

    /** Get remaining calls for a service in the current window. */
    public int remainingForService(String serviceId) {
        return Math.max(0, defaultPerService - count("service:" + serviceId, currentMinute()));
    }

    private int count(String key, long minute) {
        var counter = buckets.get(key);
        if (counter == null) return 0;
        return counter.countForMinute(minute);
    }

    private void increment(String key, long minute) {
        buckets.computeIfAbsent(key, _ -> new WindowCounter())
            .increment(minute);
    }

    private static long currentMinute() {
        return Instant.now().getEpochSecond() / 60;
    }

    /** Simple sliding window counter. Only tracks current minute. */
    static class WindowCounter {
        private volatile long lastMinute = -1;
        private final AtomicInteger count = new AtomicInteger(0);

        int countForMinute(long minute) {
            if (minute != lastMinute) return 0;
            return count.get();
        }

        void increment(long minute) {
            if (minute != lastMinute) {
                synchronized (this) {
                    if (minute != lastMinute) {
                        lastMinute = minute;
                        count.set(0);
                    }
                }
            }
            count.incrementAndGet();
        }
    }
}
