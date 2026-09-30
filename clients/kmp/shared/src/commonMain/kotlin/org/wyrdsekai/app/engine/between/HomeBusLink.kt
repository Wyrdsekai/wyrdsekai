package org.wyrdsekai.app.engine.between

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps this phone on its home's own bus ( W2) while
 * the app runs: opens the bus with the phone's pairing login, retries every
 * [retryMs] while the phone is away from home, and hands the connected client
 * to [attach].
 *
 * [start] is called when the node comes up and again whenever the phone pairs
 * (any pairing door, mid-session too). A phone that was not paired at start
 * then joins the bus at once; one already on the bus with the same login
 * stays as it is; a new login replaces the old link.
 */
class HomeBusLink(
    private val scope: CoroutineScope,
    /** Where and as whom: null while the phone has no home-network pairing. */
    private val target: () -> Target?,
    /** Pins and opens the bus; throws (or returns an unconnected client) when the home is not reachable. */
    private val open: suspend (Target) -> BetweenClient,
    private val attach: (BetweenClient, Target) -> Unit,
    private val onNotPaired: () -> Unit = {},
    /** Once per attempt run: the home is not reachable now. */
    private val onWaiting: () -> Unit = {},
    private val log: (String) -> Unit = {},
    private val retryMs: Long = 60_000L,
) {
    data class Target(val url: String, val user: String, val pass: String) {
        override fun toString() = "Target($url as $user)"
    }

    private var job: Job? = null
    private var linked: Pair<BetweenClient, Target>? = null
    private var trying: Target? = null

    /** The login the bus is attached with, or null. */
    val attachedAs: Target? get() = linked?.second

    fun start() {
        val t = target()
        if (t == null) {
            if (linked == null && trying == null) onNotPaired()
            return
        }
        if (linked?.second == t || trying == t) return
        job?.cancel()
        trying = t
        job = scope.launch {
            var told = false
            while (isActive) {
                val bc = try {
                    open(t)
                } catch (e: Exception) {
                    log("Home bus not reachable: ${e.message}")
                    null
                }
                if (bc != null && bc.isConnected) {
                    val old = linked?.first
                    linked = bc to t
                    trying = null
                    log("Home bus connected at ${t.url} as ${t.user}")
                    attach(bc, t)
                    if (old != null && old !== bc) runCatching { old.disconnect() }
                    return@launch
                }
                if (bc != null) runCatching { bc.disconnect() }
                if (!told) {
                    onWaiting()
                    told = true
                }
                delay(retryMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        trying = null
        val bc = linked?.first
        linked = null
        if (bc != null) scope.launch { runCatching { bc.disconnect() } }
    }
}
