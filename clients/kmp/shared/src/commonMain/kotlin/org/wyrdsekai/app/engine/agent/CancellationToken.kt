package org.wyrdsekai.app.engine.agent

import kotlin.concurrent.Volatile

/**
 * Lightweight cancellation signal that propagates through the actor chain (KMP port).
 *
 * Parent cancellation propagates to all children.
 * Used by InferenceRouter (skip cancelled queue entries, discard stale responses)
 * and CompanionEngine (cancel on new input, abort command).
 *
 * Note: On KMP, concurrent mutation is guarded by @Volatile. In a coroutine-based
 * runtime (which Wyrdsekai phone clients use), all access is single-threaded per
 * dispatcher, so this is sufficient.
 */
class CancellationToken {

    @Volatile
    private var _cancelled = false

    @Volatile
    private var _reason: String? = null

    val createdAt: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
    private val children = mutableListOf<CancellationToken>()

    /** Check if cancellation has been requested. */
    val isCancelled: Boolean get() = _cancelled

    /** Get the cancellation reason (null if not cancelled). */
    val reason: String? get() = _reason

    /**
     * Request cancellation. Propagates to all child tokens.
     *
     * @param reason human-readable reason for cancellation
     */
    fun cancel(reason: String) {
        if (_cancelled) return
        _cancelled = true
        _reason = reason
        for (child in children.toList()) {
            child.cancel("parent: $reason")
        }
    }

    /**
     * Create a child token that is cancelled when this parent is cancelled.
     * If the parent is already cancelled, the child starts cancelled.
     */
    fun child(): CancellationToken {
        val child = CancellationToken()
        if (_cancelled) {
            child.cancel("parent already cancelled: $_reason")
        } else {
            children.add(child)
        }
        return child
    }

    companion object {
        /**
         * A shared token that is never cancelled.
         * For code paths that don't use cancellation -- check isCancelled safely returns false.
         * Do NOT call cancel() on this instance.
         */
        val NONE = CancellationToken()
    }
}
