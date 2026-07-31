package org.wyrdsekai.app.engine.room

import org.wyrdsekai.app.protocol.RoomSnapshot

/** Responses from a RoomEngine command. */
sealed class RoomEngineResponse {
    data class Ok(val snapshot: RoomSnapshot) : RoomEngineResponse()
    data class Rejected(val code: String, val reason: String) : RoomEngineResponse()
}
