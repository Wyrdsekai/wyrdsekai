package org.wyrdsekai.app.engine.scripting

/**
 * An emission from a room script's world.emit() call.
 * eventType maps to: "narrate", "description_changed", "hints_updated",
 * "property_changed", "object_added", "object_removed", etc.
 */
data class ScriptEmission(
    val eventType: String,
    val data: Map<String, String>,
)
