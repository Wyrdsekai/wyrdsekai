package org.wyrdsekai.app.engine.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.wyrdsekai.app.platform.epochMillis
import kotlin.time.Clock
import org.wyrdsekai.app.platform.AppFiles
import kotlin.random.Random

/**
 * Queues complex inference requests when the household is unreachable.
 * Persists to a JSON file so requests survive app restarts.
 * Max 50 requests (oldest dropped if exceeded).
 */
class OfflineQueue(private val dataDir: String) {

    @Serializable
    data class PendingRequest(
        val triggerId: String,
        val triggerText: String,
        val triggerEntityName: String,
        val roomId: String,
        val timestamp: Long,
        val retryCount: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private var cache: MutableList<PendingRequest>? = null

    suspend fun enqueue(triggerText: String, triggerEntityName: String, roomId: String) {
        val list = loadOrInit()
        val request = PendingRequest(
            triggerId = "${epochMillis()}-${Random.nextInt(10000)}",
            triggerText = triggerText,
            triggerEntityName = triggerEntityName,
            roomId = roomId,
            timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
        )
        list.add(request)
        // Cap at 50 — drop oldest
        while (list.size > 50) list.removeFirst()
        save(list)
    }

    suspend fun pending(): List<PendingRequest> = loadOrInit().toList()

    suspend fun complete(triggerId: String) {
        val list = loadOrInit()
        list.removeAll { it.triggerId == triggerId }
        save(list)
    }

    suspend fun size(): Int = loadOrInit().size

    suspend fun clear() {
        cache = mutableListOf()
        save(cache!!)
    }

    private fun loadOrInit(): MutableList<PendingRequest> {
        cache?.let { return it }
        val loaded = AppFiles.readText("$dataDir/offline-queue.json")?.let { text ->
            try {
                json.decodeFromString<List<PendingRequest>>(text).toMutableList()
            } catch (_: Exception) {
                mutableListOf<PendingRequest>()
            }
        } ?: mutableListOf()
        cache = loaded
        return loaded
    }

    private fun save(list: List<PendingRequest>) {
        try {
            AppFiles.writeTextAtomic("$dataDir/offline-queue.json", json.encodeToString(list))
        } catch (_: Exception) {
            // Non-fatal — queue is also in memory
        }
    }
}
