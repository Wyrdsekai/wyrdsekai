package org.wyrdsekai.app.engine.discovery

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for the MdnsScanner interface and DefaultMdnsScanner.
 *
 * Platform-specific scanner tests (Android NsdManager, iOS NSNetServiceBrowser,
 * Desktop multicast) require real network services and cannot be unit tested
 * in isolation. These tests verify interface contracts and the no-op default.
 *
 * For integration testing of mDNS discovery, see the E2E test suite (T2+)
 * which runs against a real household server with mDNS advertisement.
 */
class MdnsScannerTest {

    // ── DefaultMdnsScanner ────────────────────────────────────────────────

    @Test
    fun defaultScannerReturnsNull() = runTest {
        val scanner = DefaultMdnsScanner()
        assertNull(scanner.scan())
    }

    @Test
    fun defaultScannerReturnsNullWithCustomServiceType() = runTest {
        val scanner = DefaultMdnsScanner()
        assertNull(scanner.scan(serviceType = "_custom._tcp.local"))
    }

    @Test
    fun defaultScannerReturnsNullWithShortTimeout() = runTest {
        val scanner = DefaultMdnsScanner()
        assertNull(scanner.scan(timeoutMs = 1))
    }

    @Test
    fun defaultScannerReturnsNullWithLongTimeout() = runTest {
        val scanner = DefaultMdnsScanner()
        // This should return immediately (not wait the full timeout) since it's a no-op
        assertNull(scanner.scan(timeoutMs = 60_000))
    }

    // ── Service type constant ──────────────────────────────────────────────

    @Test
    fun serviceTypeConstantIsCorrect() {
        assertEquals("_wyrdsekai._tcp.local", MdnsScanner.SERVICE_TYPE)
    }

    // ── DiscoveredHousehold ────────────────────────────────────────────────

    @Test
    fun discoveredHouseholdDefaults() {
        val household = DiscoveredHousehold(
            householdId = "hh-1",
            householdName = "Test",
            natsWsUrl = "ws://localhost:9222",
        )
        assertNull(household.relayUrl)
        assertNull(household.relayToken)
        assertEquals("1.0", household.version)
    }

    @Test
    fun discoveredHouseholdWithAllFields() {
        val household = DiscoveredHousehold(
            householdId = "hh-full",
            householdName = "Full Home",
            natsWsUrl = "ws://198.51.100.100:9222",
            relayUrl = "wss://relay.example.com:443",
            relayToken = "secret-token",
            version = "2.0",
        )
        assertEquals("hh-full", household.householdId)
        assertEquals("Full Home", household.householdName)
        assertEquals("ws://198.51.100.100:9222", household.natsWsUrl)
        assertEquals("wss://relay.example.com:443", household.relayUrl)
        assertEquals("secret-token", household.relayToken)
        assertEquals("2.0", household.version)
    }

    // ── Fixed scanner for testing ──────────────────────────────────────────

    @Test
    fun fixedScannerReturnsConfiguredResult() = runTest {
        val expected = DiscoveredHousehold(
            householdId = "hh-fixed",
            householdName = "Fixed",
            natsWsUrl = "ws://192.0.2.1:9222",
        )
        val scanner = object : MdnsScanner {
            override suspend fun scan(serviceType: String, timeoutMs: Long) = expected
        }

        val result = scanner.scan()
        assertEquals(expected, result)
    }

    @Test
    fun fixedScannerPassesThroughParameters() = runTest {
        var capturedType: String? = null
        var capturedTimeout: Long? = null

        val scanner = object : MdnsScanner {
            override suspend fun scan(serviceType: String, timeoutMs: Long): DiscoveredHousehold? {
                capturedType = serviceType
                capturedTimeout = timeoutMs
                return null
            }
        }

        scanner.scan(serviceType = "_custom._tcp.local", timeoutMs = 1234)
        assertEquals("_custom._tcp.local", capturedType)
        assertEquals(1234L, capturedTimeout)
    }

    @Test
    fun defaultParametersUsedWhenNotSpecified() = runTest {
        var capturedType: String? = null
        var capturedTimeout: Long? = null

        val scanner = object : MdnsScanner {
            override suspend fun scan(serviceType: String, timeoutMs: Long): DiscoveredHousehold? {
                capturedType = serviceType
                capturedTimeout = timeoutMs
                return null
            }
        }

        scanner.scan()
        assertEquals(MdnsScanner.SERVICE_TYPE, capturedType)
        assertEquals(5_000L, capturedTimeout)
    }
}
