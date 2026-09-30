package org.wyrdsekai.app.hermod

import org.wyrdsekai.app.network.PairingClient
import org.wyrdsekai.app.network.homeBusUrl
import org.wyrdsekai.app.network.relayLegUrl
import org.wyrdsekai.app.state.TokenStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A pairing reply names the home's BUS (`natsUrl`, wss://host:<bus port + 1>)
 * and no relay. Saving it must leave the relay the invite named untouched, keep
 * the bus apart, and keep nothing that points at a loopback address (an older
 * home answered its own nats://127.0.0.1:4222).
 */
class PairingReplyKeepsTheRelayTest {

    private val prefs = Preferences.userNodeForPackage(TokenStore::class.java)
    private val keys = listOf(
        "wyrd_pairing_token", "wyrd_household_id", "wyrd_household_name", "wyrd_server_did",
        "wyrd_server_url", "wyrd_nats_url", "wyrd_relay_url", "wyrd_home_bus_url",
        "wyrd_home_nats_user", "wyrd_home_nats_password", "wyrd_lan_https",
    )
    private var saved: Map<String, String?> = emptyMap()
    private val store = TokenStore()

    @BeforeTest
    fun snapshot() {
        saved = keys.associateWith { prefs.get(it, null) }
        keys.forEach { prefs.remove(it) }
    }

    @AfterTest
    fun restore() {
        for ((k, v) in saved) if (v == null) prefs.remove(k) else prefs.put(k, v)
        prefs.flush()
    }

    private fun reply(natsUrl: String, serverUrl: String) = PairingClient.PairingCredentials(
        token = "wyrd_dev_x", householdId = "rehearsal", householdName = "Home Zone", serverDid = "",
        natsUrl = natsUrl, serverUrl = serverUrl, natsUser = "phone-abc", natsPass = "pw",
    )

    @Test
    fun theBusIsKeptAsTheBusAndTheRelayStaysTheInvites() {
        store.saveRelayUrl("wss://192.0.2.105:24443")
        store.saveNatsUrl("wss://192.0.2.105:24443")
        ConsentMint.save(store, reply("wss://192.0.2.105:27223", "https://192.0.2.105:27443"))
        assertEquals("wss://192.0.2.105:24443", store.relayLegUrl())
        assertEquals("wss://192.0.2.105:24443", store.loadNatsUrl())
        assertEquals("wss://192.0.2.105:27223", store.loadHomeBusUrl())
        assertEquals("wss://192.0.2.105:27223", store.homeBusUrl())
        assertEquals("https://192.0.2.105:27443", store.loadServerUrl())
        assertEquals("phone-abc", store.loadHomeNatsUser())
    }

    @Test
    fun savingABusLoginTellsARunningNodeToJoinTheBus() = runBlocking {
        val signal = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(2_000) { ConsentMint.paired.first() } }
        ConsentMint.save(store, reply("wss://192.0.2.105:27223", "https://192.0.2.105:27443"))
        assertEquals(Unit, signal.await())
    }

    @Test
    fun aLoopbackAnswerIsNotKept() {
        ConsentMint.save(store, reply("nats://127.0.0.1:27222", "http://127.0.0.1:27070"))
        assertNull(store.loadHomeBusUrl())
        assertNull(store.loadNatsUrl())
        assertNull(store.loadRelayUrl())
        assertNull(store.loadServerUrl())
        assertNull(store.relayLegUrl())
    }
}
