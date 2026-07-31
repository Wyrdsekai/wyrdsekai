package org.wyrdsekai.app.viewmodel

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.engine.between.BetweenClient
import org.wyrdsekai.app.engine.discovery.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HouseholdViewModelTest {

    /** mDNS scanner that returns a fixed household. */
    private class FixedMdnsScanner(private val result: DiscoveredHousehold?) : MdnsScanner {
        override suspend fun scan(serviceType: String, timeoutMs: Long): DiscoveredHousehold? = result
    }

    /** A simple BetweenClient for testing. */
    private class TestBetweenClient(private val failConnect: Boolean = false) : BetweenClient {
        private var _connected = false
        override val isConnected: Boolean get() = _connected

        override suspend fun connect(url: String) {
            if (failConnect) throw RuntimeException("Connection refused")
            _connected = true
        }

        override suspend fun disconnect() { _connected = false }

        override fun publish(subject: String, data: ByteArray) {}

        override fun subscribe(subject: String, handler: (String, ByteArray) -> Unit): () -> Unit = {}
    }

    /** Factory that produces test clients. */
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
        householdId = "hh-test-123",
        householdName = "Test Household",
        natsWsUrl = "ws://198.51.100.100:9222",
        relayUrl = "wss://relay.wyrdsekai.org:9222",
        relayToken = "test-token",
    )

    private fun createViewModel(
        scope: TestScope,
        mdnsResult: DiscoveredHousehold? = discoveredHousehold,
        failConnect: Boolean = false,
        savedConfig: SavedHouseholdConfig? = null,
    ): HouseholdViewModel {
        val connector = HouseholdConnector(
            mdnsScanner = FixedMdnsScanner(mdnsResult),
            betweenClientFactory = TestBetweenClientFactory(failConnect),
            savedConfig = savedConfig,
        )
        return HouseholdViewModel(scope, connector)
    }

    @Test
    fun initialStateIsDiscovering() = runTest {
        val vm = createViewModel(this)
        assertEquals(ConnectivityState.DISCOVERING, vm.connectivityState.value)
        assertNull(vm.householdId.value)
        assertTrue(vm.connectedNodes.value.isEmpty())
    }

    @Test
    fun connectSuccessUpdatesState() = runTest {
        val vm = createViewModel(this)

        vm.connect()
        advanceUntilIdle()

        assertEquals(ConnectivityState.CONNECTED_LAN, vm.connectivityState.value)
        assertEquals("hh-test-123", vm.householdId.value)
        assertNull(vm.error.value)
    }

    @Test
    fun connectFailureUpdatesError() = runTest {
        val vm = createViewModel(this, failConnect = true)

        vm.connect()
        advanceUntilIdle()

        assertEquals(ConnectivityState.OFFLINE, vm.connectivityState.value)
        assertNotNull(vm.error.value)
    }

    @Test
    fun disconnectClearsState() = runTest {
        val vm = createViewModel(this)

        vm.connect()
        advanceUntilIdle()
        assertEquals(ConnectivityState.CONNECTED_LAN, vm.connectivityState.value)

        vm.disconnect()
        advanceUntilIdle()

        assertNull(vm.householdId.value)
        assertTrue(vm.connectedNodes.value.isEmpty())
    }

    @Test
    fun setRelayUrlUpdatesFlow() = runTest {
        val savedConfig = SavedHouseholdConfig(
            householdId = "hh-saved",
            householdName = "Saved",
            natsWsUrl = "ws://localhost:9222",
            relayUrl = "wss://old-relay.example.com",
        )
        val vm = createViewModel(this, savedConfig = savedConfig)

        vm.setRelayUrl("wss://new-relay.example.com")

        assertEquals("wss://new-relay.example.com", vm.relayUrl.value)
    }

    @Test
    fun setSavedHouseholdUrlUpdatesFlow() = runTest {
        val vm = createViewModel(this)

        vm.setSavedHouseholdUrl("ws://192.0.2.1:9222")

        assertEquals("ws://192.0.2.1:9222", vm.householdUrl.value)
    }

    @Test
    fun setSavedHouseholdUrlCreatesConfigWhenNoneExists() = runTest {
        val vm = createViewModel(this, mdnsResult = null, savedConfig = null)

        vm.setSavedHouseholdUrl("ws://192.0.2.1:9222")

        assertEquals("ws://192.0.2.1:9222", vm.householdUrl.value)
    }

    @Test
    fun setAutoDiscoverUpdatesFlow() = runTest {
        val vm = createViewModel(this)

        assertTrue(vm.autoDiscover.value)

        vm.setAutoDiscover(false)

        assertEquals(false, vm.autoDiscover.value)
    }

    @Test
    fun isConnectingDuringConnect() = runTest {
        val vm = createViewModel(this)

        assertEquals(false, vm.isConnecting.value)

        vm.connect()
        // After advanceUntilIdle, connect has completed
        advanceUntilIdle()

        assertEquals(false, vm.isConnecting.value)
    }

    @Test
    fun connectWithSavedConfigSetsHouseholdId() = runTest {
        val savedConfig = SavedHouseholdConfig(
            householdId = "hh-saved-456",
            householdName = "Saved Home",
            natsWsUrl = "ws://198.51.100.50:9222",
        )
        val vm = createViewModel(this, mdnsResult = null, savedConfig = savedConfig)

        vm.connect()
        advanceUntilIdle()

        assertEquals(ConnectivityState.CONNECTED_LAN, vm.connectivityState.value)
        assertEquals("hh-saved-456", vm.householdId.value)
    }

    @Test
    fun isAvailableReturnsTrue() = runTest {
        val vm = createViewModel(this)
        assertTrue(vm.isAvailable)
    }
}
