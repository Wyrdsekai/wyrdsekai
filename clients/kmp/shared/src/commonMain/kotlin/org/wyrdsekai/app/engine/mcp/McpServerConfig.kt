package org.wyrdsekai.app.engine.mcp

import kotlinx.serialization.Serializable

/**
 * Configuration for an MCP server that the phone can call directly.
 *
 */
@Serializable
data class McpServerConfig(
    /** Human-readable server identifier (e.g., "weather", "search"). */
    val name: String,
    /** Base URL for the MCP server (e.g., "https://api.open-meteo.com"). */
    val url: String,
    /** Key name in the local Safe for credential lookup. Null if no auth needed. */
    val credentialKey: String? = null,
    /** Maximum calls per hour for this server. */
    val rateLimit: Int = 60,
)

/**
 * Result of an MCP tool call.
 */
data class McpResult(
    val success: Boolean,
    val content: String? = null,
    val error: String? = null,
)

/**
 * Request sent via Between for proxied MCP calls (phone -> household server).
 *
 */
@Serializable
data class McpProxyRequest(
    val requestId: String,
    val server: String,
    val tool: String,
    val args: Map<String, String>,
    val nodeId: String,
)

/**
 * Response received via Between for proxied MCP calls (household server -> phone).
 */
@Serializable
data class McpProxyResponse(
    val requestId: String,
    val success: Boolean,
    val content: String? = null,
    val error: String? = null,
)

/**
 * Default server configurations for common low-risk services.
 * These are available for direct MCP at T3 (§17.3).
 * No real API keys — credentialKey references the local Safe.
 */
object DefaultMcpServers {
    val OPEN_METEO = McpServerConfig(
        name = "weather",
        url = "https://api.open-meteo.com/v1",
        credentialKey = null, // Free API, no key needed
        rateLimit = 120,
    )

    val WTTR_IN = McpServerConfig(
        name = "weather-text",
        url = "https://wttr.in",
        credentialKey = null,
        rateLimit = 60,
    )

    val BRAVE_SEARCH = McpServerConfig(
        name = "search",
        url = "https://api.search.brave.com/res/v1",
        credentialKey = "brave_api_key",
        rateLimit = 60,
    )

    val DUCKDUCKGO = McpServerConfig(
        name = "search-ddg",
        url = "https://api.duckduckgo.com",
        credentialKey = null, // Instant Answer API is free
        rateLimit = 60,
    )

    val WORLD_TIME = McpServerConfig(
        name = "time",
        url = "http://worldtimeapi.org/api",
        credentialKey = null,
        rateLimit = 120,
    )

    /** All default servers by name. */
    val ALL: Map<String, McpServerConfig> = listOf(
        OPEN_METEO, WTTR_IN, BRAVE_SEARCH, DUCKDUCKGO, WORLD_TIME,
    ).associateBy { it.name }
}
