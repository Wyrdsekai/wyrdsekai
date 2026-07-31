package org.wyrdsekai.core.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Batches writes into transactions for SQLite write contention reduction.
 *
 * <p>Collects writes for up to {@code maxDelay} or {@code maxBatchSize} operations,
 * whichever comes first, then commits as one transaction. This reduces fsync calls
 * by an order of magnitude.</p>
 *
 * <p>Each write returns a CompletableFuture that completes when the batch containing
 * it has been committed.</p>
 */
public class WriteBatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WriteBatcher.class);

    /** A pending write operation. */
    public record WriteOp(String sql, Object[] params, CompletableFuture<Void> completion) {}

    private final Queue<WriteOp> pending = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService flusher;
    private final int maxBatchSize;
    private final Duration maxDelay;
    private final Supplier<Connection> connectionSupplier;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private ScheduledFuture<?> scheduledFlush;

    // Metrics
    private final AtomicLong totalWrites = new AtomicLong(0);
    private final AtomicLong totalBatches = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);

    /**
     * @param connectionSupplier provides JDBC connections for flushing
     * @param maxBatchSize       max operations per batch (default 100)
     * @param maxDelay           max time before flushing (default 50ms)
     */
    public WriteBatcher(Supplier<Connection> connectionSupplier, int maxBatchSize, Duration maxDelay) {
        this.connectionSupplier = connectionSupplier;
        this.maxBatchSize = maxBatchSize;
        this.maxDelay = maxDelay;
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "write-batcher-flush");
            t.setDaemon(true);
            return t;
        });
        schedulePeriodicFlush();
    }

    /**
     * Default constructor: reads batch size and delay from {@link ResilienceConfig}.
     * Falls back to hardcoded defaults if no config is set (e.g., in tests).
     */
    public WriteBatcher(Supplier<Connection> connectionSupplier) {
        this(connectionSupplier,
            ResilienceConfig.get().writeBatchMaxSize(),
            Duration.ofMillis(ResilienceConfig.get().writeBatchMaxDelayMs()));
    }

    /**
     * Enqueue a write operation. Returns a future that completes when the write is committed.
     */
    public CompletableFuture<Void> enqueue(String sql, Object... params) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("WriteBatcher is closed"));
        }
        var future = new CompletableFuture<Void>();
        pending.offer(new WriteOp(sql, params, future));
        totalWrites.incrementAndGet();

        // If we hit max batch size, flush immediately
        if (pending.size() >= maxBatchSize) {
            flusher.execute(this::flush);
        }

        return future;
    }

    /** Flush all pending writes as a single transaction. */
    void flush() {
        if (pending.isEmpty()) return;

        // Drain pending into a local list
        List<WriteOp> batch = new ArrayList<>();
        WriteOp op;
        while ((op = pending.poll()) != null && batch.size() < maxBatchSize) {
            batch.add(op);
        }

        if (batch.isEmpty()) return;

        totalBatches.incrementAndGet();

        try (var conn = connectionSupplier.get()) {
            conn.setAutoCommit(false);
            try {
                for (var writeOp : batch) {
                    try (var stmt = conn.prepareStatement(writeOp.sql())) {
                        if (writeOp.params() != null) {
                            for (int i = 0; i < writeOp.params().length; i++) {
                                stmt.setObject(i + 1, writeOp.params()[i]);
                            }
                        }
                        stmt.executeUpdate();
                    }
                }
                conn.commit();
                // Complete all futures
                for (var writeOp : batch) {
                    writeOp.completion().complete(null);
                }
                log.debug("WriteBatcher flushed {} writes in 1 transaction", batch.size());
            } catch (SQLException e) {
                conn.rollback();
                totalErrors.incrementAndGet();
                log.error("WriteBatcher batch failed (rolling back {} writes): {}",
                    batch.size(), e.getMessage());
                for (var writeOp : batch) {
                    writeOp.completion().completeExceptionally(e);
                }
            }
        } catch (SQLException e) {
            totalErrors.incrementAndGet();
            log.error("WriteBatcher connection error: {}", e.getMessage());
            for (var writeOp : batch) {
                writeOp.completion().completeExceptionally(e);
            }
        }
    }

    /** Number of pending (unflushed) writes. */
    public int getPendingCount() {
        return pending.size();
    }

    public long getTotalWrites() { return totalWrites.get(); }
    public long getTotalBatches() { return totalBatches.get(); }
    public long getTotalErrors() { return totalErrors.get(); }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Flush remaining
            flush();
            flusher.shutdown();
            try {
                if (!flusher.awaitTermination(5, TimeUnit.SECONDS)) {
                    flusher.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                flusher.shutdownNow();
            }
        }
    }

    private void schedulePeriodicFlush() {
        scheduledFlush = flusher.scheduleWithFixedDelay(
            this::flush, maxDelay.toMillis(), maxDelay.toMillis(), TimeUnit.MILLISECONDS);
    }
}
