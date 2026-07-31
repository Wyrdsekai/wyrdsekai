package org.wyrdsekai.app.engine.between

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.wyrdsekai.app.engine.soul.Headline
import org.wyrdsekai.app.engine.soul.HeadlineSyncClient

/**
 * HeadlineSyncClient backed by the Between network.
 * Posts headlines to NATS and receives headlines from siblings.
 *
 * Subject: "between.household.{nodeId}.*.soul.headlines"
 *
 * Falls back to local-only mode if Between is not connected.
 */
class BetweenHeadlineSyncClient(
    private val between: BetweenClient,
    private val nodeId: String,
    private val familyId: String,
) : HeadlineSyncClient {

    private val headlines = mutableMapOf<String, Headline>()
    private val listeners = mutableListOf<(Headline) -> Unit>()
    private var unsubscribe: (() -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true }

    /** Start listening for headlines from the Between network. */
    fun startListening() {
        val subject = headlineSubject("*")
        unsubscribe = between.subscribe(subject) { _, data ->
            try {
                val headline = json.decodeFromString<Headline>(data.decodeToString())
                // Don't echo our own headlines back
                if (headline.budDid != nodeId) {
                    receiveHeadline(headline)
                }
            } catch (_: Exception) {
                // Malformed headline — skip
            }
        }
    }

    /** Stop listening for headlines. */
    fun stopListening() {
        unsubscribe?.invoke()
        unsubscribe = null
    }

    override suspend fun postHeadline(headline: Headline) {
        headlines[headline.budDid] = headline

        // Publish to Between if connected
        if (between.isConnected) {
            try {
                val data = json.encodeToString(headline).encodeToByteArray()
                between.publish(headlineSubject(nodeId), data)
            } catch (_: Exception) {
                // Publish failure is non-fatal — headline is still cached locally
            }
        }

        // Notify local listeners
        listeners.forEach { it(headline) }
    }

    override fun latestHeadlines(): List<Headline> =
        headlines.values.sortedByDescending { it.timestamp }

    override fun onHeadlineReceived(callback: (Headline) -> Unit) {
        listeners.add(callback)
    }

    private fun receiveHeadline(headline: Headline) {
        headlines[headline.budDid] = headline
        listeners.forEach { it(headline) }
    }

    private fun headlineSubject(src: String): String =
        "between.household.$familyId.$src.soul.headlines"
}
