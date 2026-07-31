package org.wyrdsekai.app.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import org.wyrdsekai.app.node.NodeManager

/**
 * View model for the embedded node controls.
 *
 * Exposes [nodeState], [errorMessage], and toggle/start/stop actions.
 * On platforms where the embedded node is not yet available
 * ([isAvailable] == false), the UI should hide or disable controls.
 */
class NodeViewModel(scope: CoroutineScope) {
    internal val nodeManager = NodeManager(scope)

    val nodeState: StateFlow<String> = nodeManager.state
    val errorMessage: StateFlow<String?> = nodeManager.errorMessage
    val isAvailable: Boolean = nodeManager.isAvailable
    val port: Int = nodeManager.port

    fun startNode() = nodeManager.start()
    fun stopNode() = nodeManager.stop()

    fun toggleNode() {
        if (nodeState.value == "running" || nodeState.value == "starting") {
            stopNode()
        } else {
            startNode()
        }
    }
}
