package org.wyrdsekai.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Server → Client WebSocket messages.
 * Mirrors the Java sealed interface in common/protocol/S2CMessage.java.
 */
@Serializable
sealed interface S2CMessage {
    val seq: Long

    @Serializable
    @SerialName("room_state")
    data class RoomState(
        override val seq: Long,
        val room: RoomSnapshot,
        val inventory: List<RoomObject>? = null,
    ) : S2CMessage

    @Serializable
    @SerialName("prose")
    data class Prose(
        override val seq: Long,
        val speaker: String,
        val text: String,
        val hints: List<Hint> = emptyList(),
        val structured: Structured? = null,
        val priority: String = "normal",
        val lang: String? = null,
        val isAiGenerated: Boolean = false,
        val blocks: List<ContentBlock> = emptyList(),
        val voice: Boolean? = null,
        val style: String? = null,
    ) : S2CMessage

    @Serializable
    @SerialName("agent_action")
    data class AgentAction(
        override val seq: Long,
        val agentName: String,
        val action: String,
        val description: String,
    ) : S2CMessage

    @Serializable
    @SerialName("state_change")
    data class StateChange(
        override val seq: Long,
        val description: String,
        val structured: Structured? = null,
        val blocks: List<ContentBlock> = emptyList(),
    ) : S2CMessage

    @Serializable
    @SerialName("replay_done")
    data class ReplayDone(
        override val seq: Long,
        val fromSeq: Long,
        val toSeq: Long,
        val count: Int,
    ) : S2CMessage

    @Serializable
    @SerialName("error")
    data class Error(
        override val seq: Long,
        val code: String,
        val message: String,
        val requestId: String? = null,
    ) : S2CMessage

    @Serializable
    @SerialName("notification")
    data class Notification(
        override val seq: Long,
        val level: String,
        val title: String,
        val message: String,
    ) : S2CMessage

    @Serializable
    @SerialName("transit")
    data class Transit(
        override val seq: Long,
        val targetZoneId: String,
        val targetUrl: String? = null,
        val transitToken: String? = null,
        val message: String,
    ) : S2CMessage

    @Serializable
    @SerialName("token_stream")
    data class TokenStream(
        override val seq: Long,
        val source: String,
        val token: String,
        val done: Boolean,
        val context: String? = null,
    ) : S2CMessage

    @Serializable
    @SerialName("topology_changed")
    data class TopologyChanged(
        override val seq: Long,
        val changeType: String,
        val roomId: String,
        val direction: String? = null,
        val targetRoomId: String? = null,
        val description: String,
    ) : S2CMessage

    @Serializable
    @SerialName("map_data")
    data class MapData(
        override val seq: Long,
        val command: String,
        val textMap: String,
        val topology: TopologySnapshot? = null,
        val path: List<String>? = null,
    ) : S2CMessage

    @Serializable
    @SerialName("zone_response")
    data class ZoneResponse(
        override val seq: Long,
        val requestId: String,
        val namespace: String,
        val text: String,
        val data: JsonElement? = null,
        val blocks: List<ContentBlock> = emptyList(),
    ) : S2CMessage

    @Serializable
    @SerialName("voice_audio")
    data class VoiceAudio(
        override val seq: Long,
        val audioBase64: String,
        val format: String,
        val speaker: String,
    ) : S2CMessage
}
