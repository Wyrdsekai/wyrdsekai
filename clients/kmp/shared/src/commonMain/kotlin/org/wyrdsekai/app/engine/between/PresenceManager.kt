package org.wyrdsekai.app.engine.between

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages presence state for the phone node within the household Between network.
 *
 * Publishes this node's presence to `between.{householdId}.presence.{nodeId}`
 * and subscribes to `between.{householdId}.presence.>` to track all agents.
 *
 */
@Serializable
data class PresenceState(
    val nodeId: String,
    val status: String,  // online, offline, sleeping, away
    val tier: String? = null,
    val timestamp: Long,
)

class PresenceManager(
    private val between: BetweenClient,
    private val nodeId: String,
    private val householdId: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val presenceMap = mutableMapOf<String, PresenceState>()
    private var unsubscribe: (() -> Unit)? = null

    /** Start listening for presence announcements from all household agents. */
    fun startListening() {
        val subject = "between.$householdId.presence.>"
        unsubscribe = between.subscribe(subject) { _, data ->
            try {
                val state = json.decodeFromString<PresenceState>(data.decodeToString())
                presenceMap[state.nodeId] = state
            } catch (_: Exception) {
                // Malformed presence — skip
            }
        }
    }

    /** Stop listening for presence announcements. */
    fun stopListening() {
        unsubscribe?.invoke()
        unsubscribe = null
    }

    /**
     * Announce this node's presence status.
     *
     * @param status One of: "online", "offline", "sleeping", "away"
     */
    fun announce(status: String) {
        val state = PresenceState(
            nodeId = nodeId,
            status = status,
            timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
        )
        presenceMap[nodeId] = state

        if (between.isConnected) {
            try {
                val data = json.encodeToString(state).encodeToByteArray()
                between.publish("between.$householdId.presence.$nodeId", data)
            } catch (_: Exception) {
                // Publish failure is non-fatal
            }
        }
    }

    /** Get the current presence state for all known household agents. */
    fun getHouseholdPresence(): Map<String, PresenceState> =
        presenceMap.toMap()
}
