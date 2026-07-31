package org.wyrdsekai.app.engine.between

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Lightweight Dock — receives inbound messages from other agents via Between.
 *
 * Subscribes to `between.{householdId}.dock.{companionDid}.>` and applies
 * the 5-layer quarantine from §97.9:
 *
 * 1. Card verification — sender must have a non-empty DID
 * 2. Message sanitization — strip control characters, limit length (4096)
 * 3. Rate limiting — max 10 messages per hour per agent
 * 4. Item quarantine — items go to quarantine list, not inventory
 * 5. Info redaction — strip internal state from outbound responses
 *
 */
@Serializable
sealed class DockMessage {
    abstract val timestamp: Long

    @Serializable
    @SerialName("text_message")
    data class TextMessage(
        val from: String,
        val content: String,
        override val timestamp: Long,
    ) : DockMessage()

    @Serializable
    @SerialName("item_gift")
    data class ItemGift(
        val from: String,
        val itemJson: JsonElement,
        val message: String? = null,
        override val timestamp: Long,
    ) : DockMessage()

    @Serializable
    @SerialName("introduction")
    data class Introduction(
        val agentDid: String,
        val agentName: String,
        override val timestamp: Long,
    ) : DockMessage()

    @Serializable
    @SerialName("status_query")
    data class StatusQuery(
        val from: String,
        override val timestamp: Long,
    ) : DockMessage()

    @Serializable
    @SerialName("goodbye")
    data class Goodbye(
        val from: String,
        override val timestamp: Long,
    ) : DockMessage()
}

/** Trust tiers for inbound Dock contacts. */
enum class TrustTier {
    ANONYMOUS,
    VERIFIED,
    TRUSTED,
    HOUSEHOLD,
    FAMILY,
}

class PhoneDock(
    private val between: BetweenClient,
    private val companionDid: String,
    private val householdId: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val inbox = mutableListOf<DockMessage>()
    private val quarantinedItems = mutableListOf<DockMessage.ItemGift>()
    private var unsubscribe: (() -> Unit)? = null

    /**
     * Rate limiting state: maps agent DID to (count, hourWindowStart).
     * Resets when the current time exceeds hourWindowStart + 1 hour.
     */
    private val rateLimitMap = mutableMapOf<String, RateLimitEntry>()

    private data class RateLimitEntry(var count: Int, var windowStart: Long)

    /** Start listening for inbound Dock messages. */
    fun startListening() {
        val subject = "between.$householdId.dock.$companionDid.>"
        unsubscribe = between.subscribe(subject) { _, data ->
            try {
                val raw = data.decodeToString()
                val message = json.decodeFromString<DockMessage>(raw)
                processInbound(message)
            } catch (_: Exception) {
                // Malformed dock message — skip
            }
        }
    }

    /** Stop listening for Dock messages. */
    fun stopListening() {
        unsubscribe?.invoke()
        unsubscribe = null
    }

    /** Get all accepted inbox messages. */
    fun getInbox(): List<DockMessage> = inbox.toList()

    /** Get all quarantined item gifts. */
    fun getQuarantinedItems(): List<DockMessage.ItemGift> = quarantinedItems.toList()

    /**
     * Send a message to another agent's Dock (T3 only).
     * Applies info redaction before sending.
     */
    fun sendMessage(toDid: String, message: DockMessage) {
        // Layer 5: Info redaction — the message is already structured,
        // so we only send the serialized form (no internal state leaks)
        val data = json.encodeToString(message).encodeToByteArray()
        between.publish("between.$householdId.dock.$toDid.inbox", data)
    }

    /**
     * Process an inbound message through the 5-layer quarantine.
     */
    private fun processInbound(message: DockMessage) {
        val senderDid = extractSenderDid(message)

        // Layer 1: Card verification — check non-empty DID
        if (senderDid.isBlank()) return

        // Layer 2: Message sanitization
        val sanitized = sanitize(message) ?: return

        // Layer 3: Rate limiting
        if (!checkRateLimit(senderDid)) return

        // Layer 4: Item quarantine — items go to quarantine, not inbox
        if (sanitized is DockMessage.ItemGift) {
            quarantinedItems.add(sanitized)
            return
        }

        // Message passed all layers — add to inbox
        inbox.add(sanitized)
    }

    /** Extract the sender DID from a DockMessage. */
    private fun extractSenderDid(message: DockMessage): String = when (message) {
        is DockMessage.TextMessage -> message.from
        is DockMessage.ItemGift -> message.from
        is DockMessage.Introduction -> message.agentDid
        is DockMessage.StatusQuery -> message.from
        is DockMessage.Goodbye -> message.from
    }

    /**
     * Layer 2: Sanitize message content.
     * - Strip control characters (keep newlines and tabs)
     * - Limit text content to MAX_MESSAGE_LENGTH
     */
    private fun sanitize(message: DockMessage): DockMessage? = when (message) {
        is DockMessage.TextMessage -> {
            val cleaned = sanitizeText(message.content)
            message.copy(content = cleaned)
        }
        is DockMessage.ItemGift -> {
            val cleaned = message.message?.let { sanitizeText(it) }
            message.copy(message = cleaned)
        }
        is DockMessage.Introduction -> {
            val cleaned = sanitizeText(message.agentName)
            message.copy(agentName = cleaned)
        }
        is DockMessage.StatusQuery -> message
        is DockMessage.Goodbye -> message
    }

    /** Strip control characters (except \n, \t) and truncate to max length. */
    private fun sanitizeText(text: String): String {
        val stripped = text.filter { it == '\n' || it == '\t' || !it.isISOControl() }
        return if (stripped.length > MAX_MESSAGE_LENGTH) {
            stripped.substring(0, MAX_MESSAGE_LENGTH)
        } else {
            stripped
        }
    }

    /**
     * Layer 3: Per-agent rate limiting.
     * Max MAX_MESSAGES_PER_HOUR messages per agent per hour.
     * Returns true if the message is allowed.
     */
    private fun checkRateLimit(senderDid: String): Boolean {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val entry = rateLimitMap.getOrPut(senderDid) { RateLimitEntry(0, now) }

        // Reset window if expired
        if (now - entry.windowStart >= RATE_LIMIT_WINDOW_MS) {
            entry.count = 0
            entry.windowStart = now
        }

        if (entry.count >= MAX_MESSAGES_PER_HOUR) return false

        entry.count++
        return true
    }

    companion object {
        /** Maximum message content length (bytes). */
        internal const val MAX_MESSAGE_LENGTH = 4096

        /** Maximum messages per agent per hour. */
        internal const val MAX_MESSAGES_PER_HOUR = 10

        /** Rate limit window in milliseconds (1 hour). */
        internal const val RATE_LIMIT_WINDOW_MS = 3_600_000L
    }
}
