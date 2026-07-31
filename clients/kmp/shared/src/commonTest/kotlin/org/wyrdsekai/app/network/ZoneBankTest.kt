package org.wyrdsekai.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** ZoneBank store tests (/P5 — KMP parity with RN). */
class ZoneBankTest {

    private fun relay(url: String) = HeldRelay(wsUrl = url, natsUser = "relay_phone", natsPass = "pw", addedAt = 1)
    private fun zone(id: String, relays: List<String>, now: Long = 1000) =
        ZoneBankEntry(zoneId = id, displayName = id, relayUrls = relays, username = "operator", addedAt = now)

    @Test
    fun addRelay_dedupesByWsUrl_andRefreshesInPlace() {
        val bank = ZoneBank()
        bank.addRelay(relay("wss://relay-node:4443"))
        bank.addRelay(HeldRelay("wss://relay-node:4443", caFp = "aa:bb", natsUser = "u2", natsPass = "p2", addedAt = 99))
        assertEquals(1, bank.relays.size)
        assertEquals("u2", bank.relays[0].natsUser)
        assertEquals(1, bank.relays[0].addedAt) // original addedAt kept
    }

    @Test
    fun addOrUpdateZone_lww_unionsRelayUrls_keepsAddedAt() {
        val bank = ZoneBank()
        bank.addOrUpdateZone(zone("home-server", listOf("wss://relay-node:4443"), now = 10))
        bank.addOrUpdateZone(zone("home-server", listOf("wss://qf:4443"), now = 20))
        val z = bank.getZone("home-server")!!
        assertEquals(listOf("wss://relay-node:4443", "wss://qf:4443"), z.relayUrls)
        assertEquals(10, z.addedAt)
    }

    @Test
    fun bumpRelay_movesToFront() {
        val bank = ZoneBank()
        bank.addOrUpdateZone(zone("home-server", listOf("wss://relay-node:4443", "wss://qf:4443")))
        bank.bumpRelay("home-server", "wss://qf:4443")
        assertEquals(listOf("wss://qf:4443", "wss://relay-node:4443"), bank.getZone("home-server")!!.relayUrls)
    }

    @Test
    fun relaysForZone_entryOrder_thenFallbackToAllHeld() {
        val bank = ZoneBank()
        bank.addRelay(relay("wss://relay-node:4443"))
        bank.addRelay(relay("wss://qf:4443"))
        bank.addOrUpdateZone(zone("home-server", listOf("wss://qf:4443")))
        assertEquals(listOf("wss://qf:4443"), bank.relaysForZone("home-server").map { it.wsUrl })
        // A synced zone naming an unpinned relay → fall back to all held.
        bank.addOrUpdateZone(zone("beta", listOf("wss://unknown:4443")))
        assertEquals(setOf("wss://relay-node:4443", "wss://qf:4443"), bank.relaysForZone("beta").map { it.wsUrl }.toSet())
    }

    @Test
    fun setHomeZone_marksExactlyOne() {
        val bank = ZoneBank()
        bank.addOrUpdateZone(zone("home-server", emptyList()))
        bank.addOrUpdateZone(zone("beta", emptyList()))
        bank.setHomeZone("home-server")
        bank.setHomeZone("beta")
        assertEquals("beta", bank.homeZone()?.zoneId)
        assertFalse(bank.getZone("home-server")!!.homeZone)
    }

    @Test
    fun load_roundTripsThroughSerialize() {
        val a = ZoneBank()
        a.addRelay(relay("wss://relay-node:4443"))
        a.addOrUpdateZone(zone("home-server", listOf("wss://relay-node:4443")))
        val zonesJson = a.serializeZones()
        val b = ZoneBank()
        b.load(null, zonesJson)
        assertEquals(1, b.zones.size)
        assertEquals("home-server", b.zones[0].zoneId)
    }

    @Test
    fun onChange_firesOnMutation() {
        var zonesSeen: String? = null
        val bank = ZoneBank(onChange = { _, zonesJson -> zonesSeen = zonesJson })
        bank.addOrUpdateZone(zone("home-server", emptyList()))
        assertTrue(zonesSeen!!.contains("home-server"))
    }

    @Test
    fun getZone_missing_isNull() {
        assertNull(ZoneBank().getZone("nope"))
    }
}
