@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.wyrdsekai.app.engine.scripting

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.*
import org.wyrdsekai.app.engine.room.RoomState
import org.wyrdsekai.app.protocol.Hint
import platform.JavaScriptCore.JSContext

/**
 * iOS ScriptEngine using JavaScriptCore (system framework).
 *
 * Room scripts execute in a JSContext. State is injected as a JS object
 * before each hook call, and emissions are collected via a JS array that
 * Kotlin reads back after execution.
 *
 * Note: JIT is disabled on iOS (interpreter only), so scripts should be
 * kept lightweight. Foundation room scripts (Nexus, Terminal) are fine.
 */
actual class ScriptEngine actual constructor(private val roomId: String) {
    private val worldApi = ScriptWorldApi(roomId)
    private val context = JSContext()
    private var scriptLoaded = false

    init {
        setupWorldBridge()
    }

    actual fun loadScript(source: String) {
        context.evaluateScript(source)
        checkException()
        scriptLoaded = true
    }

    actual fun syncState(state: RoomState) {
        worldApi.syncState(state)
        val json = buildStateJson(state)
        context.evaluateScript("__world._state = $json;")
    }

    actual fun callHook(hookName: String, args: List<Any>): List<ScriptEmission> {
        if (!scriptLoaded) return emptyList()

        // Check if function exists
        val check = context.evaluateScript("typeof $hookName === 'function'")
        if (check?.toString() != "true") return emptyList()

        // Clear emissions
        context.evaluateScript("__world._emissions = [];")

        // Build argument string
        val argsStr = args.joinToString(", ") { arg ->
            when (arg) {
                is String -> "\"${escapeJs(arg)}\""
                is Number -> arg.toString()
                is Boolean -> arg.toString()
                else -> "\"${escapeJs(arg.toString())}\""
            }
        }

        context.evaluateScript("$hookName($argsStr);")
        checkException()

        // Read emissions back
        val emissionsJson = context.evaluateScript(
            "JSON.stringify(__world._emissions)"
        )?.toString() ?: return emptyList()

        return parseEmissions(emissionsJson)
    }

    actual fun callHints(): List<Hint>? {
        if (!scriptLoaded) return null

        val check = context.evaluateScript("typeof getHints === 'function'")
        if (check?.toString() != "true") return null

        val json = context.evaluateScript("JSON.stringify(getHints())")?.toString()
            ?: return null
        if (json == "undefined" || json == "null") return null

        return parseHints(json)
    }

    // ── Private ──────────────────────────────────────────────────────────

    /**
     * Sets up the `world` JS object that room scripts interact with.
     * State is injected as a plain JS object; emissions are collected in an array.
     */
    private fun setupWorldBridge() {
        context.evaluateScript("""
            var __world = {
                _state: { roomId: '', name: '', description: '', entities: [], objects: [], properties: {} },
                _emissions: [],
                getRoomId: function() { return __world._state.roomId; },
                getRoomName: function() { return __world._state.name; },
                getRoomDescription: function() { return __world._state.description; },
                getEntities: function() { return __world._state.entities; },
                getObjects: function() { return __world._state.objects; },
                getProperty: function(key) { return (__world._state.properties || {})[key]; },
                emit: function(eventType, data) {
                    var d = {};
                    if (data) {
                        for (var k in data) {
                            if (data.hasOwnProperty(k)) {
                                d[k] = String(data[k]);
                            }
                        }
                    }
                    __world._emissions.push({ eventType: eventType, data: d });
                },
                setProperty: function(key, value) {
                    __world.emit('property_changed', { key: key, value: String(value) });
                },
                _i18n: {},
                t: function(key) {
                    var tmpl = __world._i18n[key];
                    if (!tmpl) return key;
                    if (arguments.length <= 1) return tmpl;
                    var result = tmpl;
                    for (var i = 1; i < arguments.length; i++) {
                        result = result.replace("{" + (i - 1) + "}", String(arguments[i]));
                    }
                    return result;
                },
                random: function() { return Math.random(); },
                log: function(msg) { /* no-op on phone */ }
            };
            var world = __world;
        """.trimIndent())
    }

    /** Build a JSON object literal from RoomState for injection into JS. */
    private fun buildStateJson(state: RoomState): String {
        val entities = state.entities.values.joinToString(",") { e ->
            """{"id":"${escapeJs(e.id)}","name":"${escapeJs(e.name)}","type":"${escapeJs(e.type)}"}"""
        }
        val objects = state.objects.values.joinToString(",") { o ->
            """{"id":"${escapeJs(o.id)}","name":"${escapeJs(o.name)}","description":"${escapeJs(o.description)}","takeable":${o.takeable}}"""
        }
        val properties = state.properties.entries.joinToString(",") { (k, v) ->
            """"${escapeJs(k)}":"${escapeJs(v)}""""
        }
        return """{
            "roomId":"${escapeJs(state.roomId)}",
            "name":"${escapeJs(state.name)}",
            "description":"${escapeJs(state.description)}",
            "entities":[$entities],
            "objects":[$objects],
            "properties":{$properties}
        }"""
    }

    /** Parse emissions JSON array back into ScriptEmission list. */
    private fun parseEmissions(json: String): List<ScriptEmission> {
        if (json == "[]" || json.isBlank()) return emptyList()
        return try {
            val array = Json.parseToJsonElement(json).jsonArray
            array.mapNotNull { element ->
                val obj = element.jsonObject
                val eventType = obj["eventType"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val dataObj = obj["data"]?.jsonObject ?: return@mapNotNull null
                val data = dataObj.entries.associate { (k, v) ->
                    k to (v.jsonPrimitive.contentOrNull ?: "")
                }
                ScriptEmission(eventType, data)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Parse hints JSON array into Hint list. */
    private fun parseHints(json: String): List<Hint> {
        if (json == "[]" || json.isBlank()) return emptyList()
        return try {
            val array = Json.parseToJsonElement(json).jsonArray
            array.mapNotNull { element ->
                val obj = element.jsonObject
                val label = obj["label"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val intent = obj["intent"]?.jsonPrimitive?.content ?: ""
                val action = obj["action"]?.jsonPrimitive?.content ?: ""
                val labelKey = obj["labelKey"]?.jsonPrimitive?.contentOrNull
                Hint(label = label, intent = intent, action = action, labelKey = labelKey)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Escape a string for safe embedding in a JS string literal. */
    private fun escapeJs(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    /** Log any JS exception that occurred during evaluation. */
    private fun checkException() {
        val exception = context.exception
        if (exception != null) {
            println("[Script:$roomId] JS error: ${exception.toString()}")
            context.exception = null
        }
    }
}
