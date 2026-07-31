package org.wyrdsekai.app.engine.between

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.wyrdsekai.app.platform.epochMillis

/**
 * Registers and invokes device capabilities (camera, microphone, GPS, etc.)
 * as Between services that can be called remotely from any household node.
 *
 * Design from:
 * - Capabilities are registered by the phone node on boot
 * - Other nodes (desktop, server) can invoke them via Between
 * - Pre-authorized grants allow hands-free invocation
 * - Results returned via directed reply subject
 *
 * Subject pattern:
 *   between.{householdId}.{src}.{dst}.device.invoke     — invoke a capability
 *   between.{householdId}.{src}.{dst}.device.result      — result reply
 *   between.{householdId}.{deviceId}.*.device.capabilities — advertise available caps
 */
class DeviceCapabilityService(
    private val between: BetweenClient,
    private val deviceId: String,
    private val householdId: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Registered local capabilities with their handlers. */
    private val capabilities = mutableMapOf<String, CapabilityHandler>()

    /** Pre-authorized grants: {requesterId → set of capability names}. */
    private val grants = mutableMapOf<String, MutableSet<String>>()

    private var unsubInvoke: (() -> Unit)? = null
    private val listeners = mutableListOf<(CapabilityEvent) -> Unit>()

    /** Register a local capability (e.g. "camera", "microphone", "gps"). */
    fun register(name: String, handler: CapabilityHandler) {
        capabilities[name] = handler
    }

    /** Grant a requester pre-authorization for a capability. */
    fun grant(requesterId: String, capabilityName: String) {
        grants.getOrPut(requesterId) { mutableSetOf() }.add(capabilityName)
    }

    /** Revoke a pre-authorization. */
    fun revoke(requesterId: String, capabilityName: String) {
        grants[requesterId]?.remove(capabilityName)
    }

    /** Check if a requester has pre-authorization for a capability. */
    fun isGranted(requesterId: String, capabilityName: String): Boolean {
        return grants[requesterId]?.contains(capabilityName) == true
    }

    /** Start listening for remote invocations. */
    fun startListening() {
        val subject = "between.$householdId.*.${deviceId}.device.invoke"
        unsubInvoke = between.subscribe(subject) { _, data ->
            try {
                val request = json.decodeFromString<CapabilityRequest>(data.decodeToString())
                handleInvocation(request)
            } catch (_: Exception) {}
        }
    }

    fun stopListening() {
        unsubInvoke?.invoke()
        unsubInvoke = null
    }

    fun onEvent(callback: (CapabilityEvent) -> Unit) {
        listeners.add(callback)
    }

    /** Broadcast available capabilities to the household. */
    fun advertise() {
        if (!between.isConnected) return
        val msg = CapabilityAdvertisement(
            deviceId = deviceId,
            capabilities = capabilities.keys.toList(),
        )
        try {
            val subject = "between.$householdId.$deviceId.*.device.capabilities"
            between.publish(subject, json.encodeToString(msg).encodeToByteArray())
        } catch (_: Exception) {}
    }

    /** Invoke a capability on a remote device. */
    fun invoke(targetDeviceId: String, capabilityName: String, params: Map<String, String> = emptyMap()) {
        if (!between.isConnected) return
        val request = CapabilityRequest(
            requesterId = deviceId,
            capabilityName = capabilityName,
            params = params,
            requestId = "req-${epochMillis()}",
        )
        try {
            val subject = "between.$householdId.$deviceId.$targetDeviceId.device.invoke"
            between.publish(subject, json.encodeToString(request).encodeToByteArray())
        } catch (_: Exception) {}
    }

    private fun handleInvocation(request: CapabilityRequest) {
        val handler = capabilities[request.capabilityName]
        if (handler == null) {
            sendResult(request, CapabilityResult(request.requestId, false, error = "Unknown capability: ${request.capabilityName}"))
            return
        }

        // Check authorization
        if (!isGranted(request.requesterId, request.capabilityName)) {
            // Not pre-authorized — notify for user approval
            listeners.forEach { it(CapabilityEvent.AuthorizationRequired(request)) }
            return
        }

        // Execute
        try {
            val result = handler.execute(request.params)
            sendResult(request, CapabilityResult(request.requestId, true, data = result))
            listeners.forEach { it(CapabilityEvent.Invoked(request.capabilityName, request.requesterId)) }
        } catch (e: Exception) {
            sendResult(request, CapabilityResult(request.requestId, false, error = e.message ?: "Execution failed"))
        }
    }

    private fun sendResult(request: CapabilityRequest, result: CapabilityResult) {
        if (!between.isConnected) return
        try {
            val subject = "between.$householdId.$deviceId.${request.requesterId}.device.result"
            between.publish(subject, json.encodeToString(result).encodeToByteArray())
        } catch (_: Exception) {}
    }
}

/** Handler for a local device capability. */
fun interface CapabilityHandler {
    fun execute(params: Map<String, String>): Map<String, String>
}

// ── Wire types ───────────────────────────────────────────────────────

@Serializable
data class CapabilityRequest(
    val requesterId: String,
    val capabilityName: String,
    val params: Map<String, String> = emptyMap(),
    val requestId: String,
)

@Serializable
data class CapabilityResult(
    val requestId: String,
    val success: Boolean,
    val data: Map<String, String> = emptyMap(),
    val error: String? = null,
)

@Serializable
data class CapabilityAdvertisement(
    val deviceId: String,
    val capabilities: List<String>,
)

sealed class CapabilityEvent {
    data class AuthorizationRequired(val request: CapabilityRequest) : CapabilityEvent()
    data class Invoked(val capabilityName: String, val requesterId: String) : CapabilityEvent()
}
