package org.wyrdsekai.app.engine.discovery

import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.engine.between.BetweenClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HouseholdConnectorTest {

    /** mDNS scanner that returns a fixed household. */
    private class FixedMdnsScanner(private val result: DiscoveredHousehold?) : MdnsScanner {
        override suspend fun scan(serviceType: String, timeoutMs: Long): DiscoveredHousehold? = result
    }

    /** A simple BetweenClient that tracks connection state and optionally fails. */
    private class TestBetweenClient(private val failConnect: Boolean = false) : BetweenClient {
        private var _connected = false
        override val isConnected: Boolean get() = _connected
        val published = mutableListOf<Pair<String, ByteArray>>()

        override suspend fun connect(url: String) {
            if (failConnect) throw RuntimeException("Connection refused")
            _connected = true
        }

        override suspend fun disconnect() { _connected = false }

        override fun publish(subject: String, data: ByteArray) {
            published.add(subject to data)
        }

        override fun subscribe(subject: String, handler: (String, ByteArray) -> Unit): () -> Unit {
            return {}
        }
    }

    /** Between client factory that tracks creation and optionally fails connect. */
    private class TestBetweenClientFactory(
        private val failConnect: Boolean = false,
    ) : BetweenClientFactory {
        val created = mutableListOf<TestBetweenClient>()

        override fun create(): BetweenClient {
            val client = TestBetweenClient(failConnect)
            created.add(client)
            return client
        }
    }

    private val discoveredHousehold = DiscoveredHousehold(
        householdId = "hh-123",
        householdName = "Smith Home",
        natsWsUrl = "ws://198.51.100.100:9222",
        relayUrl = "wss://relay.wyrdsekai.org:9222",
        relayToken = "test-token-abc",
    )

    @Test
    fun connectViaMdnsDiscovery() = runTest {
        val factory = TestBetweenClientFactory()
        val connector = HouseholdConnector(
            mdnsScanner = FixedMdnsScanner(discoveredHousehold),
            betweenClientFactory = factory,
        )

        val client = connector.connect()

        assertEquals(ConnectivityState.CONNECTED_LAN, connector.state.value)
        assertTrue(client.isConnected)
        assertNotNull(connector.lastDiscovered)
        assertEquals("hh-123", connector.lastDiscovered!!.householdId)
    }

    @Test
    fun connectFallsToSavedConfig() = runTest {
        val factory = TestBetweenClientFactory()
        val saved = SavedHouseholdConfig(
            householdId = "hh-saved",
            householdName = "Saved Home",
            natsWsUrl = "ws://198.51.100.50:9222",
            relayUrl = "wss://relay.wyrdsekai.org:9222",
            relayToken = "saved-token",
        )

        val connector = HouseholdConnector(
            mdnsScanner = FixedMdnsScanner(null), // mDNS finds nothing
            betweenClientFactory = factory,
            savedConfig = saved,
        )

        val client = connector.connect()

        assertEquals(ConnectivityState.CONNECTED_LAN, connector.state.value)
        assertTrue(client.isConnected)
    }

    @Test
    fun connectFallsToRelay() = runTest {
        var connectAttempt = 0
        val factory = object : BetweenClientFactory {
            override fun create(): BetweenClient {
                connectAttempt++
                val shouldFail = connectAttempt <= 2
                return object : BetweenClient {
                    private var _connected = false
                    override val isConnected: Boolean get() = _connected

                    override suspend fun connect(url: String) {
                        // First two attempts fail (mDNS LAN, saved LAN), third (relay) succeeds
                        if (shouldFail) throw RuntimeException("LAN unreachable")
                        _connected = true
                    }

                    override suspend fun disconnect() { _connected = false }
                    override fun publish(subject: String, data: ByteArray) {}
                    override fun subscribe(subject: String, handler: (String, ByteArray) -> Unit): () -> Unit = {}
                }
            }
        }

        val saved = SavedHouseholdConfig(
            householdId = "hh-relay",
            householdName = "Relay Home",
            natsWsUrl = "ws://198.51.100.50:9222",
            relayUrl = "wss://relay.wyrdsekai.org:9222",
            relayToken = "relay-token",
        )

        val connector = HouseholdConnector(
            mdnsScanner = FixedMdnsScanner(discoveredHousehold),
            betweenClientFactory = factory,
            savedConfig = saved,
        )

        val client = connector.connect()

        assertEquals(ConnectivityState.CONNECTED_RELAY, connector.state.value)
        assertTrue(client.isConnected)
    }

    @Test
    fun connectGoesOfflineWhenAllFail() = runTest {
        val factory = TestBetweenClientFactory(failConnect = true)

        val connector = HouseholdConnector(
            mdnsScanner = FixedMdnsScanner(discoveredHousehold),
            betweenClientFactory = factory,
        )

        assertFailsWith<HouseholdUnreachableException> {
            connector.connect()
        }

        assertEquals(ConnectivityState.OFFLINE, connector.state.value)
    }

    @Test
    fun noMdnsNoSavedNoRelayGoesOffline() = runTest {
        val factory = TestBetweenClientFactory()

        val connector = HouseholdConnector(
            mdnsScanner = FixedMdnsScanner(null),
            betweenClientFactory = factory,
            savedConfig = null,
        )

        assertFailsWith<HouseholdUnreachableException> {
            connector.connect()
        }

        assertEquals(ConnectivityState.OFFLINE, connector.state.value)
    }

    @Test
    fun savedConfigUpdatedOnMdnsSuccess() = runTest {
        val factory = TestBetweenClientFactory()

        val connector = HouseholdConnector(
            mdnsScanner = FixedMdnsScanner(discoveredHousehold),
            betweenClientFactory = factory,
        )

        assertNull(connector.getSavedConfig())

        connector.connect()

        val saved = connector.getSavedConfig()
        assertNotNull(saved)
        assertEquals("hh-123", saved.householdId)
        assertEquals("Smith Home", saved.householdName)
        assertEquals("ws://198.51.100.100:9222", saved.natsWsUrl)
        assertEquals("wss://relay.wyrdsekai.org:9222", saved.relayUrl)
        assertTrue(saved.lastConnected > 0)
    }

    @Test
    fun updateSavedConfigManually() {
        val connector = HouseholdConnector()

        assertNull(connector.getSavedConfig())

        val config = SavedHouseholdConfig(
            householdId = "hh-manual",
            householdName = "Manual Config",
            natsWsUrl = "ws://192.0.2.1:9222",
        )
        connector.updateSavedConfig(config)

        assertEquals("hh-manual", connector.getSavedConfig()!!.householdId)
    }

    @Test
    fun backoffDelayIsExponential() {
        assertEquals(1_000L, HouseholdConnector.backoffDelayMs(0))
        assertEquals(2_000L, HouseholdConnector.backoffDelayMs(1))
        assertEquals(4_000L, HouseholdConnector.backoffDelayMs(2))
        assertEquals(8_000L, HouseholdConnector.backoffDelayMs(3))
        assertEquals(16_000L, HouseholdConnector.backoffDelayMs(4))
        // Capped at 16s
        assertEquals(16_000L, HouseholdConnector.backoffDelayMs(5))
        assertEquals(16_000L, HouseholdConnector.backoffDelayMs(10))
    }

    @Test
    fun discoveredHouseholdConstruction() {
        val household = DiscoveredHousehold(
            householdId = "hh-test",
            householdName = "Test Home",
            natsWsUrl = "ws://localhost:9222",
        )

        assertEquals("hh-test", household.householdId)
        assertEquals("Test Home", household.householdName)
        assertNull(household.relayUrl)
        assertNull(household.relayToken)
        assertEquals("1.0", household.version)
    }

    @Test
    fun savedHouseholdConfigFromDiscovered() {
        val discovered = DiscoveredHousehold(
            householdId = "hh-disc",
            householdName = "Disc Home",
            natsWsUrl = "ws://198.51.100.1:9222",
            relayUrl = "wss://relay.example.com:9222",
            relayToken = "tok-abc",
        )

        val saved = SavedHouseholdConfig.fromDiscovered(discovered, 1234567890L)

        assertEquals("hh-disc", saved.householdId)
        assertEquals("Disc Home", saved.householdName)
        assertEquals("ws://198.51.100.1:9222", saved.natsWsUrl)
        assertEquals("wss://relay.example.com:9222", saved.relayUrl)
        assertEquals("tok-abc", saved.relayToken)
        assertEquals(1234567890L, saved.lastConnected)
    }

    @Test
    fun connectivityStateEnumValues() {
        val states = ConnectivityState.entries
        assertEquals(5, states.size)
        assertTrue(states.contains(ConnectivityState.DISCOVERING))
        assertTrue(states.contains(ConnectivityState.CONNECTED_LAN))
        assertTrue(states.contains(ConnectivityState.CONNECTED_RELAY))
        assertTrue(states.contains(ConnectivityState.RECONNECTING))
        assertTrue(states.contains(ConnectivityState.OFFLINE))
    }

    @Test
    fun defaultMdnsScannerReturnsNull() = runTest {
        val scanner = DefaultMdnsScanner()
        assertNull(scanner.scan())
        assertNull(scanner.scan("_wyrdsekai._tcp.local", 1000))
    }
}
