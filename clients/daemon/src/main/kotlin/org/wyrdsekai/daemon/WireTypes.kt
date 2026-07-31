package org.wyrdsekai.daemon

import kotlinx.serialization.Serializable

/**
 * Wire types for InferenceGossip — Kotlin equivalents of daemon-common's Java records.
 * JSON-compatible: same field names, same structure.
 * The "Kt" suffix avoids name collision with the Java daemon-common types.
 */

@Serializable
data class DaemonCapabilityKt(
    val nodeId: String,
    val models: List<DaemonModelKt>,
    val totalGpuCount: Int,
    val totalFreeVramMB: Long,
    val availableSlots: Int,
    val queueDepth: Int,
    val avgLatencyMs: Double,
    val timestamp: Long,
)

@Serializable
data class DaemonModelKt(
    val modelId: String,
    val tier: String,
    val endpoint: String,
    val maxConcurrent: Int,
    val activeLeases: Int,
)

/** Simple stats tracker (Kotlin version of daemon-common's DaemonStats). */
class DaemonStatsKt {
    @Volatile var requestsServed: Int = 0; private set
    @Volatile var tokensGenerated: Long = 0; private set
    @Volatile var activeRequests: Int = 0; private set
    @Volatile var queueDepth: Int = 0
    @Volatile var avgLatencyMs: Double = 0.0; private set

    private val alpha = 0.1

    fun recordRequestStart() { activeRequests++ }
    fun recordFailure() { activeRequests-- }

    fun recordCompletion(latencyMs: Long, tokens: Int) {
        requestsServed++
        tokensGenerated += tokens
        activeRequests--
        avgLatencyMs = if (avgLatencyMs == 0.0) latencyMs.toDouble()
                       else alpha * latencyMs + (1 - alpha) * avgLatencyMs
    }
}
