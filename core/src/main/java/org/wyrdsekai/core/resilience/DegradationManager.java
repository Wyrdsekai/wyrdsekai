package org.wyrdsekai.core.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Tracks system pressure and determines what should be disabled.
 *
 * <p>Degradation levels form a ladder:
 * <ul>
 *   <li>NORMAL — everything works</li>
 *   <li>HIGH_LOAD — disable ambient notifications, slow snapshots</li>
 *   <li>OVERLOADED — disable autonomy, agents only respond when spoken to</li>
 *   <li>CRITICAL — disable agent inference entirely, serve room state only</li>
 *   <li>EMERGENCY — reject new connections, serve only existing sessions</li>
 * </ul>
 *
 * <p>Called periodically by EngineRoomService. Singleton pattern for global access.</p>
 */
public class DegradationManager {

    private static final Logger log = LoggerFactory.getLogger(DegradationManager.class);

    public enum Level {
        NORMAL, HIGH_LOAD, OVERLOADED, CRITICAL, EMERGENCY
    }

    private volatile Level level = Level.NORMAL;
    private final List<Consumer<Level>> listeners = new CopyOnWriteArrayList<>();

    // Configurable thresholds (read from ResilienceConfig)
    private final int highLoadCpu;
    private final int overloadedCpu;
    private final int criticalCpu;
    private final int highLoadHeap;
    private final int overloadedHeap;
    private final int criticalHeap;

    // Singleton
    private static volatile DegradationManager instance;

    public DegradationManager() {
        var rc = ResilienceConfig.get();
        this.highLoadCpu = rc.degradationHighLoadCpuPct();
        this.overloadedCpu = rc.degradationOverloadedCpuPct();
        this.criticalCpu = rc.degradationCriticalCpuPct();
        this.highLoadHeap = rc.degradationHighLoadHeapPct();
        this.overloadedHeap = rc.degradationOverloadedHeapPct();
        this.criticalHeap = rc.degradationCriticalHeapPct();
    }

    /** Initialize the global instance. */
    public static void init() {
        instance = new DegradationManager();
    }

    /** Get the global instance (null if not initialized). */
    public static DegradationManager get() {
        return instance;
    }

    /**
     * Evaluate system pressure and update degradation level.
     * Called by EngineRoomService periodically.
     *
     * @param cpuPct              CPU usage percentage (0-100)
     * @param heapPct             heap usage percentage (0-100)
     * @param inferenceQueueDepth current inference queue depth
     */
    public void evaluate(double cpuPct, double heapPct, int inferenceQueueDepth) {
        Level newLevel;

        // Use per-metric thresholds instead of max(cpu, heap)
        // CPU and heap are evaluated independently against their configured thresholds.
        // The highest severity from either metric wins.
        // Inference queue depth uses proportional thresholds relative to configured max queue.
        int cpuLevel = levelFromPct(cpuPct, highLoadCpu, overloadedCpu, criticalCpu);
        int heapLevel = levelFromPct(heapPct, highLoadHeap, overloadedHeap, criticalHeap);
        int queueLevel = inferenceQueueDepth > 100 ? 4
            : inferenceQueueDepth > 50 ? 3
            : inferenceQueueDepth > 30 ? 2
            : inferenceQueueDepth > 15 ? 1 : 0;

        int maxLevel = Math.max(Math.max(cpuLevel, heapLevel), queueLevel);
        newLevel = switch (maxLevel) {
            case 4 -> Level.EMERGENCY;
            case 3 -> Level.CRITICAL;
            case 2 -> Level.OVERLOADED;
            case 1 -> Level.HIGH_LOAD;
            default -> Level.NORMAL;
        };

        if (newLevel != level) {
            var previous = level;
            level = newLevel;
            log.warn("Degradation level changed: {} -> {} (cpu={}%, heap={}%, inferQ={})",
                previous, newLevel, String.format("%.1f", cpuPct),
                String.format("%.1f", heapPct), inferenceQueueDepth);
            notifyListeners(newLevel);
        }
    }

    public Level getLevel() {
        return level;
    }

    /** Whether autonomous agent behavior should be processed. */
    public boolean shouldProcessAutonomy() {
        return level.ordinal() < Level.OVERLOADED.ordinal();
    }

    /** Whether inference requests should be accepted. */
    public boolean shouldProcessInference() {
        return level.ordinal() < Level.CRITICAL.ordinal();
    }

    /** Whether new connections should be accepted. */
    public boolean shouldAcceptConnections() {
        return level != Level.EMERGENCY;
    }

    /** Whether ambient/low-priority events should be published. */
    public boolean shouldPublishAmbient() {
        return level == Level.NORMAL;
    }

    /** Register a listener for level changes. */
    public void addListener(Consumer<Level> listener) {
        listeners.add(listener);
    }

    /** Force a specific level (for testing or manual override). */
    public void setLevel(Level level) {
        var previous = this.level;
        this.level = level;
        if (previous != level) {
            log.info("Degradation level manually set: {} -> {}", previous, level);
            notifyListeners(level);
        }
    }

    private void notifyListeners(Level newLevel) {
        for (var listener : listeners) {
            try {
                listener.accept(newLevel);
            } catch (Exception e) {
                log.warn("Degradation listener threw: {}", e.getMessage());
            }
        }
    }

    /**
     * Map a percentage metric to a severity level (0=normal, 1=high, 2=overloaded, 3=critical, 4=emergency).
     * Each threshold is inclusive (e.g., 80% CPU with highThreshold=80 triggers HIGH_LOAD).
     * Emergency is triggered at critical + half the gap above critical to 100.
     */
    private static int levelFromPct(double pct, int highThreshold, int overloadedThreshold, int criticalThreshold) {
        // Emergency = critical + half the gap above critical to 100
        double emergencyThreshold = criticalThreshold + (100.0 - criticalThreshold) / 2.0;
        if (pct >= emergencyThreshold) return 4;
        if (pct >= criticalThreshold) return 3;
        if (pct >= overloadedThreshold) return 2;
        if (pct >= highThreshold) return 1;
        return 0;
    }
}
