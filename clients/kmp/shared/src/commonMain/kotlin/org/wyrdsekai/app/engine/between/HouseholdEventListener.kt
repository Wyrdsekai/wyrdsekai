package org.wyrdsekai.app.engine.between

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Subscribes to household-wide events on `between.{householdId}.events`.
 *
 * These are low-volume, significant events:
 * - New agent arrived in the household
 * - Agent departed or went dormant
 * - Steward announcements
 * - Safety alerts
 * - Configuration changes
 *
 */
@Serializable
sealed class HouseholdEvent {
    abstract val timestamp: Long

    @Serializable
    @SerialName("agent_arrived")
    data class AgentArrived(
        val agentDid: String,
        val agentName: String,
        override val timestamp: Long,
    ) : HouseholdEvent()

    @Serializable
    @SerialName("agent_departed")
    data class AgentDeparted(
        val agentDid: String,
        val agentName: String,
        val reason: String? = null,
        override val timestamp: Long,
    ) : HouseholdEvent()

    @Serializable
    @SerialName("steward_announcement")
    data class StewardAnnouncement(
        val stewardDid: String,
        val message: String,
        override val timestamp: Long,
    ) : HouseholdEvent()

    @Serializable
    @SerialName("safety_alert")
    data class SafetyAlert(
        val severity: String,
        val message: String,
        val sourceDid: String? = null,
        override val timestamp: Long,
    ) : HouseholdEvent()

    @Serializable
    @SerialName("config_changed")
    data class ConfigChanged(
        val key: String,
        val oldValue: String? = null,
        val newValue: String? = null,
        override val timestamp: Long,
    ) : HouseholdEvent()
}

class HouseholdEventListener(
    private val between: BetweenClient,
    private val householdId: String,
    private val onEvent: (HouseholdEvent) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var unsubscribe: (() -> Unit)? = null

    /** Start listening for household events. */
    fun startListening() {
        val subject = "between.$householdId.events"
        unsubscribe = between.subscribe(subject) { _, data ->
            try {
                val event = json.decodeFromString<HouseholdEvent>(data.decodeToString())
                onEvent(event)
            } catch (_: Exception) {
                // Malformed household event — skip
            }
        }
    }

    /** Stop listening for household events. */
    fun stopListening() {
        unsubscribe?.invoke()
        unsubscribe = null
    }
}
