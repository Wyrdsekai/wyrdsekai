package org.wyrdsekai.app.network

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ZoneBankSync tests ( — KMP parity with RN). */
class ZoneBankSyncTest {

    private fun zone(id: String, user: String = "operator", added: Long = 1000, used: Long? = null) =
        ZoneBankEntry(zoneId = id, displayName = id, relayUrls = emptyList(), username = user, addedAt = added, lastUsedAt = used)

    /** A stub client backed by an in-memory server blob. */
    private class StubClient(var blob: String?, var stamp: Long = 0, val failGet: Boolean = false) : ZoneBankSyncClient {
        override suspend fun getZoneBank(): ZoneBankFetch? =
            if (failGet) null else ZoneBankFetch(blob, stamp)
        override suspend fun putZoneBank(bankJson: String, updatedAt: Long): Boolean {
            blob = bankJson; stamp = updatedAt; return true
        }
    }

    @Test
    fun merge_addsRemoteOnly() {
        val bank = ZoneBank()
        bank.addOrUpdateZone(zone("home-server"))
        val n = ZoneBankSync.mergeRemoteZones(bank, listOf(zone("relay-b")))
        assertEquals(1, n)
        assertEquals(setOf("home-server", "relay-b"), bank.zones.map { it.zoneId }.toSet())
    }

    @Test
    fun merge_keepsLocallyNewer() {
        val bank = ZoneBank()
        bank.addOrUpdateZone(zone("home-server", user = "local", used = 5000))
        val n = ZoneBankSync.mergeRemoteZones(bank, listOf(zone("home-server", user = "remote", used = 1000)))
        assertEquals(0, n)
        assertEquals("local", bank.getZone("home-server")!!.username)
    }

    @Test
    fun merge_takesRemotelyNewer() {
        val bank = ZoneBank()
        bank.addOrUpdateZone(zone("home-server", user = "local", used = 1000))
        val n = ZoneBankSync.mergeRemoteZones(bank, listOf(zone("home-server", user = "remote", used = 9000)))
        assertEquals(1, n)
        assertEquals("remote", bank.getZone("home-server")!!.username)
    }

    @Test
    fun sync_pullsMergesAndPushesUnion() = runTest {
        val bank = ZoneBank()
        bank.addOrUpdateZone(zone("home-server"))
        val remote = ZoneBank().also { it.addOrUpdateZone(zone("relay-b")) }
        val client = StubClient(blob = remote.serializeZones())
        val res = ZoneBankSync.syncZoneBank(bank, client, now = 7777)
        assertTrue(res.ok); assertEquals(1, res.pulled); assertTrue(res.pushed)
        assertEquals(setOf("home-server", "relay-b"), bank.zones.map { it.zoneId }.toSet())
        assertEquals(7777, client.stamp)
        // Server received the merged superset.
        val pushed = ZoneBank().also { it.load(null, client.blob) }
        assertEquals(setOf("home-server", "relay-b"), pushed.zones.map { it.zoneId }.toSet())
    }

    @Test
    fun sync_pushesLocalWhenServerEmpty() = runTest {
        val bank = ZoneBank()
        bank.addOrUpdateZone(zone("home-server"))
        val client = StubClient(blob = null)
        val res = ZoneBankSync.syncZoneBank(bank, client, now = 100)
        assertTrue(res.ok); assertEquals(0, res.pulled); assertTrue(res.pushed)
        assertEquals(1, ZoneBank().also { it.load(null, client.blob) }.zones.size)
    }

    @Test
    fun sync_noPushWhenBothEmpty() = runTest {
        val client = StubClient(blob = null)
        val res = ZoneBankSync.syncZoneBank(ZoneBank(), client, now = 100)
        assertTrue(res.ok); assertEquals(0, res.pulled); assertFalse(res.pushed)
        assertNull(client.blob)
    }

    @Test
    fun sync_reportsGetFailure() = runTest {
        val res = ZoneBankSync.syncZoneBank(ZoneBank(), StubClient(blob = null, failGet = true), now = 1)
        assertFalse(res.ok)
        assertEquals("zonebank get failed", res.error)
    }
}
