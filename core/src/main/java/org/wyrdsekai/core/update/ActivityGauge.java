package org.wyrdsekai.core.update;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the node is doing right now, for anyone who must wait for a quiet moment: the
 * self-updater, before it swaps the program under a running household. Two counters and a
 * clock — inference requests at a backend, coding tasks a backend is running — and the
 * instant either was last non-zero. A node is idle when both are zero and have been for a
 * while; a coding task drives the node's model directly, not through the router, so it is
 * counted on its own.
 */
public final class ActivityGauge {

    private ActivityGauge() {}

    private static final AtomicInteger INFERENCE = new AtomicInteger();
    private static final AtomicInteger CODING = new AtomicInteger();
    /** A backup, a sleep write, an index rebuild: work that should not be interrupted by an update either. */
    private static final AtomicInteger MAINTENANCE = new AtomicInteger();
    private static volatile long lastBusyMillis = System.currentTimeMillis();

    public static void inferenceStarted() { INFERENCE.incrementAndGet(); lastBusyMillis = System.currentTimeMillis(); }
    public static void inferenceFinished() { INFERENCE.updateAndGet(n -> Math.max(0, n - 1)); lastBusyMillis = System.currentTimeMillis(); }
    public static void codingTaskStarted() { CODING.incrementAndGet(); lastBusyMillis = System.currentTimeMillis(); }
    public static void codingTaskFinished() { CODING.updateAndGet(n -> Math.max(0, n - 1)); lastBusyMillis = System.currentTimeMillis(); }

    public static void maintenanceStarted() { MAINTENANCE.incrementAndGet(); lastBusyMillis = System.currentTimeMillis(); }
    public static void maintenanceFinished() { MAINTENANCE.updateAndGet(n -> Math.max(0, n - 1)); lastBusyMillis = System.currentTimeMillis(); }
    public static int maintenanceRunning() { return MAINTENANCE.get(); }

    public static int inferenceInFlight() { return INFERENCE.get(); }
    public static int codingTasks() { return CODING.get(); }

    /** True when nothing is in flight and nothing has been for at least {@code quiet}. */
    public static boolean idleFor(Duration quiet) {
        if (INFERENCE.get() > 0 || CODING.get() > 0 || MAINTENANCE.get() > 0) return false;
        return System.currentTimeMillis() - lastBusyMillis >= quiet.toMillis();
    }

    public static Instant lastBusy() { return Instant.ofEpochMilli(lastBusyMillis); }

    /** Tests only. */
    static void reset() { INFERENCE.set(0); CODING.set(0); MAINTENANCE.set(0); lastBusyMillis = 0L; }
}
