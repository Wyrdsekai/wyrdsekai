package org.wyrdsekai.app.engine.between

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Sleep sync protocol — full manifest + fragment exchange during rest.
 *
 * The three-tier sync from §95 (Soul Budding):
 * - Tier 1: Headlines (~200B, continuous) — handled by BetweenHeadlineSyncClient
 * - Tier 2: Warm Handoff (~2s, device switch) — handled by WarmHandoffManager
 * - Tier 3: Sleep Sync (full, on rest cycle) — THIS
 *
 * Sleep sync pushes local items to the Family Locker and pulls new items
 * from siblings. Triggered by the Dream Chamber / Forge cycle.
 *
 * Subject: "between.household.{familyId}.{nodeId}.*.soul.sync"
 *
 * and.
 */
@Serializable
data class SleepSyncRequest(
    val budDid: String,
    val nodeId: String,
    val manifestVersion: Int,
    val localItemHashes: List<String>,
    val localTombstones: List<Tombstone> = emptyList(),
    val lastSyncTimestamp: Long,
    val timestamp: Long,
)

@Serializable
data class SleepSyncResponse(
    val budDid: String,
    val newItems: List<SoulItemRef> = emptyList(),
    val newTombstones: List<Tombstone> = emptyList(),
    val manifestUpdated: Boolean = false,
    val itemsMerged: Int = 0,
    val tombstonesApplied: Int = 0,
    val timestamp: Long,
)

@Serializable
data class Tombstone(
    val itemHash: String,
    val reason: String,
    val createdBy: String,
    val timestamp: Long,
)

@Serializable
data class SoulItemRef(
    val hash: String,
    val category: String,
    val significance: Float,
    val createdBy: String,
    val timestamp: Long,
)

/**
 * Manages sleep sync initiation and response handling.
 */
class SleepSyncManager(
    private val between: BetweenClient,
    private val nodeId: String,
    private val familyId: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var syncResponseCallback: ((SleepSyncResponse) -> Unit)? = null
    private var unsubscribe: (() -> Unit)? = null

    /** Register a callback for sync responses. */
    fun onSyncResponse(callback: (SleepSyncResponse) -> Unit) {
        syncResponseCallback = callback
    }

    /** Start listening for sync responses directed to this node. */
    fun startListening() {
        val subject = syncResponseSubject(nodeId)
        unsubscribe = between.subscribe(subject) { _, data ->
            try {
                val response = json.decodeFromString<SleepSyncResponse>(data.decodeToString())
                syncResponseCallback?.invoke(response)
            } catch (_: Exception) {
                // Malformed response — skip
            }
        }
    }

    /** Stop listening. */
    fun stopListening() {
        unsubscribe?.invoke()
        unsubscribe = null
    }

    /**
     * Initiate a sleep sync — send local state to the household server.
     * The server processes via FamilyLocker and responds with new items.
     */
    fun requestSync(request: SleepSyncRequest) {
        val data = json.encodeToString(request).encodeToByteArray()
        between.publish(syncRequestSubject(nodeId), data)
    }

    /**
     * Build a sync request from current state.
     */
    fun buildRequest(
        budDid: String,
        manifestVersion: Int,
        localItemHashes: List<String>,
        localTombstones: List<Tombstone>,
        lastSyncTimestamp: Long,
    ): SleepSyncRequest = SleepSyncRequest(
        budDid = budDid,
        nodeId = nodeId,
        manifestVersion = manifestVersion,
        localItemHashes = localItemHashes,
        localTombstones = localTombstones,
        lastSyncTimestamp = lastSyncTimestamp,
        timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
    )

    private fun syncRequestSubject(src: String): String =
        "between.household.$familyId.$src.server.soul.sync.request"

    private fun syncResponseSubject(dst: String): String =
        "between.household.$familyId.server.$dst.soul.sync.response"
}
