package org.wyrdsekai.app.node

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

actual class NodeManager actual constructor(scope: CoroutineScope) {
    private val node = EmbeddedNode(scope)

    actual val state: StateFlow<String> = node.state.map {
        when (it) {
            EmbeddedNode.State.STOPPED -> "stopped"
            EmbeddedNode.State.STARTING -> "starting"
            EmbeddedNode.State.RUNNING -> "running"
            EmbeddedNode.State.ERROR -> "error"
        }
    }.stateIn(scope, SharingStarted.Eagerly, "stopped")

    actual val errorMessage: StateFlow<String?> = node.errorMessage
    actual val isAvailable: Boolean = true
    actual val port: Int = node.port
    actual val phoneNode: org.wyrdsekai.app.engine.PhoneNode? = null  // Desktop uses EmbeddedNode, not PhoneNode
    actual val modelStatus: StateFlow<String> = MutableStateFlow("idle")
    actual val modelProgress: StateFlow<Float> = MutableStateFlow(0f)
    actual val modelStatusText: StateFlow<String?> = MutableStateFlow(null)

    actual fun start() = node.start()
    actual fun stop() = node.stop()
}
