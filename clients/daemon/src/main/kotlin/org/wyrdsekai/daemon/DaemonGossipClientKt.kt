package org.wyrdsekai.daemon

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Kotlin gossip client for Android daemon.
 * Announces capabilities every 30s on wyrd.inference.capabilities.
 * Wire-compatible with server's InferenceGossip and daemon-common's DaemonGossipClient.
 */
class DaemonGossipClientKt(
    private val nats: NatsConnectionWrapper,
    private val nodeId: String,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "DaemonGossip"
        private const val SUBJECT = "wyrd.inference.capabilities"
        private const val ANNOUNCE_INTERVAL_MS = 30_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var announceJob: Job? = null

    fun startAnnouncing(capabilityProvider: () -> DaemonCapabilityKt) {
        announceJob = scope.launch {
            while (isActive) {
                try {
                    val cap = capabilityProvider()
                    val payload = json.encodeToString(cap)
                    nats.publishString(SUBJECT, payload)
                    Log.d(TAG, "Announced: ${cap.models.size} models, slots=${cap.availableSlots}")
                } catch (e: Exception) {
                    Log.e(TAG, "Announce failed: ${e.message}")
                }
                delay(ANNOUNCE_INTERVAL_MS)
            }
        }
        Log.i(TAG, "Started gossip announcements")
    }

    fun subscribePeers(listener: (DaemonCapabilityKt) -> Unit) {
        nats.subscribe(SUBJECT) { payload ->
            try {
                val cap = json.decodeFromString<DaemonCapabilityKt>(payload)
                if (cap.nodeId != nodeId) {
                    listener(cap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse peer capability: ${e.message}")
            }
        }
    }

    fun stop() {
        announceJob?.cancel()
        announceJob = null
        Log.i(TAG, "Gossip client stopped")
    }
}
