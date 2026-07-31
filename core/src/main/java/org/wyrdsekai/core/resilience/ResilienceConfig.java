package org.wyrdsekai.core.resilience;

import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized resilience configuration loaded from HOCON ({@code wyrdsekai.resilience.*}).
 *
 * <p>Singleton pattern: call {@link #set(ResilienceConfig)} once at startup (from Main.java),
 * then components call {@link #get()} to read values. If never set, {@link #get()} returns
 * {@link #defaults()} so tests work without HOCON config.</p>
 */
public record ResilienceConfig(
    // Inference concurrency
    int inferenceMaxConcurrent,
    int inferenceMaxQueue,
    int inferenceTimeoutSeconds,
    // WebSocket session throttling
    double wsMaxMsgPerSecond,
    int wsBurstCapacity,
    // Agent event stream
    double eventsPerSecondPerAgent,
    int eventQueueCapacity,
    // Circuit breaker
    int cbFailureThreshold,
    int cbOpenDurationSeconds,
    int cbHalfOpenPermits,
    // Write batcher
    int writeBatchMaxSize,
    int writeBatchMaxDelayMs,
    // NATS coalescing
    int natsCoalesceWindowMs,
    int natsMaxPublishRate,
    // Degradation thresholds
    int degradationHighLoadCpuPct,
    int degradationOverloadedCpuPct,
    int degradationCriticalCpuPct,
    int degradationHighLoadHeapPct,
    int degradationOverloadedHeapPct,
    int degradationCriticalHeapPct,
    // Room limits
    int maxSubscribersPerRoom,
    int roomIdleEvictionHours,
    int maxSnapshotRateSeconds
) {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    // --- Singleton ---

    private static volatile ResilienceConfig instance;

    /** Set the global instance. Called once at startup from Main.java. */
    public static void set(ResilienceConfig config) {
        instance = config;
    }

    /** Get the global instance. Returns {@link #defaults()} if never set. */
    public static ResilienceConfig get() {
        var rc = instance;
        return rc != null ? rc : defaults();
    }

    /** Reset singleton to null (for test isolation). */
    public static void reset() {
        instance = null;
    }

    /** Default values matching application.conf defaults. */
    public static ResilienceConfig defaults() {
        return new ResilienceConfig(
            4, 20, 60,        // inference
            20.0, 30,         // websocket
            10.0, 100,        // event stream
            5, 30, 1,         // circuit breaker
            100, 50,          // write batcher
            500, 100,         // nats
            80, 90, 95,       // degradation cpu
            80, 90, 95,       // degradation heap
            1000, 24, 30      // rooms
        );
    }

    /**
     * Load from Typesafe Config (HOCON). Expects the full application config
     * (i.e., contains {@code wyrdsekai.resilience.*}).
     *
     * @param config the root application config
     * @return parsed ResilienceConfig
     */
    public static ResilienceConfig fromConfig(Config config) {
        var r = config.getConfig("wyrdsekai.resilience");
        return new ResilienceConfig(
            r.getInt("inference.max-concurrent-per-backend"),
            r.getInt("inference.max-queue-depth"),
            r.getInt("inference.request-timeout-seconds"),
            r.getDouble("websocket.max-messages-per-second"),
            r.getInt("websocket.burst-capacity"),
            r.getDouble("event-stream.events-per-second-per-agent"),
            r.getInt("event-stream.queue-capacity-per-agent"),
            r.getInt("circuit-breaker.failure-threshold"),
            r.getInt("circuit-breaker.open-duration-seconds"),
            r.getInt("circuit-breaker.half-open-permits"),
            r.getInt("write-batcher.max-batch-size"),
            r.getInt("write-batcher.max-delay-ms"),
            r.getInt("nats.coalesce-window-ms"),
            r.getInt("nats.max-publish-rate"),
            r.getInt("degradation.high-load-cpu-pct"),
            r.getInt("degradation.overloaded-cpu-pct"),
            r.getInt("degradation.critical-cpu-pct"),
            r.getInt("degradation.high-load-heap-pct"),
            r.getInt("degradation.overloaded-heap-pct"),
            r.getInt("degradation.critical-heap-pct"),
            r.getInt("rooms.max-subscribers-per-room"),
            r.getInt("rooms.idle-eviction-hours"),
            r.getInt("rooms.max-snapshot-rate-seconds")
        );
    }

    @Override
    public String toString() {
        return "ResilienceConfig{" +
            "inference=" + inferenceMaxConcurrent + "/" + inferenceMaxQueue + "/" + inferenceTimeoutSeconds + "s" +
            ", ws=" + wsMaxMsgPerSecond + "/s burst=" + wsBurstCapacity +
            ", events=" + eventsPerSecondPerAgent + "/s queue=" + eventQueueCapacity +
            ", cb=" + cbFailureThreshold + "/" + cbOpenDurationSeconds + "s/" + cbHalfOpenPermits +
            ", writeBatch=" + writeBatchMaxSize + "/" + writeBatchMaxDelayMs + "ms" +
            ", nats=" + natsCoalesceWindowMs + "ms/" + natsMaxPublishRate +
            ", degradation=cpu(" + degradationHighLoadCpuPct + "/" + degradationOverloadedCpuPct + "/" + degradationCriticalCpuPct + ")" +
            " heap(" + degradationHighLoadHeapPct + "/" + degradationOverloadedHeapPct + "/" + degradationCriticalHeapPct + ")" +
            ", rooms=" + maxSubscribersPerRoom + "/" + roomIdleEvictionHours + "h/" + maxSnapshotRateSeconds + "s" +
            '}';
    }
}
