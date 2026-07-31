package org.wyrdsekai.app.node

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-specific node manager.
 *
 * On desktop, delegates to [EmbeddedNode] which runs a Wyrdsekai server
 * subprocess. On Android and iOS, returns stubs (Phase 2b / Phase 4).
 */
expect class NodeManager(scope: CoroutineScope) {
    /** "stopped" | "starting" | "running" | "error" */
    val state: StateFlow<String>

    /** null when healthy, otherwise a human-readable error description. */
    val errorMessage: StateFlow<String?>

    /** true when a local PhoneNode can be started. */
    val isAvailable: Boolean

    /** The port the embedded server listens on (0 = in-process). */
    val port: Int

    /** The running PhoneNode instance, if any. Null when stopped or on platforms without PhoneNode. */
    val phoneNode: org.wyrdsekai.app.engine.PhoneNode?

    /** Model status: "idle", "checking", "downloading", "loading", "ready", "unavailable" */
    val modelStatus: StateFlow<String>

    /** Download progress 0.0-1.0 */
    val modelProgress: StateFlow<Float>

    /** Human-readable model status for display */
    val modelStatusText: StateFlow<String?>

    fun start()
    fun stop()
}
