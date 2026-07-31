package org.wyrdsekai.core.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Limits concurrent requests to a backend with priority queue.
 *
 * <p>When at capacity, incoming requests queue up to {@code maxQueueDepth}.
 * Queued requests are drained in priority order (INTERACTIVE > AUTONOMOUS > BACKGROUND).
 * If the queue is full, the request is rejected immediately.</p>
 */
public class ConcurrencyController {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyController.class);

    public enum Priority {
        INTERACTIVE(0), AUTONOMOUS(1), BACKGROUND(2);

        private final int order;
        Priority(int order) { this.order = order; }
        public int order() { return order; }
    }

    private final Semaphore permits;
    private final int maxQueueDepth;
    private final AtomicInteger queueDepth = new AtomicInteger(0);
    private final AtomicLong rejectedCount = new AtomicLong(0);
    private final AtomicLong completedCount = new AtomicLong(0);
    private final String name;

    // Priority queue for waiting requests
    private final PriorityBlockingQueue<PendingRequest<?>> waitQueue;

    public ConcurrencyController(String name, int maxConcurrency, int maxQueueDepth) {
        this.name = name;
        this.permits = new Semaphore(maxConcurrency);
        this.maxQueueDepth = maxQueueDepth;
        this.waitQueue = new PriorityBlockingQueue<>(
            maxQueueDepth > 0 ? maxQueueDepth : 11,
            Comparator.comparingInt(r -> r.priority.order())
        );
    }

    public ConcurrencyController(int maxConcurrency, int maxQueueDepth) {
        this("default", maxConcurrency, maxQueueDepth);
    }

    /**
     * Submit an action for execution. If a permit is available, execute immediately.
     * If not, queue the request. If the queue is full, reject with a failed future.
     */
    public <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> action, Priority priority) {
        // Try to acquire a permit immediately
        if (permits.tryAcquire()) {
            return executeAndRelease(action);
        }

        // Check queue depth
        if (queueDepth.get() >= maxQueueDepth) {
            rejectedCount.incrementAndGet();
            log.debug("ConcurrencyController '{}' rejected request (queue full: {}/{})",
                name, queueDepth.get(), maxQueueDepth);
            return CompletableFuture.failedFuture(
                new ConcurrencyExceededException(name, maxQueueDepth));
        }

        // Queue the request
        var future = new CompletableFuture<T>();
        var pending = new PendingRequest<>(action, priority, future);
        queueDepth.incrementAndGet();
        waitQueue.offer(pending);

        // Try to drain the queue (another request may have completed)
        drainQueue();

        return future;
    }

    /** Submit with default AUTONOMOUS priority. */
    public <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> action) {
        return submit(action, Priority.AUTONOMOUS);
    }

    /** Current number of active (in-flight) requests. */
    public int getActiveCount() {
        return permits.availablePermits() == 0
            ? permits.getQueueLength() + (permits.availablePermits() == 0 ? maxConcurrency() : 0)
            : maxConcurrency() - permits.availablePermits();
    }

    /** Current queue depth. */
    public int getQueueDepth() {
        return queueDepth.get();
    }

    /** Total rejected requests since creation. */
    public long getRejectedCount() {
        return rejectedCount.get();
    }

    /** Total completed requests since creation. */
    public long getCompletedCount() {
        return completedCount.get();
    }

    public String getName() {
        return name;
    }

    private int maxConcurrency() {
        // Semaphore doesn't expose initial permits, so compute from available + queued
        return permits.availablePermits() + (int) completedCount.get()
            - (int) completedCount.get() + permits.availablePermits();
    }

    private <T> CompletableFuture<T> executeAndRelease(Supplier<CompletableFuture<T>> action) {
        try {
            return action.get().whenComplete((result, ex) -> {
                permits.release();
                completedCount.incrementAndGet();
                drainQueue();
            });
        } catch (Exception e) {
            permits.release();
            completedCount.incrementAndGet();
            drainQueue();
            return CompletableFuture.failedFuture(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void drainQueue() {
        while (!waitQueue.isEmpty() && permits.tryAcquire()) {
            var pending = waitQueue.poll();
            if (pending == null) {
                permits.release();
                break;
            }
            queueDepth.decrementAndGet();
            try {
                var result = pending.action.get();
                result.whenComplete((r, ex) -> {
                    permits.release();
                    completedCount.incrementAndGet();
                    if (ex != null) {
                        ((CompletableFuture<Object>) pending.future).completeExceptionally(ex);
                    } else {
                        ((CompletableFuture<Object>) pending.future).complete(r);
                    }
                    drainQueue();
                });
            } catch (Exception e) {
                permits.release();
                completedCount.incrementAndGet();
                pending.future.completeExceptionally(e);
                drainQueue();
            }
        }
    }

    private record PendingRequest<T>(
        Supplier<CompletableFuture<T>> action,
        Priority priority,
        CompletableFuture<T> future
    ) {}

    /** Exception thrown when the concurrency queue is full. */
    public static class ConcurrencyExceededException extends RuntimeException {
        public ConcurrencyExceededException(String controllerName, int maxQueue) {
            super("ConcurrencyController '" + controllerName + "' queue full (max " + maxQueue + ")");
        }
    }
}
