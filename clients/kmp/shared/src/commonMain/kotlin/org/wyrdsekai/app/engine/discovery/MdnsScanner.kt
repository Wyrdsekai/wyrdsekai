package org.wyrdsekai.app.engine.discovery

/**
 * Interface for mDNS/DNS-SD service discovery.
 *
 * The household server advertises itself as _wyrdsekai._tcp.local
 * with TXT records containing the NATS WebSocket URL, relay URL,
 * household ID, and auth token.
 *
 * Platform-specific implementations:
 * - Android: NsdManager (API 16+)
 * - iOS: NSNetServiceBrowser (Bonjour)
 * - Desktop: JmDNS
 *
 */
interface MdnsScanner {
    /**
     * Scan for a household server on the local network.
     *
     * @param serviceType mDNS service type (e.g., "_wyrdsekai._tcp.local")
     * @param timeoutMs Maximum time to wait for discovery
     * @return Discovered household info, or null if not found
     */
    suspend fun scan(
        serviceType: String = SERVICE_TYPE,
        timeoutMs: Long = 5_000,
    ): DiscoveredHousehold?

    companion object {
        const val SERVICE_TYPE = "_wyrdsekai._tcp.local"
    }
}

/**
 * Information discovered about a household server via mDNS.
 *
 * Extracted from mDNS TXT records:
 * - nats_ws: LAN NATS WebSocket URL
 * - relay_url: Cloud relay URL (if configured)
 * - relay_token: Authentication token for relay
 * - household_id: Unique household identifier
 * - household_name: Human-readable household name
 * - version: Protocol version
 */
data class DiscoveredHousehold(
    val householdId: String,
    val householdName: String,
    val natsWsUrl: String,
    val relayUrl: String? = null,
    val relayToken: String? = null,
    val version: String = "1.0",
)
