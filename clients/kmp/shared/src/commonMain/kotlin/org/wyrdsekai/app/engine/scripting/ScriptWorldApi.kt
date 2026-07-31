package org.wyrdsekai.app.engine.scripting

import org.wyrdsekai.app.engine.room.RoomState
import org.wyrdsekai.app.protocol.Entity
import org.wyrdsekai.app.protocol.RoomObject
import kotlin.random.Random

/**
 * Common implementation of the world.* API surface that room scripts see.
 * Platform-specific ScriptEngine implementations delegate to this for state management.
 * This is the phone-class subset — no admin-gated methods (bridge, library, federation).
 */
class ScriptWorldApi(private val roomId: String) {
    private var state: RoomState = RoomState.empty(roomId)
    private val _emissions = mutableListOf<ScriptEmission>()

    val emissions: List<ScriptEmission> get() = _emissions.toList()

    fun clearEmissions() { _emissions.clear() }

    fun syncState(newState: RoomState) { state = newState }

    // --- Read-only state queries (available to all scripts) ---

    fun getRoomId(): String = state.roomId
    fun getRoomName(): String = state.name
    fun getRoomDescription(): String = state.description

    fun getEntities(): List<Map<String, String>> = state.entities.values.map { e ->
        mapOf("id" to e.id, "name" to e.name, "type" to e.type)
    }

    fun getObjects(): List<Map<String, Any>> = state.objects.values.map { o ->
        mapOf("id" to o.id, "name" to o.name, "description" to o.description, "takeable" to o.takeable)
    }

    fun getProperty(key: String): String? = state.properties[key]

    // --- Write operations (emit events back to room engine) ---

    fun setProperty(key: String, value: String) {
        emit("property_changed", mapOf("key" to key, "value" to value))
    }

    fun emit(eventType: String, data: Map<String, String>) {
        _emissions.add(ScriptEmission(eventType, data))
    }

    /**
     * i18n translation lookup. Checks the bundled [translations] map first,
     * then falls back to returning the key itself. Supports {0}, {1} placeholders.
     * On server, RoomScriptEngine provides the real ScriptMessageCatalog;
     * on phone, we bundle English defaults and can load locale-specific maps later.
     */
    private val translations = mutableMapOf<String, String>()

    /** Load a batch of translations (e.g. from bundled English defaults or a locale file). */
    fun loadTranslations(entries: Map<String, String>) {
        translations.putAll(entries)
    }

    fun t(key: String, vararg args: Any): String {
        val template = translations[key] ?: return key
        if (args.isEmpty()) return template
        var result = template
        for (i in args.indices) {
            result = result.replace("{$i}", args[i].toString())
        }
        return result
    }

    fun random(): Double = Random.nextDouble()

    fun log(message: String) {
        println("[Script:$roomId] $message")
    }
}
