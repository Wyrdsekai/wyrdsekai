package org.wyrdsekai.app.engine.between

/**
 * Abstract transport for the Between network.
 * Implementations connect to NATS (via WebSocket) or other transports.
 *
 * Subject naming: "between.{zone}.{src}.{dst}.{layer}.{topic}"
 * For soul headlines: "between.household.{nodeId}.*.soul.headlines"
 *
 */
interface BetweenClient {
    val isConnected: Boolean

    /** Connect to the Between network. */
    suspend fun connect(url: String)

    /**
     * Provide NATS credentials for subsequent connects (e.g. the
     * relay_phone user from a wyrdphone:// invite).
     * Default no-op for transports without authentication.
     */
    fun setCredentials(user: String?, password: String?) {}

    /** Disconnect from the Between network. */
    suspend fun disconnect()

    /** Publish a message to a subject. */
    fun publish(subject: String, data: ByteArray)

    /** Subscribe to a subject. Returns unsubscribe function. */
    fun subscribe(subject: String, handler: (subject: String, data: ByteArray) -> Unit): () -> Unit
}

/**
 * In-memory Between client for testing.
 * Messages published are delivered synchronously to local subscribers.
 */
class InMemoryBetweenClient : BetweenClient {
    private var _connected = false
    override val isConnected: Boolean get() = _connected

    private val subscriptions = mutableListOf<Pair<String, (String, ByteArray) -> Unit>>()

    /** All messages published (for test assertions). */
    val published = mutableListOf<Pair<String, ByteArray>>()

    override suspend fun connect(url: String) {
        _connected = true
    }

    override suspend fun disconnect() {
        _connected = false
    }

    override fun publish(subject: String, data: ByteArray) {
        published.add(subject to data)
        for ((pattern, handler) in subscriptions) {
            if (subjectMatches(pattern, subject)) {
                handler(subject, data)
            }
        }
    }

    override fun subscribe(subject: String, handler: (String, ByteArray) -> Unit): () -> Unit {
        val entry = subject to handler
        subscriptions.add(entry)
        return { subscriptions.remove(entry) }
    }

    /** Simple wildcard matching: * matches one token, > matches remaining. */
    private fun subjectMatches(pattern: String, subject: String): Boolean {
        val pParts = pattern.split(".")
        val sParts = subject.split(".")
        for (i in pParts.indices) {
            if (pParts[i] == ">") return true
            if (i >= sParts.size) return false
            if (pParts[i] != "*" && pParts[i] != sParts[i]) return false
        }
        return pParts.size == sParts.size
    }
}
