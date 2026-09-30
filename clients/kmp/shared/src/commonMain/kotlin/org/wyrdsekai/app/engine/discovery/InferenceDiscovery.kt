package org.wyrdsekai.app.engine.discovery

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.wyrdsekai.app.network.createHouseholdHttpClient

/**
 * A discovered Wyrdsekai server on the network.
 *
 * @param url Base URL of the server (e.g., "https://198.51.100.39:7443", the invite's lan_https)
 * @param name Server name or hostname
 * @param label Human-readable label for display
 * @param natsUrl NATS URL from /health (e.g., "nats://198.51.100.39:4222"), null if not present
 * @param relayUrl Relay URL from /health (e.g., "wss://relay.wyrdsekai.org:9222"), null if not present
 */
data class DiscoveredServer(
    val url: String,
    val name: String,
    val label: String,
    val natsUrl: String? = null,
    val relayUrl: String? = null,
    /** Inference config from server — what companion inference is available. */
    val inferenceConfig: InferenceCapability? = null,
)

/**
 * Inference capability advertised by a household server.
 * Parsed from /health response "inference" field.
 */
data class InferenceCapability(
    val available: Boolean = false,
    val provider: String? = null,   // "ollama", "llamacpp", "cloud"
    val baseUrl: String? = null,
    val models: List<String> = emptyList(),
    val companionModel: String? = null,
)

// Keep old type as alias for backward compatibility
typealias DiscoveredInference = DiscoveredServer

/**
 * Finds the Wyrdsekai home this phone already knows.
 *
 * It used to scan every address of the local /24 for http://<ip>:7070/health.
 * A 0.5.0 home answers plain http on its own machine only (W2), so the scan
 * found nothing, and anything it did find could only be reached in the clear.
 * A phone meets its home through an invite (QR or link): that carries the
 * home's https address and the certificate fingerprint the phone pins. There
 * is no scan and no trust on first use; [discover] checks only the saved
 * address, through the pinned household client.
 */
object InferenceDiscovery {

    private const val PROBE_TIMEOUT_MS = 1_500L
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The saved home, when it answers /health over its pinned https address
     * (or this device's own loopback). Empty otherwise: pairing a new home
     * takes an invite.
     *
     * @param savedUrl The home address from the invite (TokenStore)
     */
    suspend fun discover(savedUrl: String? = null): List<DiscoveredServer> {
        if (savedUrl.isNullOrBlank()) return emptyList()
        val server = probeWyrdsekai(savedUrl) ?: return emptyList()
        return listOf(server.copy(label = "Saved: ${server.name}"))
    }

    /**
     * Pick the best server from discovered list.
     * Priority: saved > first non-local.
     */
    fun bestEndpoint(discovered: List<DiscoveredServer>): DiscoveredServer? {
        return discovered.firstOrNull { it.label.startsWith("Saved") }
            ?: discovered.firstOrNull()
    }

    /**
     * Probe a URL to check if it's a running Wyrdsekai server.
     * Checks GET /health — expects a JSON response with server info.
     * Returns null if not a Wyrdsekai server or unreachable.
     */
    internal suspend fun probeWyrdsekai(baseUrl: String): DiscoveredServer? {
        return try {
            // The household client: the home's invite pin, and never plain http off the device.
            val client = createHouseholdHttpClient().config {
                install(HttpTimeout) {
                    requestTimeoutMillis = PROBE_TIMEOUT_MS
                    connectTimeoutMillis = PROBE_TIMEOUT_MS
                }
            }
            val response: HttpResponse = client.get("$baseUrl/health")
            client.close()

            if (response.status.value !in 200..299) return null

            // Try to parse server name, natsUrl, relayUrl from health response
            val body = response.bodyAsText()
            var name = extractHostname(baseUrl)
            var natsUrl: String? = null
            var relayUrl: String? = null
            var inferenceConfig: InferenceCapability? = null
            try {
                val obj = json.parseToJsonElement(body).jsonObject
                name = obj["name"]?.jsonPrimitive?.content
                    ?: obj["server"]?.jsonPrimitive?.content
                    ?: extractHostname(baseUrl)
                natsUrl = obj["natsUrl"]?.jsonPrimitive?.content
                relayUrl = obj["relayUrl"]?.jsonPrimitive?.content

                // Parse inference config if present
                val infObj = obj["inference"]?.jsonObject
                if (infObj != null) {
                    inferenceConfig = InferenceCapability(
                        available = infObj["available"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                        provider = infObj["provider"]?.jsonPrimitive?.content,
                        baseUrl = infObj["baseUrl"]?.jsonPrimitive?.content,
                        models = try {
                            infObj["models"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                        } catch (_: Exception) { emptyList() },
                        companionModel = infObj["companionModel"]?.jsonPrimitive?.content,
                    )
                }
            } catch (_: Exception) {
                // Parse failure is non-fatal — name defaults to hostname
            }

            DiscoveredServer(
                url = baseUrl,
                name = name,
                label = "$name ($baseUrl)",
                natsUrl = natsUrl,
                relayUrl = relayUrl,
                inferenceConfig = inferenceConfig,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractHostname(url: String): String {
        return try {
            val stripped = url.removePrefix("http://").removePrefix("https://")
            stripped.substringBefore(":").substringBefore("/")
        } catch (_: Exception) {
            url
        }
    }
}
