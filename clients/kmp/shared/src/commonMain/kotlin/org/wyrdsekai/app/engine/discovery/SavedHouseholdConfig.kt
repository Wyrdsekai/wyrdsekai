package org.wyrdsekai.app.engine.discovery

import kotlinx.serialization.Serializable

/**
 * Persisted household configuration for fallback when mDNS is unavailable.
 *
 * Saved on successful mDNS discovery. Used as fallback when:
 * - Away from home (mDNS unreachable)
 * - mDNS blocked on network (corporate WiFi)
 * - Server IP changed but relay URL is still valid
 *
 * Stored encrypted in platform keystore for security (contains relay token).
 *
 */
@Serializable
data class SavedHouseholdConfig(
    val householdId: String,
    val householdName: String,
    val natsWsUrl: String,
    val relayUrl: String? = null,
    val relayToken: String? = null,
    val lastConnected: Long = 0L,
    // relay credentials from a wyrdphone:// invite.
    // All default null so configs saved before P5 deserialize unchanged.
    val natsUser: String? = null,
    val natsPassword: String? = null,
    val zoneId: String? = null,
    /** Relay leaf-cert SHA-256 from the invite — TOFU pin seed (self-signed). */
    val relayFp: String? = null,
    /** Household CA SHA-256 from the invite — preferred pin (survives leaf rotation). */
    val relayCaFp: String? = null,
) {
    /**
     * Create from a discovered household (mDNS result).
     */
    companion object {
        fun fromDiscovered(discovered: DiscoveredHousehold, timestamp: Long): SavedHouseholdConfig {
            return SavedHouseholdConfig(
                householdId = discovered.householdId,
                householdName = discovered.householdName,
                natsWsUrl = discovered.natsWsUrl,
                relayUrl = discovered.relayUrl,
                relayToken = discovered.relayToken,
                lastConnected = timestamp,
            )
        }

        /**
         * Create from a `wyrd phone invite` URL (/P5).
         * The first relay in the invite's ordered failover list wins; the
         * relay IS the household's reachable address from off-LAN, so it
         * fills both natsWsUrl and relayUrl.
         */
        fun fromPhoneInvite(invite: PhoneInvite, timestamp: Long): SavedHouseholdConfig {
            val relay = invite.relays.first()
            return SavedHouseholdConfig(
                householdId = invite.householdId ?: "unknown",
                householdName = invite.householdId ?: "Relay household",
                natsWsUrl = relay.wsUrl,
                relayUrl = relay.wsUrl,
                relayToken = null,
                lastConnected = timestamp,
                natsUser = relay.natsUser,
                natsPassword = relay.natsPassword,
                zoneId = invite.zoneId,
                relayFp = relay.fp,
                relayCaFp = relay.caFp,
            )
        }
    }
}
