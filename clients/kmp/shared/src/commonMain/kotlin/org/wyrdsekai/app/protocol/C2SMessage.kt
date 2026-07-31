package org.wyrdsekai.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Client → Server WebSocket messages.
 * Mirrors the Java sealed interface in common/protocol/C2SMessage.java.
 */
@Serializable
sealed interface C2SMessage {
    val id: String

    @Serializable
    @SerialName("say")
    data class Say(
        override val id: String,
        val roomId: String,
        val text: String,
        val voice: Boolean? = null,
    ) : C2SMessage

    @Serializable
    @SerialName("go")
    data class Go(
        override val id: String,
        val roomId: String,
        val direction: String,
    ) : C2SMessage

    @Serializable
    @SerialName("take")
    data class Take(
        override val id: String,
        val roomId: String,
        val objectName: String,
    ) : C2SMessage

    @Serializable
    @SerialName("drop")
    data class Drop(
        override val id: String,
        val roomId: String,
        val objectName: String,
    ) : C2SMessage

    @Serializable
    @SerialName("use")
    data class Use(
        override val id: String,
        val roomId: String,
        val objectName: String,
        val target: String? = null,
    ) : C2SMessage

    @Serializable
    @SerialName("examine")
    data class Examine(
        override val id: String,
        val roomId: String,
        val target: String,
    ) : C2SMessage

    @Serializable
    @SerialName("look")
    data class Look(
        override val id: String,
        val roomId: String,
    ) : C2SMessage

    @Serializable
    @SerialName("hint_select")
    data class HintSelect(
        override val id: String,
        val roomId: String,
        val index: Int,
    ) : C2SMessage

    @Serializable
    @SerialName("reconnect")
    data class Reconnect(
        override val id: String,
        val roomId: String,
        val lastSeenSeq: Long,
    ) : C2SMessage

    @Serializable
    @SerialName("command")
    data class Command(
        override val id: String,
        val command: String,
        val args: List<String> = emptyList(),
        val payload: Map<String, String> = emptyMap(),
    ) : C2SMessage

    @Serializable
    @SerialName("set_preference")
    data class SetPreference(
        override val id: String,
        val key: String,
        val value: String,
    ) : C2SMessage

    @Serializable
    @SerialName("map_request")
    data class MapRequest(
        override val id: String,
        val command: String,
        val radius: Int = 2,
        val target: String? = null,
    ) : C2SMessage

    @Serializable
    @SerialName("voice_audio")
    data class VoiceAudio(
        override val id: String,
        val audioBase64: String,
        val format: String,
    ) : C2SMessage

    @Serializable
    @SerialName("emote")
    data class Emote(
        override val id: String,
        val roomId: String,
        val text: String,
    ) : C2SMessage
}
