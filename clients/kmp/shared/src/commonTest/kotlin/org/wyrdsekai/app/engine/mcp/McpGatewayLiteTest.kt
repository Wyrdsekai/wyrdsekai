package org.wyrdsekai.app.engine.mcp

import org.wyrdsekai.app.engine.between.InMemoryBetweenClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpGatewayLiteTest {

    @Test
    fun rateLimiterAllowsCallsWithinLimit() {
        val gateway = McpGatewayLite()
        // Allow 3 calls per hour
        assertTrue(gateway.checkRateLimit("test-server", 3))
        assertTrue(gateway.checkRateLimit("test-server", 3))
        assertTrue(gateway.checkRateLimit("test-server", 3))
    }

    @Test
    fun rateLimiterBlocksExcessCalls() {
        val gateway = McpGatewayLite()
        // Allow 2 calls per hour
        assertTrue(gateway.checkRateLimit("test-server", 2))
        assertTrue(gateway.checkRateLimit("test-server", 2))
        // Third call should be blocked
        assertFalse(gateway.checkRateLimit("test-server", 2))
    }

    @Test
    fun rateLimiterTracksServersIndependently() {
        val gateway = McpGatewayLite()
        // Each server has its own counter
        assertTrue(gateway.checkRateLimit("server-a", 1))
        assertTrue(gateway.checkRateLimit("server-b", 1))
        // server-a is at limit, server-b still has room
        assertFalse(gateway.checkRateLimit("server-a", 1))
        // server-b can still accept one more call of its own limit
        assertFalse(gateway.checkRateLimit("server-b", 1))
    }

    @Test
    fun rateLimiterResetClearsState() {
        val gateway = McpGatewayLite()
        assertTrue(gateway.checkRateLimit("test-server", 1))
        assertFalse(gateway.checkRateLimit("test-server", 1))
        gateway.resetRateLimits()
        // After reset, calls are allowed again
        assertTrue(gateway.checkRateLimit("test-server", 1))
    }

    @Test
    fun mcpResultConstruction() {
        val success = McpResult(success = true, content = """{"temp": 22}""")
        assertTrue(success.success)
        assertEquals("""{"temp": 22}""", success.content)
        assertNull(success.error)

        val failure = McpResult(success = false, error = "Server unreachable")
        assertFalse(failure.success)
        assertNull(failure.content)
        assertEquals("Server unreachable", failure.error)
    }

    @Test
    fun serverConfigRegistration() {
        val gateway = McpGatewayLite()
        assertNull(gateway.getServer("weather"))

        gateway.registerServer(
            McpServerConfig(
                name = "weather",
                url = "https://api.open-meteo.com/v1",
                rateLimit = 120,
            )
        )

        val config = gateway.getServer("weather")
        assertNotNull(config)
        assertEquals("weather", config.name)
        assertEquals("https://api.open-meteo.com/v1", config.url)
        assertEquals(120, config.rateLimit)
        assertNull(config.credentialKey)
    }

    @Test
    fun registerDefaultsPopulatesAllServers() {
        val gateway = McpGatewayLite()
        gateway.registerDefaults()

        assertNotNull(gateway.getServer("weather"))
        assertNotNull(gateway.getServer("weather-text"))
        assertNotNull(gateway.getServer("search"))
        assertNotNull(gateway.getServer("search-ddg"))
        assertNotNull(gateway.getServer("time"))
    }

    @Test
    fun defaultServersHaveCorrectConfig() {
        assertEquals("weather", DefaultMcpServers.OPEN_METEO.name)
        assertNull(DefaultMcpServers.OPEN_METEO.credentialKey) // free API
        assertEquals(120, DefaultMcpServers.OPEN_METEO.rateLimit)

        assertEquals("search", DefaultMcpServers.BRAVE_SEARCH.name)
        assertEquals("brave_api_key", DefaultMcpServers.BRAVE_SEARCH.credentialKey)
    }

    @Test
    fun mcpProxyRequestSerialization() {
        val request = McpProxyRequest(
            requestId = "mcp-1-123456",
            server = "iot",
            tool = "lights.toggle",
            args = mapOf("room" to "living-room", "state" to "on"),
            nodeId = "phone-abc",
        )

        assertEquals("mcp-1-123456", request.requestId)
        assertEquals("iot", request.server)
        assertEquals("lights.toggle", request.tool)
        assertEquals("phone-abc", request.nodeId)
        assertEquals(2, request.args.size)
    }

    @Test
    fun mcpProxyResponseConstruction() {
        val success = McpProxyResponse(
            requestId = "mcp-1-123456",
            success = true,
            content = """{"status":"toggled"}""",
        )
        assertTrue(success.success)
        assertNotNull(success.content)
        assertNull(success.error)

        val failure = McpProxyResponse(
            requestId = "mcp-2-789",
            success = false,
            error = "Device offline",
        )
        assertFalse(failure.success)
        assertNull(failure.content)
        assertEquals("Device offline", failure.error)
    }

    @Test
    fun generateRequestIdIsUnique() {
        val id1 = McpGatewayLite.generateRequestId()
        val id2 = McpGatewayLite.generateRequestId()
        assertTrue(id1 != id2)
        assertTrue(id1.startsWith("mcp-"))
        assertTrue(id2.startsWith("mcp-"))
    }

    @Test
    fun shutdownCleansUpState() {
        val gateway = McpGatewayLite()
        gateway.betweenClient = InMemoryBetweenClient()
        gateway.householdId = "test-household"
        gateway.nodeId = "test-node"

        // Shutdown should not throw
        gateway.shutdown()
    }
}
