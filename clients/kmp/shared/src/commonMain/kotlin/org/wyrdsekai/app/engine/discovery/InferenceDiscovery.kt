package org.wyrdsekai.app.engine.discovery

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.wyrdsekai.app.platform.localIpv4Addresses

/**
 * A discovered Wyrdsekai server on the network.
 *
 * @param url Base URL of the server (e.g., "http://198.51.100.39:7070")
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
 * Discovers Wyrdsekai servers on the local network.
 *
 * SECURITY: Only discovers Wyrdsekai servers (port 7070, /health endpoint).
 * Never probes for raw inference endpoints (Ollama, llama-server, etc.).
 * The phone talks to Wyrdsekai servers only — the server handles inference routing.
 *
 * Discovery strategy:
 * 1. Saved server URL (user explicitly configured)
 * 2. Subnet scan — probe port 7070 on all IPs in the local /24 subnet
 * 3. Return all responsive Wyrdsekai servers
 */
object InferenceDiscovery {

    private const val PROBE_TIMEOUT_MS = 1_500L
    private const val WYRDSEKAI_PORT = 7070
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Discover Wyrdsekai servers on the network.
     *
     * @param savedUrl User-configured server URL (from TokenStore)
     * @param localSubnet Local subnet prefix (e.g., "192.168.10") — if null, tries to detect
     * @return All responsive Wyrdsekai servers
     */
    suspend fun discover(
        savedUrl: String? = null,
        localSubnet: String? = null,
    ): List<DiscoveredServer> = coroutineScope {
        val results = mutableListOf<DiscoveredServer>()

        // 1. Saved URL first (user explicitly configured — trusted)
        if (!savedUrl.isNullOrBlank()) {
            val server = probeWyrdsekai(savedUrl)
            if (server != null) {
                results.add(server.copy(label = "Saved: ${server.name}"))
            }
        }

        // 2. Subnet scan — probe port 7070 on local network
        val subnets = mutableListOf<String>()
        if (localSubnet != null) {
            subnets.add(localSubnet)
        } else {
            // Common home subnets — try both
            subnets.addAll(detectLocalSubnets())
        }

        // Probe all IPs in parallel (254 per subnet, 1.5s timeout each)
        val probeJobs = subnets.flatMap { subnet ->
            (1..254).map { host ->
                async {
                    val ip = "$subnet.$host"
                    val url = "http://$ip:$WYRDSEKAI_PORT"
                    probeWyrdsekai(url)
                }
            }
        }

        // Collect results
        for (job in probeJobs) {
            val server = job.await()
            if (server != null && results.none { it.url == server.url }) {
                results.add(server)
            }
        }

        results
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
            val client = HttpClient {
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

    /**
     * Detect local subnet prefixes from common patterns.
     * Returns prefixes like "192.168.1", "192.168.10".
     */
    private fun detectLocalSubnets(): List<String> {
        return try {
            val addresses = localIpv4Addresses()

            addresses.mapNotNull { ip ->
                val parts = ip.split(".")
                if (parts.size == 4 && parts[0] == "192") {
                    "${parts[0]}.${parts[1]}.${parts[2]}"
                } else null
            }.distinct()
        } catch (_: Exception) {
            // Fallback: common home subnets
            listOf("192.168.1", "192.168.10")
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
