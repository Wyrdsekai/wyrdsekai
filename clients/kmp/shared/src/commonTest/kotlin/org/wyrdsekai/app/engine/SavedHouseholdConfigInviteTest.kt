package org.wyrdsekai.app.engine

import org.wyrdsekai.app.engine.discovery.PhoneInvite
import org.wyrdsekai.app.engine.discovery.SavedHouseholdConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * the invite → SavedHouseholdConfig mapping that
 * the ConnectionViewModel paste intercept persists.
 */
class SavedHouseholdConfigInviteTest {

    private fun invite(zoneId: String? = "zone-alpha") = PhoneInvite(
        relays = listOf(
            PhoneInvite.Relay(
                wsUrl = "wss://relay.example.org:4443",
                natsUser = "relay_phone",
                natsPassword = "pw-secret",
                fp = "63:24:CB:6B",
                caFp = "E5:F0:A2:3E",
            ),
            PhoneInvite.Relay(
                wsUrl = "wss://backup.example.org",
                natsUser = "relay_phone",
                natsPassword = "pw-2",
                fp = null,
                caFp = null,
            ),
        ),
        householdId = "hh-9ce1d3b57ebf",
        zoneId = zoneId,
        mintedAt = 1781225931L,
    )

    @Test
    fun firstRelayWinsAndFillsBothUrls() {
        val config = SavedHouseholdConfig.fromPhoneInvite(invite(), 42L)
        assertEquals("wss://relay.example.org:4443", config.natsWsUrl)
        assertEquals("wss://relay.example.org:4443", config.relayUrl)
        assertEquals("relay_phone", config.natsUser)
        assertEquals("pw-secret", config.natsPassword)
        assertEquals("zone-alpha", config.zoneId)
        assertEquals("63:24:CB:6B", config.relayFp)
        assertEquals("hh-9ce1d3b57ebf", config.householdId)
        assertEquals(42L, config.lastConnected)
        // The invite carries no MCP token — that comes from login later.
        assertNull(config.relayToken)
    }

    @Test
    fun missingZoneStaysNullForDiscoverZoneFallback() {
        val config = SavedHouseholdConfig.fromPhoneInvite(invite(zoneId = null), 1L)
        assertNull(config.zoneId)
    }

    @Test
    fun preP5ConfigsStillConstructWithoutRelayCredentialFields() {
        // Compile-level back-compat guard: the original five-field shape
        // (plus timestamp) must keep working for configs saved before P5.
        val legacy = SavedHouseholdConfig(
            householdId = "hh-1",
            householdName = "Home",
            natsWsUrl = "wss://lan.local:9222",
            relayUrl = null,
            relayToken = null,
            lastConnected = 0L,
        )
        assertNull(legacy.natsUser)
        assertNull(legacy.natsPassword)
        assertNull(legacy.zoneId)
        assertNull(legacy.relayFp)
    }
}
