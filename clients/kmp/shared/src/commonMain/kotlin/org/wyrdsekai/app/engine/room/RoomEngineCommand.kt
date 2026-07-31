package org.wyrdsekai.app.engine.room

import org.wyrdsekai.app.protocol.Exit
import org.wyrdsekai.app.protocol.RoomObject

/**
 * Commands that can be sent to a RoomEngine.
 * Replaces Pekko's RoomCommand protocol messages.
 */
sealed class RoomEngineCommand {
    data class CreateRoom(
        val name: String,
        val description: String,
        val zone: String,
        val exits: List<Exit> = emptyList(),
        val objects: List<RoomObject> = emptyList(),
    ) : RoomEngineCommand()

    data class EnterRoom(
        val entityId: String,
        val entityName: String,
        val entityType: String,
        val fromDirection: String,
    ) : RoomEngineCommand()

    data class LeaveRoom(
        val entityId: String,
        val entityName: String,
        val direction: String,
    ) : RoomEngineCommand()

    data class SayInRoom(
        val entityId: String,
        val entityName: String,
        val text: String,
    ) : RoomEngineCommand()

    data class TakeObject(
        val entityId: String,
        val objectName: String,
    ) : RoomEngineCommand()

    data class DropObject(
        val entityId: String,
        val objectName: String,
        val objectId: String,
        val description: String,
        val takeable: Boolean,
    ) : RoomEngineCommand()

    data class UseObject(
        val entityId: String,
        val objectName: String,
        val target: String?,
    ) : RoomEngineCommand()

    data class SelectHint(
        val entityId: String,
        val index: Int,
    ) : RoomEngineCommand()

    data class EmoteInRoom(
        val entityId: String,
        val entityName: String,
        val text: String,
    ) : RoomEngineCommand()

    /** Set a room property (e.g. from onboarding flow). */
    data class SetProperty(
        val key: String,
        val value: String,
    ) : RoomEngineCommand()
}
