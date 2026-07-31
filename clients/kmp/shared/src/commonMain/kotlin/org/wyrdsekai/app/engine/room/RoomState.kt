package org.wyrdsekai.app.engine.room

import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.protocol.*

/**
 * Mutable room state derived from event stream via apply().
 * Port of the event-folding logic in core/room/RoomActor.java.
 */
data class RoomState(
    val roomId: String,
    val name: String,
    val description: String,
    val zone: String,
    val exits: Map<String, Exit>,
    val entities: Map<String, Entity>,
    val objects: Map<String, RoomObject>,
    val hints: List<Hint>,
    val properties: Map<String, String>,
) {
    companion object {
        fun empty(roomId: String) = RoomState(
            roomId = roomId,
            name = "",
            description = "",
            zone = "",
            exits = emptyMap(),
            entities = emptyMap(),
            objects = emptyMap(),
            hints = emptyList(),
            properties = emptyMap(),
        )
    }

    /** Pure function: apply an event to produce new state. */
    fun apply(event: WorldEvent): RoomState = when (event) {
        is WorldEvent.RoomCreated -> copy(
            name = event.name,
            description = event.description,
            zone = event.zone,
        )
        is WorldEvent.EntityEntered -> copy(
            entities = entities + (event.entityId to Entity(
                id = event.entityId,
                name = event.entityName,
                type = event.entityType,
                description = "",
            ))
        )
        is WorldEvent.EntityLeft -> copy(entities = entities - event.entityId)
        is WorldEvent.ObjectTaken -> copy(objects = objects - event.objectId)
        is WorldEvent.ObjectDropped -> copy(
            objects = objects + (event.objectId to RoomObject(
                id = event.objectId,
                name = event.objectName,
                description = event.description,
                takeable = event.takeable,
            ))
        )
        is WorldEvent.ObjectAdded -> copy(
            objects = objects + (event.objectId to RoomObject(
                id = event.objectId,
                name = event.objectName,
                description = event.description,
                takeable = event.takeable,
            ))
        )
        is WorldEvent.ExitOpened -> copy(
            exits = exits + (event.direction to Exit(
                direction = event.direction,
                targetRoom = event.targetRoom,
                label = event.label,
            ))
        )
        is WorldEvent.ExitClosed -> copy(exits = exits - event.direction)
        is WorldEvent.DescriptionChanged -> copy(description = event.newDescription)
        is WorldEvent.HintsUpdated -> copy(hints = event.hints)
        is WorldEvent.PropertyChanged -> copy(
            properties = if (event.newValue == null) {
                properties - event.key
            } else {
                properties + (event.key to event.newValue)
            }
        )
        // Events that don't change room state
        is WorldEvent.Said,
        is WorldEvent.Emoted,
        is WorldEvent.ObjectUsed,
        is WorldEvent.ScriptTriggered,
        is WorldEvent.Whispered -> this
    }

    /** Convert to protocol snapshot for wire transmission. */
    fun toSnapshot(): RoomSnapshot = RoomSnapshot(
        roomId = roomId,
        name = name,
        description = description,
        zone = zone,
        exits = exits.values.toList(),
        entities = entities.values.toList(),
        objects = objects.values.toList(),
        hints = hints,
    )
}
