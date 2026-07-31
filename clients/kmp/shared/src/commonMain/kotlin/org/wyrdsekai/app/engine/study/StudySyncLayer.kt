package org.wyrdsekai.app.engine.study

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.wyrdsekai.app.engine.between.BetweenClient

/**
 * Between sync layer for Study items.
 *
 * Follows the advertisement → delta request → delta response protocol
 * from.
 *
 * Subject pattern:
 *   between.{householdId}.{deviceId}.*.study.state     — broadcast state summary
 *   between.{householdId}.{deviceId}.*.study.sync      — delta exchange
 *
 * Sync flow:
 * 1. Each device periodically broadcasts a StudyStateSummary
 * 2. Peers compare clock summaries; if behind, send delta request
 * 3. Source responds with items newer than the requested clock
 * 4. Receiver merges using vector clock comparison
 */
class StudySyncLayer(
    private val between: BetweenClient,
    private val store: StudyStore,
    private val deviceId: String,
    private val householdId: String,
    private val userDid: String,
    private val scope: CoroutineScope,
    /** Session (mcp.login) or device pairing token proving we speak for userDid —
     *  the server peer drops unauthenticated study messages. */
    private val authToken: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var unsubState: (() -> Unit)? = null
    private var unsubSync: (() -> Unit)? = null

    private val listeners = mutableListOf<(SyncEvent) -> Unit>()

    /** Start listening for sync messages from peers. */
    fun startListening() {
        // Listen for state advertisements from all peers
        unsubState = between.subscribe(stateSubject("*")) { _, data ->
            try {
                val msg = json.decodeFromString<StudySyncMessage>(data.decodeToString())
                if (msg.deviceId != deviceId) scope.launch { handlePeerMessage(msg) }
            } catch (_: Exception) {}
        }

        // Listen for directed sync messages to this device
        unsubSync = between.subscribe(syncSubject("*", deviceId)) { _, data ->
            try {
                val msg = json.decodeFromString<StudySyncMessage>(data.decodeToString())
                if (msg.deviceId != deviceId) scope.launch { handlePeerMessage(msg) }
            } catch (_: Exception) {}
        }
    }

    fun stopListening() {
        unsubState?.invoke()
        unsubSync?.invoke()
        unsubState = null
        unsubSync = null
    }

    fun onSyncEvent(callback: (SyncEvent) -> Unit) {
        listeners.add(callback)
    }

    /** Broadcast our current state summary to all peers. */
    suspend fun broadcastState() {
        if (!between.isConnected) return
        val count = store.count(userDid)
        val recent = store.recentJournal(userDid, limit = 1)
        val latestTs = recent.firstOrNull()?.timestamp ?: 0L

        // Build clock summary from all items
        val clockSummary = buildClockSummary()

        val msg = StudySyncMessage(
            type = "study_state",
            deviceId = deviceId,
            userDid = userDid,
            token = authToken ?: "",
            itemCount = count,
            latestModified = latestTs,
            clockSummary = clockSummary,
        )
        try {
            between.publish(stateSubject(deviceId), json.encodeToString(msg).encodeToByteArray())
        } catch (_: Exception) {}
    }

    /** Request deltas from a peer since our last known clock for them. */
    suspend fun requestDelta(peerDeviceId: String) {
        if (!between.isConnected) return
        val clockSummary = buildClockSummary()
        val msg = StudySyncMessage(
            type = "study_delta_request",
            deviceId = deviceId,
            userDid = userDid,
            token = authToken ?: "",
            clockSummary = clockSummary,
        )
        try {
            between.publish(syncSubject(deviceId, peerDeviceId), json.encodeToString(msg).encodeToByteArray())
        } catch (_: Exception) {}
    }

    /** Handle an incoming message from a peer. */
    private suspend fun handlePeerMessage(msg: StudySyncMessage) {
        // Scope by owner — a household can hold more than one user; ignore traffic
        // for anyone but us. (Empty userDid = a legacy peer; allow it.)
        if (msg.userDid.isNotEmpty() && msg.userDid != userDid) return
        when (msg.type) {
            "study_state" -> {
                // Compare clocks — if peer has newer items, request delta
                val ourClock = buildClockSummary()
                val theirClock = msg.clockSummary
                if (theirClock.any { (k, v) -> (ourClock[k] ?: 0L) < v }) {
                    requestDelta(msg.deviceId)
                }
            }
            "study_delta_request" -> {
                // Peer wants items newer than their clock
                sendDelta(msg.deviceId, msg.clockSummary)
            }
            "study_delta" -> {
                // Merge incoming items
                val merged = mergeIncoming(msg.items)
                if (merged > 0) {
                    listeners.forEach { it(SyncEvent.ItemsMerged(merged)) }
                }
                if (msg.conflicts > 0) {
                    listeners.forEach { it(SyncEvent.ConflictsDetected(msg.conflicts)) }
                }
            }
        }
    }

    /** Send items newer than the peer's clock. */
    private suspend fun sendDelta(peerDeviceId: String, peerClock: Map<String, Long>) {
        if (!between.isConnected) return
        // Get all items and filter to those newer than peer's clock
        val all = store.recentJournal(userDid, limit = 1000) // TODO: searchAll with no query
        val delta = all.filter { item ->
            val relation = VectorClock.compare(item.vectorClock, peerClock)
            relation == VectorClock.Relation.DOMINATES || relation == VectorClock.Relation.CONCURRENT
        }

        if (delta.isEmpty()) return

        val msg = StudySyncMessage(
            type = "study_delta",
            deviceId = deviceId,
            userDid = userDid,
            token = authToken ?: "",
            items = delta,
        )
        try {
            between.publish(syncSubject(deviceId, peerDeviceId), json.encodeToString(msg).encodeToByteArray())
        } catch (_: Exception) {}
    }

    /** Merge incoming items using vector clock comparison. Returns count merged. */
    private suspend fun mergeIncoming(items: List<StudyItem>): Int {
        var merged = 0
        for (remote in items) {
            val local = store.getItem(remote.id)
            if (local == null) {
                // New item this device has never seen — persist it. #5
                // (2026-07-19): this branch used to count merged++ and fire
                // ItemsMerged without ever storing the item, silently dropping
                // every synced-in Study/journal entry. A tombstone for an item
                // we never had is a no-op (nothing to delete).
                if (!remote.deleted) {
                    store.putItem(remote)
                    merged++
                }
            } else {
                val relation = VectorClock.compare(remote.vectorClock, local.vectorClock)
                when (relation) {
                    VectorClock.Relation.DOMINATES -> {
                        // Remote is newer — fast-forward
                        if (remote.deleted) {
                            store.deleteItem(local.id)
                        } else {
                            store.editItem(local.id, remote.content)
                        }
                        merged++
                    }
                    VectorClock.Relation.DOMINATED -> {
                        // Local is newer — nothing to do
                    }
                    VectorClock.Relation.CONCURRENT -> {
                        // Conflict — keep both versions
                        // TODO: Store conflict versions on the item for user resolution
                        listeners.forEach { it(SyncEvent.ConflictsDetected(1)) }
                    }
                    VectorClock.Relation.EQUAL -> {
                        // Same version — nothing to do
                    }
                }
            }
        }
        return merged
    }

    /** Build a summary clock from all local items. */
    private suspend fun buildClockSummary(): Map<String, Long> {
        val all = store.recentJournal(userDid, limit = 1000)
        val summary = mutableMapOf<String, Long>()
        for (item in all) {
            for ((device, version) in item.vectorClock) {
                summary[device] = maxOf(summary[device] ?: 0L, version)
            }
        }
        return summary
    }

    // ── Subjects ─────────────────────────────────────────────────────

    private fun stateSubject(src: String) =
        "between.$householdId.$src.*.study.state"

    private fun syncSubject(src: String, dst: String) =
        "between.$householdId.$src.$dst.study.sync"
}

// ── Wire messages ────────────────────────────────────────────────────

@Serializable
data class StudySyncMessage(
    val type: String,           // study_state, study_delta_request, study_delta
    val deviceId: String,
    // Whose Study this is about. The server hosts many users, so every message
    // names its owner; peers ignore messages for a different user. Empty = a
    // legacy pre-userDid peer (allowed).
    val userDid: String = "",
    // Auth token proving the sender speaks for userDid (session or pairing
    // token). The server peer DROPS unauthenticated messages.
    val token: String = "",
    val itemCount: Int = 0,
    val latestModified: Long = 0L,
    val clockSummary: Map<String, Long> = emptyMap(),
    val items: List<StudyItem> = emptyList(),
    val conflicts: Int = 0,
)

sealed class SyncEvent {
    data class ItemsMerged(val count: Int) : SyncEvent()
    data class ConflictsDetected(val count: Int) : SyncEvent()
}
