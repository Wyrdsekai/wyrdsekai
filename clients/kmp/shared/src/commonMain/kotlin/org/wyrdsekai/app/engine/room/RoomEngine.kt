package org.wyrdsekai.app.engine.room

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.time.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.engine.persistence.EventJournal
import org.wyrdsekai.app.engine.scripting.ScriptEmission
import org.wyrdsekai.app.engine.scripting.ScriptEngine
import org.wyrdsekai.app.protocol.Hint
import org.wyrdsekai.app.protocol.RoomSnapshot

/**
 * Core room engine — replaces Pekko's RoomActor with coroutine-based processing.
 * A Channel<Command> with a single consumer coroutine gives the same sequential
 * processing guarantee as a Pekko actor mailbox.
 */
class RoomEngine(
    val roomId: String,
    private val journal: EventJournal,
    private val scriptEngine: ScriptEngine?,
    private val scriptSource: String?,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(RoomState.empty(roomId))
    val state: StateFlow<RoomState> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<WorldEvent>(extraBufferCapacity = 64)
    val notifications: SharedFlow<WorldEvent> = _notifications.asSharedFlow()

    private val commandChannel = Channel<Pair<RoomEngineCommand, CompletableDeferred<RoomEngineResponse>>>(Channel.BUFFERED)
    private val json = Json { ignoreUnknownKeys = true }

    private var recovered = false

    init {
        scriptSource?.let { scriptEngine?.loadScript(it) }
        scope.launch { recover() }
        scope.launch { processCommands() }
    }

    suspend fun send(command: RoomEngineCommand): RoomEngineResponse {
        val deferred = CompletableDeferred<RoomEngineResponse>()
        commandChannel.send(command to deferred)
        return deferred.await()
    }

    fun sendAsync(command: RoomEngineCommand) {
        scope.launch { send(command) }
    }

    private suspend fun recover() {
        val events = journal.replay(roomId)
        var s = RoomState.empty(roomId)
        for (event in events) {
            s = s.apply(event)
        }
        _state.value = s
        scriptEngine?.syncState(s)
        recovered = true
    }

    private suspend fun processCommands() {
        for ((cmd, deferred) in commandChannel) {
            try {
                val result = handle(cmd)
                deferred.complete(result)
            } catch (e: Exception) {
                deferred.complete(RoomEngineResponse.Rejected("internal_error", e.message ?: "Unknown error"))
            }
        }
    }

    private suspend fun handle(cmd: RoomEngineCommand): RoomEngineResponse {
        val now = Clock.System.now()
        val currentState = _state.value
        val events = mutableListOf<WorldEvent>()

        when (cmd) {
            is RoomEngineCommand.CreateRoom -> {
                events.add(WorldEvent.RoomCreated(roomId, now, cmd.name, cmd.description, cmd.zone))
                for (exit in cmd.exits) {
                    events.add(WorldEvent.ExitOpened(roomId, now, exit.direction, exit.targetRoom, exit.label))
                }
                for (obj in cmd.objects) {
                    events.add(WorldEvent.ObjectAdded(roomId, now, obj.id, obj.name, obj.description, obj.takeable))
                }
            }
            is RoomEngineCommand.EnterRoom -> {
                events.add(WorldEvent.EntityEntered(roomId, now, cmd.entityId, cmd.entityName, cmd.entityType, cmd.fromDirection))
            }
            is RoomEngineCommand.LeaveRoom -> {
                events.add(WorldEvent.EntityLeft(roomId, now, cmd.entityId, cmd.entityName, cmd.direction))
            }
            is RoomEngineCommand.SayInRoom -> {
                events.add(WorldEvent.Said(roomId, now, cmd.entityId, cmd.entityName, cmd.text))
            }
            is RoomEngineCommand.TakeObject -> {
                val obj = currentState.objects.values.find {
                    it.name.equals(cmd.objectName, ignoreCase = true)
                }
                if (obj == null) {
                    return RoomEngineResponse.Rejected("not_found", "No object named '${cmd.objectName}' here.")
                }
                if (!obj.takeable) {
                    return RoomEngineResponse.Rejected("not_takeable", "You can't take the ${obj.name}.")
                }
                events.add(WorldEvent.ObjectTaken(roomId, now, cmd.entityId, obj.id, obj.name))
            }
            is RoomEngineCommand.DropObject -> {
                events.add(WorldEvent.ObjectDropped(
                    roomId, now, cmd.entityId, cmd.objectId,
                    cmd.objectName, cmd.description, cmd.takeable,
                ))
            }
            is RoomEngineCommand.UseObject -> {
                val obj = currentState.objects.values.find {
                    it.name.equals(cmd.objectName, ignoreCase = true)
                }
                if (obj == null) {
                    return RoomEngineResponse.Rejected("not_found", "No object named '${cmd.objectName}' here.")
                }
                events.add(WorldEvent.ObjectUsed(roomId, now, cmd.entityId, obj.id, obj.name, cmd.target, null))
            }
            is RoomEngineCommand.EmoteInRoom -> {
                events.add(WorldEvent.Emoted(roomId, now, cmd.entityId, cmd.entityName, cmd.text))
            }
            is RoomEngineCommand.SelectHint -> {
                val hints = currentState.hints
                if (cmd.index < 0 || cmd.index >= hints.size) {
                    return RoomEngineResponse.Rejected("invalid_index", "Invalid hint index.")
                }
                // Hint selection doesn't generate an event — it's dispatched by the caller
            }
            is RoomEngineCommand.SetProperty -> {
                events.add(WorldEvent.PropertyChanged(roomId, now, cmd.key, currentState.properties[cmd.key], cmd.value))
            }
        }

        // Persist and apply events
        for (event in events) {
            journal.append(roomId, event)
            _state.value = _state.value.apply(event)
            _notifications.emit(event)
        }

        // Invoke script hooks
        val hookName = when (cmd) {
            is RoomEngineCommand.EnterRoom -> "onEnter"
            is RoomEngineCommand.SayInRoom -> "onSay"
            is RoomEngineCommand.EmoteInRoom -> "onEmote"
            is RoomEngineCommand.UseObject -> "onUse"
            is RoomEngineCommand.TakeObject -> "onTake"
            is RoomEngineCommand.DropObject -> "onDrop"
            else -> null
        }
        if (hookName != null && scriptEngine != null) {
            scriptEngine.syncState(_state.value)
            val hookArgs = buildHookArgs(cmd)
            val emissions = scriptEngine.callHook(hookName, hookArgs)
            for (emission in emissions) {
                handleScriptEmission(emission, now)
            }
        }

        return RoomEngineResponse.Ok(_state.value.toSnapshot())
    }

    private suspend fun handleScriptEmission(emission: ScriptEmission, now: kotlin.time.Instant) {
        val event: WorldEvent? = when (emission.eventType) {
            "narrate" -> {
                val text = emission.data["text"] ?: return
                WorldEvent.Said(roomId, now, "narrator", "narrator", text)
            }
            "description_changed" -> {
                val desc = emission.data["description"] ?: emission.data["text"] ?: return
                WorldEvent.DescriptionChanged(roomId, now, desc, "script")
            }
            "hints_updated" -> null // Handled via callHints()
            "study_action" -> {
                val action = emission.data["action"] ?: return
                WorldEvent.ScriptTriggered(roomId, now, "study_action", action, emission.data)
            }
            "property_changed" -> {
                val key = emission.data["key"] ?: return
                val value = emission.data["value"]
                WorldEvent.PropertyChanged(roomId, now, key, _state.value.properties[key], value)
            }
            else -> null
        }

        if (event != null) {
            journal.append(roomId, event)
            _state.value = _state.value.apply(event)
            _notifications.emit(event)
        }
    }

    private fun buildHookArgs(cmd: RoomEngineCommand): List<Any> = when (cmd) {
        is RoomEngineCommand.EnterRoom -> listOf(cmd.entityId, cmd.entityName, cmd.fromDirection)
        is RoomEngineCommand.SayInRoom -> listOf(cmd.entityId, cmd.entityName, cmd.text)
        is RoomEngineCommand.EmoteInRoom -> listOf(cmd.entityId, cmd.entityName, cmd.text)
        is RoomEngineCommand.UseObject -> listOf(cmd.entityId, cmd.objectName, cmd.target ?: "")
        is RoomEngineCommand.TakeObject -> listOf(cmd.entityId, cmd.objectName, "")
        is RoomEngineCommand.DropObject -> listOf(cmd.entityId, cmd.objectName, cmd.objectId)
        else -> emptyList()
    }

    fun shutdown() {
        commandChannel.close()
    }
}
