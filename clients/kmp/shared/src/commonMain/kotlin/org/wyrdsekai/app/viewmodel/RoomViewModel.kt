package org.wyrdsekai.app.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.wyrdsekai.app.network.WyrdWebSocket
import org.wyrdsekai.app.protocol.*
import org.wyrdsekai.app.state.ProseEntry
import org.wyrdsekai.app.state.TokenStreamBuffer
import kotlin.random.Random

class RoomViewModel(
    private val scope: CoroutineScope,
    private val webSocket: WyrdWebSocket,
) {
    private val _roomName = MutableStateFlow("?")
    val roomName: StateFlow<String> = _roomName.asStateFlow()

    private val _roomDescription = MutableStateFlow("")
    val roomDescription: StateFlow<String> = _roomDescription.asStateFlow()

    private val _exits = MutableStateFlow<List<Exit>>(emptyList())
    val exits: StateFlow<List<Exit>> = _exits.asStateFlow()

    private val _entities = MutableStateFlow<List<Entity>>(emptyList())
    val entities: StateFlow<List<Entity>> = _entities.asStateFlow()

    private val _objects = MutableStateFlow<List<RoomObject>>(emptyList())
    val objects: StateFlow<List<RoomObject>> = _objects.asStateFlow()

    private val _hints = MutableStateFlow<List<Hint>>(emptyList())
    val hints: StateFlow<List<Hint>> = _hints.asStateFlow()

    private val _inventory = MutableStateFlow<List<RoomObject>>(emptyList())
    val inventory: StateFlow<List<RoomObject>> = _inventory.asStateFlow()

    private val _proseStream = MutableStateFlow<List<ProseEntry>>(emptyList())
    val proseStream: StateFlow<List<ProseEntry>> = _proseStream.asStateFlow()

    private val _currentRoomId = MutableStateFlow("")
    val currentRoomId: StateFlow<String> = _currentRoomId.asStateFlow()

    // Token stream buffers (keyed by source)
    private val _streamingText = MutableStateFlow<Map<String, String>>(emptyMap())
    val streamingText: StateFlow<Map<String, String>> = _streamingText.asStateFlow()

    private val tokenBuffers = mutableMapOf<String, TokenStreamBuffer>()

    init {
        scope.launch {
            webSocket.messages.collect { msg -> handleMessage(msg) }
        }
    }

    private fun handleMessage(msg: S2CMessage) {
        when (msg) {
            is S2CMessage.RoomState -> {
                val room = msg.room
                val prevRoomId = _currentRoomId.value
                _currentRoomId.value = room.roomId
                webSocket.setCurrentRoomId(room.roomId)
                _roomName.value = room.name
                _roomDescription.value = room.description
                _exits.value = room.exits
                _entities.value = room.entities
                _objects.value = room.objects
                _hints.value = room.hints
                msg.inventory?.let { _inventory.value = it }

                // Skip duplicate room description on reconnect to same room
                if (room.roomId != prevRoomId) {
                    addProse(ProseEntry(
                        speaker = "narrator",
                        text = "${room.name}\n${room.description}",
                        priority = PriorityLevel.NORMAL,
                    ))
                }
            }

            is S2CMessage.Prose -> {
                val priority = PriorityLevel.fromWire(msg.priority)
                addProse(ProseEntry(
                    speaker = msg.speaker,
                    text = msg.text,
                    priority = priority,
                    isAiGenerated = msg.isAiGenerated,
                    hints = msg.hints,
                    blocks = msg.blocks,
                ))
                if (msg.hints.isNotEmpty()) {
                    _hints.value = msg.hints
                }
            }

            is S2CMessage.AgentAction -> {
                addProse(ProseEntry(
                    speaker = msg.agentName,
                    text = "* ${msg.agentName} ${msg.description}",
                    priority = PriorityLevel.NORMAL,
                ))
            }

            is S2CMessage.StateChange -> {
                addProse(ProseEntry(
                    speaker = "narrator",
                    text = "~ ${msg.description}",
                    priority = PriorityLevel.NORMAL,
                    blocks = msg.blocks,
                ))
            }

            is S2CMessage.Error -> {
                addProse(ProseEntry(
                    speaker = "system",
                    text = "Error [${msg.code}]: ${msg.message}",
                    priority = PriorityLevel.CRITICAL,
                ))
            }

            is S2CMessage.Notification -> {
                addProse(ProseEntry(
                    speaker = "system",
                    text = "[${msg.title}] ${msg.message}",
                    priority = PriorityLevel.NORMAL,
                ))
            }

            is S2CMessage.TokenStream -> {
                val buf = tokenBuffers.getOrPut(msg.source) {
                    TokenStreamBuffer(msg.source, context = msg.context)
                }
                buf.tokens.append(msg.token)
                _streamingText.value = _streamingText.value + (msg.source to buf.tokens.toString())

                if (msg.done) {
                    addProse(ProseEntry(
                        speaker = msg.source,
                        text = buf.tokens.toString(),
                        priority = PriorityLevel.NORMAL,
                        isAiGenerated = true,
                    ))
                    tokenBuffers.remove(msg.source)
                    _streamingText.value = _streamingText.value - msg.source
                }
            }

            is S2CMessage.ReplayDone -> {
                addProse(ProseEntry(
                    speaker = "system",
                    text = "[Reconnected — replayed ${msg.count} messages]",
                    priority = PriorityLevel.NORMAL,
                ))
            }

            is S2CMessage.MapData -> { /* Handled by map UI if present */ }
            is S2CMessage.TopologyChanged -> { /* Handled by node dashboard */ }
            is S2CMessage.ZoneResponse -> { /* Handled by zone-command UI if present */ }
            is S2CMessage.VoiceAudio -> { /* Handled by audio playback layer if present */ }

            is S2CMessage.Transit -> {
                addProse(ProseEntry(
                    speaker = "system",
                    text = "[Transit] ${msg.message}",
                    priority = PriorityLevel.CRITICAL,
                ))
                // Clear local room state for the new zone
                _exits.value = emptyList()
                _entities.value = emptyList()
                _objects.value = emptyList()
                _hints.value = emptyList()
                _roomName.value = "Transiting..."
                _roomDescription.value = ""
                // Reconnect to the target zone
                val targetUrl = msg.targetUrl
                if (targetUrl != null) {
                    webSocket.disconnect()
                    webSocket.connect(targetUrl, msg.transitToken)
                }
            }
        }
    }

    private fun addProse(entry: ProseEntry) {
        _proseStream.value = _proseStream.value + entry
    }

    // --- User Actions ---

    /**
     * Parse free-text input and dispatch the appropriate C2S message.
     * Supports MUD-style commands: go/move, look, take/get/pick up, drop, use,
     * say, /inventory, /help, bare direction words, and Japanese direction kanji.
     */
    fun processInput(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val lower = trimmed.lowercase()

        // Slash commands: /<command> [args...] → send as Command
        if (lower.startsWith("/")) {
            val parts = trimmed.substring(1).split(Regex("\\s+"), limit = 2)
            val cmd = parts[0].lowercase()
            val args = if (parts.size > 1) parts[1].split(Regex("\\s+")) else emptyList()
            sendCommand(cmd, args)
            return
        }

        // look or l
        if (lower == "look" || lower == "l") {
            look()
            return
        }

        // go <direction> or move <direction>
        val goRegex = Regex("^(?:go|move)\\s+(.+)$")
        goRegex.find(lower)?.let { match ->
            go(resolveDirection(match.groupValues[1].trim()))
            return
        }

        // Bare direction word (check lowercase for English, original for Japanese)
        if (lower in BARE_DIRECTIONS || trimmed in BARE_DIRECTIONS) {
            val raw = if (lower in BARE_DIRECTIONS) lower else trimmed
            go(resolveDirection(raw))
            return
        }

        // take/get <object> or pick up <object>
        val takeRegex = Regex("^(?:take|get)\\s+(.+)$")
        val pickUpRegex = Regex("^pick\\s+up\\s+(.+)$")
        (takeRegex.find(lower) ?: pickUpRegex.find(lower))?.let { match ->
            take(match.groupValues[1].trim())
            return
        }

        // drop <object>
        val dropRegex = Regex("^drop\\s+(.+)$")
        dropRegex.find(lower)?.let { match ->
            drop(match.groupValues[1].trim())
            return
        }

        // use <object>
        val useRegex = Regex("^use\\s+(.+)$")
        useRegex.find(lower)?.let { match ->
            use(match.groupValues[1].trim())
            return
        }

        // Say shorthands: ' or "
        if (trimmed.startsWith("'") || trimmed.startsWith("\"")) {
            say(trimmed.substring(1))
            return
        }

        // Emote shorthands: : or ;
        if (trimmed.startsWith(":") || trimmed.startsWith(";")) {
            say(trimmed)  // server parses the : prefix
            return
        }

        // Tell shorthand: >name text
        if (trimmed.startsWith(">")) {
            say(trimmed)  // server parses the > prefix
            return
        }

        // Full word commands
        if (lower.startsWith("emote ")) { say(":" + trimmed.substring(6)); return }
        if (lower.startsWith("tell ")) { say(">" + trimmed.substring(5)); return }
        if (lower.startsWith("whisper ")) { say("whisper " + trimmed.substring(8)); return }

        // Explicit say: say <text> or "<text>" (use original case for the text)
        val sayRegex = Regex("^say\\s+(.+)$", RegexOption.IGNORE_CASE)
        val quotedRegex = Regex("^\"(.+)\"$")
        (sayRegex.find(trimmed) ?: quotedRegex.find(trimmed))?.let { match ->
            say(match.groupValues[1])
            return
        }

        // Default: send as Say — room scripts parse verbs from speech
        // (equip, doff, consume, craft, assess, collaborate, etc.)
        say(trimmed)
    }

    fun say(text: String) {
        scope.launch {
            webSocket.send(C2SMessage.Say(
                id = newId(),
                roomId = _currentRoomId.value,
                text = text,
            ))
        }
    }

    fun go(direction: String) {
        scope.launch {
            webSocket.send(C2SMessage.Go(
                id = newId(),
                roomId = _currentRoomId.value,
                direction = direction,
            ))
        }
    }

    fun look() {
        scope.launch {
            webSocket.send(C2SMessage.Look(
                id = newId(),
                roomId = _currentRoomId.value,
            ))
        }
    }

    fun take(objectName: String) {
        scope.launch {
            webSocket.send(C2SMessage.Take(
                id = newId(),
                roomId = _currentRoomId.value,
                objectName = objectName,
            ))
        }
    }

    fun drop(objectName: String) {
        scope.launch {
            webSocket.send(C2SMessage.Drop(
                id = newId(),
                roomId = _currentRoomId.value,
                objectName = objectName,
            ))
        }
    }

    fun use(objectName: String, target: String? = null) {
        scope.launch {
            webSocket.send(C2SMessage.Use(
                id = newId(),
                roomId = _currentRoomId.value,
                objectName = objectName,
                target = target,
            ))
        }
    }

    fun selectHint(index: Int) {
        scope.launch {
            webSocket.send(C2SMessage.HintSelect(
                id = newId(),
                roomId = _currentRoomId.value,
                index = index,
            ))
        }
    }

    fun sendCommand(command: String, args: List<String> = emptyList(), payload: Map<String, String> = emptyMap()) {
        scope.launch {
            webSocket.send(C2SMessage.Command(
                id = newId(),
                command = command,
                args = args,
                payload = payload,
            ))
        }
    }

    fun setPreference(key: String, value: String) {
        scope.launch {
            webSocket.send(C2SMessage.SetPreference(
                id = newId(),
                key = key,
                value = value,
            ))
        }
    }

    private fun newId(): String = "msg-${Random.nextInt(100000, 999999)}"

    companion object {
        private val DIRECTION_ALIASES = mapOf(
            "n" to "north", "s" to "south", "e" to "east", "w" to "west",
            "ne" to "northeast", "nw" to "northwest", "se" to "southeast", "sw" to "southwest",
            "\u5317" to "north", "\u5357" to "south", "\u6771" to "east", "\u897F" to "west",
            "\u4E0A" to "up", "\u4E0B" to "down",
        )

        private val BARE_DIRECTIONS = setOf(
            "north", "south", "east", "west", "up", "down",
            "northeast", "northwest", "southeast", "southwest",
            "n", "s", "e", "w", "ne", "nw", "se", "sw",
            "\u5317", "\u5357", "\u6771", "\u897F", "\u4E0A", "\u4E0B",
        )

        private fun resolveDirection(raw: String): String {
            val lower = raw.lowercase()
            return DIRECTION_ALIASES[lower] ?: lower
        }
    }
}
