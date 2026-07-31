package org.wyrdsekai.app.engine.discovery

import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.engine.between.BetweenClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * the cascade's cloud-relay level must hand the
 * NATS credentials saved from a wyrdphone:// invite to the client before
 * connecting (they ride the NATS CONNECT message, not the URL).
 */
class HouseholdConnectorInviteCredsTest {

    /** Fails LAN (ws://) connects so the cascade falls through to the relay. */
    private class RecordingClient : BetweenClient {
        private var _connected = false
        override val isConnected: Boolean get() = _connected
        var connectedUrl: String? = null
        var user: String? = null
        var password: String? = null

        override fun setCredentials(user: String?, password: String?) {
            this.user = user
            this.password = password
        }

        override suspend fun connect(url: String) {
            if (url.startsWith("ws://")) throw RuntimeException("LAN unreachable")
            connectedUrl = url
            _connected = true
        }

        override suspend fun disconnect() { _connected = false }
        override fun publish(subject: String, data: ByteArray) {}
        override fun subscribe(
            subject: String,
            handler: (String, ByteArray) -> Unit,
        ): () -> Unit = {}
    }

    private class RecordingFactory : BetweenClientFactory {
        val created = mutableListOf<RecordingClient>()
        override fun create(): BetweenClient =
            RecordingClient().also { created.add(it) }
    }

    private val noMdns = object : MdnsScanner {
        override suspend fun scan(
            serviceType: String,
            timeoutMs: Long,
        ): DiscoveredHousehold? = null
    }

    private fun inviteConfig(
        natsUser: String? = "relay_phone",
        relayToken: String? = null,
    ) = SavedHouseholdConfig(
        householdId = "hh-9ce1d3b57ebf",
        householdName = "hh-9ce1d3b57ebf",
        natsWsUrl = "ws://198.51.100.50:9222",
        relayUrl = "wss://relay.example.org:4443",
        relayToken = relayToken,
        lastConnected = 1L,
        natsUser = natsUser,
        natsPassword = natsUser?.let { "pw-secret" },
        zoneId = "zone-alpha",
        relayFp = "63:24:CB:6B",
    )

    @Test
    fun relayLevelHandsInviteCredentialsToTheClient() = runTest {
        val factory = RecordingFactory()
        val connector = HouseholdConnector(noMdns, factory, inviteConfig())

        connector.connect()

        val relayClient = factory.created.last()
        assertEquals("wss://relay.example.org:4443", relayClient.connectedUrl)
        assertEquals("relay_phone", relayClient.user)
        assertEquals("pw-secret", relayClient.password)
        assertEquals(ConnectivityState.CONNECTED_RELAY, connector.state.value)
    }

    @Test
    fun legacyTokenConfigKeepsQueryParamForm() = runTest {
        val factory = RecordingFactory()
        val connector = HouseholdConnector(
            noMdns, factory, inviteConfig(natsUser = null, relayToken = "tok-legacy"))

        connector.connect()

        val relayClient = factory.created.last()
        assertEquals("wss://relay.example.org:4443?token=tok-legacy",
            relayClient.connectedUrl)
        assertNull(relayClient.user)
        assertTrue(relayClient.password == null)
    }
}
