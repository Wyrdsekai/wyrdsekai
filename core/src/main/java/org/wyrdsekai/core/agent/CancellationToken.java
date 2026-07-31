package org.wyrdsekai.core.agent;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight cancellation signal that propagates through the actor chain.
 *
 * <p>Thread-safe. Parent cancellation propagates to all children.
 * Used by InferenceRouter (skip cancelled queue entries, discard stale responses)
 * and CompanionActor (cancel on new input, abort command).</p>
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<String> reason = new AtomicReference<>();
    private final Instant createdAt;
    private final CopyOnWriteArrayList<CancellationToken> children = new CopyOnWriteArrayList<>();

    public CancellationToken() {
        this.createdAt = Instant.now();
    }

    /** Check if cancellation has been requested. */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Request cancellation. Propagates to all child tokens.
     *
     * @param reason human-readable reason for cancellation
     */
    public void cancel(String reason) {
        if (cancelled.compareAndSet(false, true)) {
            this.reason.set(reason);
            for (var child : children) {
                child.cancel("parent: " + reason);
            }
        }
    }

    /** Get the cancellation reason (null if not cancelled). */
    public String reason() {
        return reason.get();
    }

    /** Get the creation timestamp. */
    public Instant createdAt() {
        return createdAt;
    }

    /**
     * Create a child token that is cancelled when this parent is cancelled.
     * If the parent is already cancelled, the child starts cancelled.
     */
    public CancellationToken child() {
        var child = new CancellationToken();
        if (cancelled.get()) {
            child.cancel("parent already cancelled: " + reason.get());
        } else {
            children.add(child);
        }
        return child;
    }

    /**
     * A shared token that is never cancelled.
     * For code paths that don't use cancellation — check isCancelled() safely returns false.
     * Do NOT call cancel() on this instance.
     */
    public static final CancellationToken NONE = new CancellationToken();
}
