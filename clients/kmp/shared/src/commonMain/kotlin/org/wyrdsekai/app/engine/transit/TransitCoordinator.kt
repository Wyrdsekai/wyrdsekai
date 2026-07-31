package org.wyrdsekai.app.engine.transit

import org.wyrdsekai.app.engine.PhoneNode
import org.wyrdsekai.app.engine.room.RoomEngineCommand
import org.wyrdsekai.app.protocol.C2SMessage
import org.wyrdsekai.app.protocol.S2CMessage

/**
 * Coordinates between local PhoneNode rooms and remote household server rooms.
 *
 * When the player is in a local room, commands route to the PhoneNode engine.
 * When the player transits to a remote room (one that exists on the server but
 * not locally), commands are forwarded via the ServerConnection using typed
 * C2S messages.
 *
 * Transit triggers:
 * - Player moves to a room that's remote-only → enter remote mode
 * - Player moves back to a local room → enter local mode
 * - Server sends S2CTransit → switch zones
 */
class TransitCoordinator(
    private val phoneNode: PhoneNode,
    private val serverConnection: ServerConnection?,
) {
    enum class Mode { LOCAL, REMOTE }

    private var _mode = Mode.LOCAL
    val mode: Mode get() = _mode

    private var _remoteRoomId: String? = null
    val remoteRoomId: String? get() = _remoteRoomId

    private val eventListeners = mutableListOf<(TransitEvent) -> Unit>()
    private var serverUnsub: (() -> Unit)? = null

    private var idCounter = 0L
    private fun nextId(): String = "transit-${++idCounter}"

    /** Start listening for server messages. */
    fun start() {
        serverUnsub = serverConnection?.onMessage { msg ->
            handleServerMessage(msg)
        }
    }

    /** Stop listening. */
    fun stop() {
        serverUnsub?.invoke()
        serverUnsub = null
    }

    fun onEvent(listener: (TransitEvent) -> Unit): () -> Unit {
        eventListeners.add(listener)
        return { eventListeners.remove(listener) }
    }

    private fun emit(event: TransitEvent) {
        for (listener in eventListeners) {
            listener(event)
        }
    }

    /**
     * Handle "go" — checks if the target room is local or remote.
     * Returns true if handled, false if the direction is invalid.
     */
    suspend fun go(entityId: String, entityName: String, direction: String): Boolean {
        if (_mode == Mode.LOCAL) {
            val currentRoom = phoneNode.currentRoom() ?: return false
            val exit = currentRoom.state.value.exits[direction] ?: return false
            val targetRoomId = exit.targetRoom

            // Is the target room local?
            if (targetRoomId in phoneNode.activeRoomIds()) {
                phoneNode.go(entityId, entityName, direction)
                return true
            }

            // Is the target room remote?
            val conn = serverConnection
            if (conn != null && conn.isConnected && targetRoomId in conn.remoteRoomIds()) {
                transitToRemote(entityId, entityName, targetRoomId)
                return true
            }

            // Room not available anywhere
            return false
        } else {
            // In remote mode — send typed Go to server
            val conn = serverConnection ?: return false
            conn.send(C2SMessage.Go(
                id = nextId(),
                roomId = _remoteRoomId ?: "",
                direction = direction,
            ))
            return true
        }
    }

    /**
     * Handle "say" — routes to local or remote.
     */
    suspend fun say(entityId: String, entityName: String, text: String) {
        if (_mode == Mode.LOCAL) {
            phoneNode.say(entityId, entityName, text)
        } else {
            serverConnection?.send(C2SMessage.Say(
                id = nextId(),
                roomId = _remoteRoomId ?: "",
                text = text,
            ))
        }
    }

    /**
     * Handle "look" — returns local snapshot or sends Look to server.
     */
    suspend fun look() {
        if (_mode == Mode.LOCAL) {
            // Caller can read PhoneNode.look() directly
            phoneNode.look()
        } else {
            serverConnection?.send(C2SMessage.Look(
                id = nextId(),
                roomId = _remoteRoomId ?: "",
            ))
            // Server response arrives async via onMessage → RemoteRoomState event
        }
    }

    /**
     * Explicitly return to a local room.
     */
    suspend fun returnToLocal(entityId: String, entityName: String, localRoomId: String = "home") {
        if (_mode != Mode.REMOTE) return
        _mode = Mode.LOCAL
        _remoteRoomId = null
        emit(TransitEvent.ReturnedToLocal(localRoomId))
    }

    /** Whether the coordinator is currently routing to a remote room. */
    val isRemote: Boolean get() = _mode == Mode.REMOTE

    // ── Private ──────────────────────────────────────────────────────────

    private suspend fun transitToRemote(entityId: String, entityName: String, targetRoomId: String) {
        // Leave current local room
        phoneNode.currentRoom()?.send(
            RoomEngineCommand.LeaveRoom(entityId, entityName, "transit")
        )

        _mode = Mode.REMOTE
        _remoteRoomId = targetRoomId
        emit(TransitEvent.TransitedToRemote(targetRoomId))

        // Tell the server we want to look at this room
        serverConnection?.send(C2SMessage.Look(
            id = nextId(),
            roomId = targetRoomId,
        ))
    }

    private fun handleServerMessage(msg: S2CMessage) {
        when (msg) {
            is S2CMessage.Transit -> {
                emit(TransitEvent.ServerTransit(msg.targetZoneId, msg.message))
            }
            is S2CMessage.Prose -> {
                if (_mode == Mode.REMOTE) {
                    emit(TransitEvent.RemoteProse(msg.speaker, msg.text))
                }
            }
            is S2CMessage.RoomState -> {
                if (_mode == Mode.REMOTE) {
                    _remoteRoomId = msg.room.roomId
                    emit(TransitEvent.RemoteRoomState(
                        roomId = msg.room.roomId,
                        name = msg.room.name,
                        description = msg.room.description,
                    ))
                }
            }
            else -> {} // Other S2C messages handled elsewhere
        }
    }
}

/** Events emitted by TransitCoordinator for the UI layer. */
sealed class TransitEvent {
    /** Player moved from local to a remote room. */
    data class TransitedToRemote(val roomId: String) : TransitEvent()
    /** Player returned from remote to local. */
    data class ReturnedToLocal(val roomId: String) : TransitEvent()
    /** Server initiated a zone transit. */
    data class ServerTransit(val targetZoneId: String, val message: String) : TransitEvent()
    /** Prose received from remote room. */
    data class RemoteProse(val speaker: String, val text: String) : TransitEvent()
    /** Room state received from remote room. */
    data class RemoteRoomState(val roomId: String, val name: String, val description: String) : TransitEvent()
}
