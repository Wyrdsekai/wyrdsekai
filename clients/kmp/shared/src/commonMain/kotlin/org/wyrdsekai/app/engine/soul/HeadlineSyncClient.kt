package org.wyrdsekai.app.engine.soul

import kotlinx.serialization.Serializable

/**
 * Headline — a lightweight status broadcast between buds of the same lineage.
 *
 * Headlines are the continuous (~200B) sync mechanism from §95 (Soul Budding).
 * Each bud periodically posts a headline summarizing its state; siblings consume
 * these to maintain awareness of the family without full sync.
 *
 * Human sees one companion; buds coordinate via headlines + warm handoff.
 */
@Serializable
data class Headline(
    val budDid: String,
    val summary: String,
    val vitalitySnapshot: Map<String, Float>,
    val itemCount: Int,
    val timestamp: Long,
)

/**
 * Client-side interface for bud headline synchronization.
 *
 * Posting and receiving will be wired to the Between (WebSocket/NATS)
 * once that transport layer is integrated. Until then, the in-memory
 * implementation serves for local testing and single-device use.
 */
interface HeadlineSyncClient {
    /** Post this bud's current headline to the family. */
    suspend fun postHeadline(headline: Headline)

    /** Return the latest cached headlines from all family buds. */
    fun latestHeadlines(): List<Headline>

    /** Register a callback invoked when a headline arrives from a sibling. */
    fun onHeadlineReceived(callback: (Headline) -> Unit)
}

/**
 * In-memory headline sync — caches headlines locally, no network.
 * Useful for single-bud testing and phone-only mode.
 */
class InMemoryHeadlineSyncClient : HeadlineSyncClient {
    private val headlines = mutableMapOf<String, Headline>()
    private val listeners = mutableListOf<(Headline) -> Unit>()

    override suspend fun postHeadline(headline: Headline) {
        headlines[headline.budDid] = headline
        listeners.forEach { it(headline) }
    }

    override fun latestHeadlines(): List<Headline> = headlines.values.toList()

    override fun onHeadlineReceived(callback: (Headline) -> Unit) {
        listeners.add(callback)
    }
}
