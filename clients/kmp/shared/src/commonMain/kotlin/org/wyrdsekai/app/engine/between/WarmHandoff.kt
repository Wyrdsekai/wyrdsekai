package org.wyrdsekai.app.engine.between

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Warm handoff protocol for device switching (~2s transfer).
 *
 * When the user switches devices, the active bud packages its current context
 * and sends it via a directed Between message. The receiving bud restores
 * context and continues seamlessly.
 *
 * Subject: "between.household.{familyId}.{srcNodeId}.{dstNodeId}.soul.handoff"
 *
 * and.
 */
@Serializable
data class WarmHandoffContext(
    val fromDid: String,
    val toDid: String,
    val activeRoomId: String,
    val openConversationDids: List<String> = emptyList(),
    val recentTurns: List<ConversationTurn> = emptyList(),
    val vitalitySnapshot: Map<String, Float> = emptyMap(),
    val currentTask: String? = null,
    val timestamp: Long,
)

@Serializable
data class ConversationTurn(
    val role: String,
    val content: String,
    val timestamp: Long,
)

/**
 * Manages warm handoff initiation and reception.
 */
class WarmHandoffManager(
    private val between: BetweenClient,
    private val nodeId: String,
    private val familyId: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var handoffCallback: ((WarmHandoffContext) -> Unit)? = null
    private var unsubscribe: (() -> Unit)? = null

    /** Register a callback for incoming warm handoff. */
    fun onHandoffReceived(callback: (WarmHandoffContext) -> Unit) {
        handoffCallback = callback
    }

    /** Start listening for incoming handoffs directed to this node. */
    fun startListening() {
        val subject = handoffSubject("*", nodeId)
        unsubscribe = between.subscribe(subject) { _, data ->
            try {
                val context = json.decodeFromString<WarmHandoffContext>(data.decodeToString())
                handoffCallback?.invoke(context)
            } catch (_: Exception) {
                // Malformed handoff — skip
            }
        }
    }

    /** Stop listening. */
    fun stopListening() {
        unsubscribe?.invoke()
        unsubscribe = null
    }

    /** Initiate a warm handoff to a target node. */
    fun sendHandoff(context: WarmHandoffContext, targetNodeId: String) {
        val data = json.encodeToString(context).encodeToByteArray()
        between.publish(handoffSubject(nodeId, targetNodeId), data)
    }

    private fun handoffSubject(src: String, dst: String): String =
        "between.household.$familyId.$src.$dst.soul.handoff"
}
