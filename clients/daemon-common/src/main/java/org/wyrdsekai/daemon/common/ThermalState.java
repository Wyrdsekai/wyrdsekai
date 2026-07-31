package org.wyrdsekai.daemon.common;

/**
 * Thermal and power state for inference throttling.
 * Used by both Android (battery + thermal API) and Desktop (CPU temp probes).
 */
public record ThermalState(
    ThrottleLevel level,
    boolean isCharging,
    int batteryPercent,
    float temperatureCelsius
) {
    /** Throttle levels with increasing severity. */
    public enum ThrottleLevel {
        /** Full speed — accept all requests. */
        NORMAL,
        /** Reduce threads, reject DEFERRED priority. */
        WARM,
        /** Minimal threads, reject AGENT_INITIATED and DEFERRED. */
        HOT,
        /** Pause inference entirely, drain queue, announce 0 slots. */
        CRITICAL
    }

    /**
     * Evaluate throttle level from battery and temperature readings.
     * Shared logic used by both Android and Desktop implementations.
     */
    public static ThermalState evaluate(boolean isCharging, int batteryPercent,
                                         float temperatureCelsius) {
        ThrottleLevel level;
        if (temperatureCelsius > 45) {
            level = ThrottleLevel.CRITICAL;
        } else if (temperatureCelsius > 40) {
            level = ThrottleLevel.HOT;
        } else if (temperatureCelsius > 38) {
            level = ThrottleLevel.WARM;
        } else if (!isCharging && batteryPercent < 20) {
            level = ThrottleLevel.CRITICAL;
        } else if (!isCharging && batteryPercent < 50) {
            level = ThrottleLevel.HOT;
        } else {
            level = ThrottleLevel.NORMAL;
        }
        return new ThermalState(level, isCharging, batteryPercent, temperatureCelsius);
    }

    /** Number of inference threads appropriate for this throttle level. */
    public int recommendedThreads(int maxThreads) {
        return switch (level) {
            case NORMAL -> maxThreads;
            case WARM -> Math.max(1, maxThreads / 2);
            case HOT -> 1;
            case CRITICAL -> 0;
        };
    }

    /** Whether the daemon should accept new inference requests. */
    public boolean acceptsRequests() {
        return level != ThrottleLevel.CRITICAL;
    }

    /** Available slots to advertise in gossip (0 if throttled to CRITICAL). */
    public int effectiveSlots(int configuredSlots) {
        return switch (level) {
            case NORMAL, WARM -> configuredSlots;
            case HOT -> Math.min(1, configuredSlots);
            case CRITICAL -> 0;
        };
    }
}
