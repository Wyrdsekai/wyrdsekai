package org.wyrdsekai.app.engine.event

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.wyrdsekai.app.protocol.Hint

/**
 * Domain events that occur within rooms.
 * Port of common/src/main/java/org/wyrdsekai/common/event/WorldEvent.java.
 * Each event is tagged with roomId (sharding key) and timestamp.
 */
@Serializable
sealed class WorldEvent {
    abstract val roomId: String
    abstract val timestamp: Instant

    @Serializable
    @SerialName("room_created")
    data class RoomCreated(
        override val roomId: String,
        override val timestamp: Instant,
        val name: String,
        val description: String,
        val zone: String,
    ) : WorldEvent()

    @Serializable
    @SerialName("entity_entered")
    data class EntityEntered(
        override val roomId: String,
        override val timestamp: Instant,
        val entityId: String,
        val entityName: String,
        val entityType: String,
        val fromDirection: String,
    ) : WorldEvent()

    @Serializable
    @SerialName("entity_left")
    data class EntityLeft(
        override val roomId: String,
        override val timestamp: Instant,
        val entityId: String,
        val entityName: String,
        val direction: String,
    ) : WorldEvent()

    @Serializable
    @SerialName("said")
    data class Said(
        override val roomId: String,
        override val timestamp: Instant,
        val entityId: String,
        val entityName: String,
        val text: String,
    ) : WorldEvent()

    @Serializable
    @SerialName("object_taken")
    data class ObjectTaken(
        override val roomId: String,
        override val timestamp: Instant,
        val entityId: String,
        val objectId: String,
        val objectName: String,
    ) : WorldEvent()

    @Serializable
    @SerialName("object_dropped")
    data class ObjectDropped(
        override val roomId: String,
        override val timestamp: Instant,
        val entityId: String,
        val objectId: String,
        val objectName: String,
        val description: String,
        val takeable: Boolean,
    ) : WorldEvent()

    @Serializable
    @SerialName("object_used")
    data class ObjectUsed(
        override val roomId: String,
        override val timestamp: Instant,
        val entityId: String,
        val objectId: String,
        val objectName: String,
        val target: String?,
        val result: String?,
    ) : WorldEvent()

    @Serializable
    @SerialName("exit_opened")
    data class ExitOpened(
        override val roomId: String,
        override val timestamp: Instant,
        val direction: String,
        val targetRoom: String,
        val label: String,
    ) : WorldEvent()

    @Serializable
    @SerialName("exit_closed")
    data class ExitClosed(
        override val roomId: String,
        override val timestamp: Instant,
        val direction: String,
    ) : WorldEvent()

    @Serializable
    @SerialName("description_changed")
    data class DescriptionChanged(
        override val roomId: String,
        override val timestamp: Instant,
        val newDescription: String,
        val reason: String?,
    ) : WorldEvent()

    @Serializable
    @SerialName("hints_updated")
    data class HintsUpdated(
        override val roomId: String,
        override val timestamp: Instant,
        val hints: List<Hint>,
    ) : WorldEvent()

    @Serializable
    @SerialName("script_triggered")
    data class ScriptTriggered(
        override val roomId: String,
        override val timestamp: Instant,
        val scriptName: String,
        val trigger: String,
        val context: Map<String, String> = emptyMap(),
    ) : WorldEvent()

    @Serializable
    @SerialName("object_added")
    data class ObjectAdded(
        override val roomId: String,
        override val timestamp: Instant,
        val objectId: String,
        val objectName: String,
        val description: String,
        val takeable: Boolean,
    ) : WorldEvent()

    @Serializable
    @SerialName("property_changed")
    data class PropertyChanged(
        override val roomId: String,
        override val timestamp: Instant,
        val key: String,
        val oldValue: String?,
        val newValue: String?,
    ) : WorldEvent()

    @Serializable
    @SerialName("whispered")
    data class Whispered(
        override val roomId: String,
        override val timestamp: Instant,
        val entityId: String,
        val entityName: String,
        val targetEntityId: String,
        val text: String,
    ) : WorldEvent()

    @Serializable
    @SerialName("emoted")
    data class Emoted(
        override val roomId: String,
        override val timestamp: Instant,
        val entityId: String,
        val entityName: String,
        val text: String,
    ) : WorldEvent()
}
