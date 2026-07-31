package org.wyrdsekai.app.engine.between

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Manages item exchange between companions via the Between network.
 *
 * Inbound items are quarantined (never go directly to inventory).
 * Quarantined items are reviewed during the Forge/sleep cycle.
 *
 * Subscribes to: `between.{householdId}.items.{myDid}.inbox`
 * Publishes to:  `between.{householdId}.items.{recipientDid}.inbox`
 *
 */
@Serializable
data class ItemTransfer(
    val fromDid: String,
    val toDid: String,
    val itemJson: JsonElement,
    val message: String? = null,
    val timestamp: Long,
    val signature: String = "",
)

class ItemExchangeManager(
    private val between: BetweenClient,
    private val myDid: String,
    private val householdId: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val quarantine = mutableListOf<ItemTransfer>()
    private var unsubscribe: (() -> Unit)? = null

    /** Start listening for inbound items on this agent's inbox. */
    fun startListening() {
        val subject = "between.$householdId.items.$myDid.inbox"
        unsubscribe = between.subscribe(subject) { _, data ->
            try {
                val transfer = json.decodeFromString<ItemTransfer>(data.decodeToString())
                // All inbound items go to quarantine, never directly to inventory
                quarantine.add(transfer)
            } catch (_: Exception) {
                // Malformed transfer — skip
            }
        }
    }

    /** Stop listening for inbound items. */
    fun stopListening() {
        unsubscribe?.invoke()
        unsubscribe = null
    }

    /**
     * Send an item to another agent.
     *
     * @param recipientDid The DID of the recipient agent
     * @param item The item to send (serialized as JsonElement)
     * @param message Optional gift message
     */
    fun sendItem(recipientDid: String, item: JsonElement, message: String? = null) {
        val transfer = ItemTransfer(
            fromDid = myDid,
            toDid = recipientDid,
            itemJson = item,
            message = message,
            timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
        )
        val data = json.encodeToString(transfer).encodeToByteArray()
        between.publish("between.$householdId.items.$recipientDid.inbox", data)
    }

    /** Get all quarantined inbound items (reviewed during Forge/sleep). */
    fun getQuarantinedItems(): List<ItemTransfer> = quarantine.toList()

    /** Clear quarantine after items have been reviewed by the Forge. */
    fun clearQuarantine() {
        quarantine.clear()
    }

    /** Remove a specific item from quarantine (accepted or rejected by Forge). */
    fun removeFromQuarantine(transfer: ItemTransfer) {
        quarantine.remove(transfer)
    }
}
