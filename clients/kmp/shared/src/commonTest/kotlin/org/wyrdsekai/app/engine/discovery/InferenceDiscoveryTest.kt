package org.wyrdsekai.app.engine.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for InferenceDiscovery pure functions (bestEndpoint).
 *
 * Network-dependent tests (discover(), probeWyrdsekai()) are excluded here
 * since they require real HTTP endpoints. Those are covered by E2E tests.
 */
class InferenceDiscoveryTest {

    // ── bestEndpoint ─────────────────────────────────────────────────────

    @Test
    fun bestEndpointReturnsNullForEmptyList() {
        val result = InferenceDiscovery.bestEndpoint(emptyList())
        assertNull(result, "bestEndpoint on empty list should return null")
    }

    @Test
    fun bestEndpointReturnsSingleEndpoint() {
        val endpoint = DiscoveredServer(
            url = "http://198.51.100.10:7070",
            name = "household-server",
            label = "Household (198.51.100.10)",
        )
        val result = InferenceDiscovery.bestEndpoint(listOf(endpoint))
        assertEquals(endpoint, result)
    }

    @Test
    fun bestEndpointPrefersSavedOverDiscovered() {
        val saved = DiscoveredServer(
            url = "http://saved.example.com:7070",
            name = "saved-server",
            label = "Saved: saved-server",
        )
        val discovered = DiscoveredServer(
            url = "http://198.51.100.10:7070",
            name = "household-server",
            label = "Household (198.51.100.10)",
        )
        val result = InferenceDiscovery.bestEndpoint(listOf(discovered, saved))
        // bestEndpoint prefers saved (label starts with "Saved") over discovered
        assertEquals(saved, result)
    }

    // ── DiscoveredServer data class ──────────────────────────────────────

    @Test
    fun discoveredServerConstruction() {
        val server = DiscoveredServer(
            url = "http://192.0.2.1:7070",
            name = "test-server",
            label = "Test Server",
        )
        assertEquals("http://192.0.2.1:7070", server.url)
        assertEquals("test-server", server.name)
        assertEquals("Test Server", server.label)
    }

    @Test
    fun discoveredServerEquality() {
        val a = DiscoveredServer("http://a:7070", "a", "A")
        val b = DiscoveredServer("http://a:7070", "a", "A")
        assertEquals(a, b)
    }

    // ── DiscoveredInference typealias ────────────────────────────────────

    @Test
    fun discoveredInferenceAliasWorks() {
        val server: DiscoveredInference = DiscoveredServer(
            url = "http://198.51.100.5:7070",
            name = "alias-test",
            label = "Alias Test",
        )
        assertEquals("http://198.51.100.5:7070", server.url)
    }

    @Test
    fun bestEndpointWithMultipleServers() {
        val server1 = DiscoveredServer("http://198.51.100.5:7070", "server-1", "Server 1")
        val server2 = DiscoveredServer("http://198.51.100.10:7070", "server-2", "Server 2")
        val server3 = DiscoveredServer("http://198.51.100.20:7070", "server-3", "Server 3")

        val result = InferenceDiscovery.bestEndpoint(listOf(server1, server2, server3))
        // Should return first (highest priority)
        assertEquals(server1, result)
    }
}
