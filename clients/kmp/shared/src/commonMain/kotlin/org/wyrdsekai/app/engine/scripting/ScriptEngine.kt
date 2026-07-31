package org.wyrdsekai.app.engine.scripting

import org.wyrdsekai.app.engine.room.RoomState
import org.wyrdsekai.app.protocol.Hint

/**
 * Platform-specific JavaScript engine for room scripting.
 * iOS: JavaScriptCore, Desktop: GraalJS/Nashorn (stubbed), Android: stub.
 */
expect class ScriptEngine(roomId: String) {
    /** Load and evaluate a JavaScript source. */
    fun loadScript(source: String)

    /** Sync the world.* state so scripts see current room state. */
    fun syncState(state: RoomState)

    /** Call a named hook function. Returns any emissions from world.emit(). */
    fun callHook(hookName: String, args: List<Any> = emptyList()): List<ScriptEmission>

    /** Call getHints() to get script-defined hints. Returns null if function doesn't exist. */
    fun callHints(): List<Hint>?
}
