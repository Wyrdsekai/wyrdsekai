package org.wyrdsekai.app.engine.between

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.engine.room.RoomEngineCommand
import org.wyrdsekai.app.engine.room.RoomState

/**
 * Looks like a RoomEngine to PhoneNode but forwards everything over Between.
 *
 * Subscribes to `between.{householdId}.room.{roomId}.events` for incoming WorldEvents.
 * Publishes commands to `between.{householdId}.room.{roomId}.commands`.
 * Maintains local RoomState by applying received events.
 *
 * This enables room visiting (SSH model) — the phone companion can visit rooms
 * hosted on other nodes without running the room engine locally.
 *
 */
class VisitingRoomProxy(
    val roomId: String,
    private val betweenClient: BetweenClient,
    private val householdId: String,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(RoomState.empty(roomId))
    val state: StateFlow<RoomState> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<WorldEvent>(extraBufferCapacity = 64)
    val notifications: SharedFlow<WorldEvent> = _notifications.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private var unsubscribeEvents: (() -> Unit)? = null

    /** Start receiving events from the remote room. */
    fun startListening() {
        val subject = "between.$householdId.room.$roomId.events"
        unsubscribeEvents = betweenClient.subscribe(subject) { _, data ->
            try {
                val event = json.decodeFromString<WorldEvent>(data.decodeToString())
                _state.value = _state.value.apply(event)
                scope.launch { _notifications.emit(event) }
            } catch (_: Exception) {
                // Malformed event — skip
            }
        }
    }

    /**
     * Send a command to the remote room engine.
     * The command is serialized to JSON and published to the room's command subject.
     *
     * RoomEngineCommand is not @Serializable (it uses sealed class without
     * serialization annotations), so we manually build JSON matching the
     * wire protocol discriminated union format.
     */
    fun send(command: RoomEngineCommand) {
        val jsonObj = encodeCommand(command)
        val data = jsonObj.toString().encodeToByteArray()
        betweenClient.publish("between.$householdId.room.$roomId.commands", data)
    }

    /** Stop receiving events and clean up. */
    fun shutdown() {
        unsubscribeEvents?.invoke()
        unsubscribeEvents = null
    }

    /**
     * Encode a RoomEngineCommand to a JSON object matching the wire protocol format.
     * Each command type maps to a discriminated union with a "type" field.
     */
    private fun encodeCommand(command: RoomEngineCommand) = when (command) {
        is RoomEngineCommand.SayInRoom -> buildJsonObject {
            put("type", "say_in_room")
            put("entityId", command.entityId)
            put("entityName", command.entityName)
            put("text", command.text)
        }
        is RoomEngineCommand.EnterRoom -> buildJsonObject {
            put("type", "enter_room")
            put("entityId", command.entityId)
            put("entityName", command.entityName)
            put("entityType", command.entityType)
            put("fromDirection", command.fromDirection)
        }
        is RoomEngineCommand.LeaveRoom -> buildJsonObject {
            put("type", "leave_room")
            put("entityId", command.entityId)
            put("entityName", command.entityName)
            put("direction", command.direction)
        }
        is RoomEngineCommand.TakeObject -> buildJsonObject {
            put("type", "take_object")
            put("entityId", command.entityId)
            put("objectName", command.objectName)
        }
        is RoomEngineCommand.DropObject -> buildJsonObject {
            put("type", "drop_object")
            put("entityId", command.entityId)
            put("objectName", command.objectName)
            put("objectId", command.objectId)
            put("description", command.description)
            put("takeable", command.takeable)
        }
        is RoomEngineCommand.UseObject -> buildJsonObject {
            put("type", "use_object")
            put("entityId", command.entityId)
            put("objectName", command.objectName)
            put("target", command.target)
        }
        is RoomEngineCommand.CreateRoom -> buildJsonObject {
            put("type", "create_room")
            put("name", command.name)
            put("description", command.description)
            put("zone", command.zone)
        }
        is RoomEngineCommand.SelectHint -> buildJsonObject {
            put("type", "select_hint")
            put("entityId", command.entityId)
            put("index", command.index)
        }
        is RoomEngineCommand.EmoteInRoom -> buildJsonObject {
            put("type", "emote_in_room")
            put("entityId", command.entityId)
            put("entityName", command.entityName)
            put("text", command.text)
        }
        is RoomEngineCommand.SetProperty -> buildJsonObject {
            put("type", "set_property")
            put("key", command.key)
            put("value", command.value)
        }
    }
}
