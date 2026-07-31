package org.wyrdsekai.core.scheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Bounded executor backed by virtual threads. A semaphore limits the number
 * of concurrently executing tasks. Tasks that exceed the bound queue on the
 * semaphore until a slot opens.
 * <p>
 * This prevents runaway thread creation under pathological conditions
 * (e.g., mass event storm where all 24 agents fire LLM calls simultaneously).
 * Under normal load, the semaphore is never contended.
 */
public final class BoundedVirtualExecutor {

    private final ExecutorService executor;
    private final Semaphore semaphore;
    private final int maxConcurrent;

    public BoundedVirtualExecutor(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
        this.semaphore = new Semaphore(maxConcurrent);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Submit a task for execution on a virtual thread. If the maximum number
     * of concurrent tasks is already running, the calling virtual thread
     * waits on the semaphore until a slot opens.
     *
     * @param name thread name for debugging
     * @param task the work to execute
     */
    public void submit(String name, Runnable task) {
        executor.submit(() -> {
            Thread.currentThread().setName(name);
            try {
                semaphore.acquire();
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                semaphore.release();
            }
        });
    }

    /** Number of permits currently available (slots not in use). */
    public int availablePermits() {
        return semaphore.availablePermits();
    }

    /** Maximum concurrent tasks allowed. */
    public int maxConcurrent() {
        return maxConcurrent;
    }

    /** Number of tasks currently executing. */
    public int activeCount() {
        return maxConcurrent - semaphore.availablePermits();
    }

    /** Shutdown the executor. Outstanding tasks may be interrupted. */
    public void shutdown() {
        executor.shutdownNow();
    }
}
