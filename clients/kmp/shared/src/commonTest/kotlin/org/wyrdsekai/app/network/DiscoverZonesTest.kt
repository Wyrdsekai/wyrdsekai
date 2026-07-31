package org.wyrdsekai.app.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DiscoverZones tests ( — KMP parity with RN). */
class DiscoverZonesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(s: String): JsonObject = json.decodeFromString(JsonObject.serializer(), s)

    private val homeServer = obj("""{"did":"did:key:home-server","zoneLabel":"home-server","displayName":"home-server","tagline":"a quiet study","tags":["personal","household"]}""")
    private val qf = obj("""{"did":"did:key:qf","zoneLabel":"relay-b","tags":[]}""")

    private class StubClient(val result: List<JsonObject>?) : DirectorySearchClient {
        override suspend fun searchDirectory(query: String, limit: Int): List<JsonObject>? = result
    }

    @Test
    fun normalize_mapsManifestWithTagsAndTagline() {
        val zones = DiscoverZones.normalize(listOf(homeServer), emptySet())
        assertEquals(1, zones.size)
        val z = zones[0]
        assertEquals("home-server", z.zoneLabel)
        assertEquals("did:key:home-server", z.did)
        assertEquals("a quiet study", z.tagline)
        assertEquals(listOf("personal", "household"), z.tags)
        assertEquals(false, z.inBank)
    }

    @Test
    fun normalize_flagsBankedZones() {
        val zones = DiscoverZones.normalize(listOf(homeServer, qf), setOf("home-server"))
        assertTrue(zones.first { it.zoneLabel == "home-server" }.inBank)
        assertEquals(false, zones.first { it.zoneLabel == "relay-b" }.inBank)
    }

    @Test
    fun normalize_dropsEntriesWithNoZoneLabel() {
        val noLabel = obj("""{"did":"did:key:x"}""")
        val zones = DiscoverZones.normalize(listOf(noLabel, homeServer), emptySet())
        assertEquals(listOf("home-server"), zones.map { it.zoneLabel })
    }

    @Test
    fun discover_returnsNormalisedOnSuccess() = runTest {
        val r = DiscoverZones.discover(StubClient(listOf(homeServer, qf)), ZoneBank())
        assertNull(r.error)
        assertEquals(setOf("home-server", "relay-b"), r.zones.map { it.zoneLabel }.toSet())
    }

    @Test
    fun discover_emptyWithErrorOnFailure() = runTest {
        val r = DiscoverZones.discover(StubClient(null), ZoneBank())
        assertTrue(r.zones.isEmpty())
        assertEquals("directory search failed", r.error)
    }
}
