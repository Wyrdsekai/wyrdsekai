package org.wyrdsekai.scripting.sandbox;

import org.graalvm.polyglot.Context;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cancels a script evaluation that uses more CPU time than it is allowed.
 *
 * <p>One daemon thread polls every running evaluation. It measures the CPU time of the thread
 * the script runs on, so a hook that waits on a slow host call (an MCP server, the library) is
 * not charged for the wait; a loop, a runaway regular expression or a huge string operation is.
 * Where the JVM cannot report a thread's CPU time (virtual threads) it falls back to wall-clock
 * time. A tripped evaluation is cancelled with {@code Context.close(true)}: the script stops at
 * its next safepoint and the calling thread gets a cancelled {@code PolyglotException}.
 */
final class ScriptWatchdog {

    private static final long POLL_MS = 50;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final Set<Watch> RUNNING = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService POLLER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "script-watchdog");
            t.setDaemon(true);
            return t;
        });

    static {
        POLLER.scheduleWithFixedDelay(ScriptWatchdog::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
    }

    private ScriptWatchdog() {}

    /** One watched evaluation. Close it when the evaluation ends, before closing the context. */
    static final class Watch implements AutoCloseable {
        private final Context context;
        private final long threadId;
        private final boolean cpuClock;
        private final long startNanos;
        private final long budgetNanos;
        private volatile boolean tripped;

        private Watch(Context context, Thread thread, long budgetMs) {
            this.context = context;
            this.threadId = thread.threadId();
            long cpu = thread.isVirtual() ? -1 : cpuTime(threadId);
            this.cpuClock = cpu >= 0;
            this.startNanos = cpuClock ? cpu : System.nanoTime();
            this.budgetNanos = TimeUnit.MILLISECONDS.toNanos(budgetMs);
        }

        /** True when the watchdog cancelled this evaluation for running over its budget. */
        boolean tripped() { return tripped; }

        private long used() {
            if (!cpuClock) return System.nanoTime() - startNanos;
            long now = cpuTime(threadId);
            return now < 0 ? 0 : now - startNanos;
        }

        @Override
        public void close() {
            RUNNING.remove(this);
        }
    }

    /** Watch the evaluation about to run on the current thread in {@code context}. */
    static Watch watch(Context context, long budgetMs) {
        var w = new Watch(context, Thread.currentThread(), budgetMs);
        RUNNING.add(w);
        return w;
    }

    private static long cpuTime(long threadId) {
        try {
            return THREADS.isThreadCpuTimeSupported() ? THREADS.getThreadCpuTime(threadId) : -1;
        } catch (UnsupportedOperationException e) {
            return -1;
        }
    }

    private static void poll() {
        for (var w : RUNNING) {
            if (w.used() <= w.budgetNanos) continue;
            if (!RUNNING.remove(w)) continue;   // finished in the meantime
            w.tripped = true;
            try {
                w.context.close(true);
            } catch (RuntimeException ignored) {
                // Already closed by its owner, or closing from here is refused: either way it stops.
            }
        }
    }
}
