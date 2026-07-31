package org.wyrdsekai.app.engine.transit

import org.wyrdsekai.app.protocol.C2SMessage
import org.wyrdsekai.app.protocol.S2CMessage

/**
 * Abstraction for a connection to a household server.
 * Real implementation wraps WyrdWebSocket; tests use InMemoryServerConnection.
 */
interface ServerConnection {
    val isConnected: Boolean

    /** Send a C2S command to the server. */
    suspend fun send(message: C2SMessage)

    /** Register a handler for S2C messages from the server. Returns unsubscribe function. */
    fun onMessage(handler: (S2CMessage) -> Unit): () -> Unit

    /** Available room IDs known to be on the server. */
    fun remoteRoomIds(): Set<String>
}

/**
 * In-memory mock for testing transit coordination.
 */
class InMemoryServerConnection : ServerConnection {
    override var isConnected: Boolean = false

    val sent = mutableListOf<C2SMessage>()
    private val handlers = mutableListOf<(S2CMessage) -> Unit>()
    private val _remoteRoomIds = mutableSetOf<String>()

    override suspend fun send(message: C2SMessage) {
        sent.add(message)
    }

    override fun onMessage(handler: (S2CMessage) -> Unit): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    override fun remoteRoomIds(): Set<String> = _remoteRoomIds.toSet()

    /** Test helper: add remote room IDs. */
    fun addRemoteRooms(vararg roomIds: String) {
        _remoteRoomIds.addAll(roomIds)
    }

    /** Test helper: simulate a message from the server. */
    fun receive(message: S2CMessage) {
        for (handler in handlers) {
            handler(message)
        }
    }
}
