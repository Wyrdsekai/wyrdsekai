package org.wyrdsekai.core.mcp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Configuration for a registered MCP service (§86.3).
 * Loaded from zone configuration JSON, hot-reloadable.
 *
 * @param id               Unique service identifier (e.g., "searxng", "home-assistant")
 * @param name             Human-readable name
 * @param transport        Transport type: "http", "stdio", "websocket"
 * @param endpoint         Server URL or command
 * @param tier             Cost tier: "local", "keyed", "metered"
 * @param auth             Auth configuration (type, safeKey, header)
 * @param rateLimitOverride  Per-service rate limit overrides
 * @param enabled          Whether this service is currently active
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpServiceConfig(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("transport") String transport,
    @JsonProperty("endpoint") String endpoint,
    @JsonProperty("tier") String tier,
    @JsonProperty("auth") AuthConfig auth,
    @JsonProperty("rate_limit_override") Map<String, Integer> rateLimitOverride,
    @JsonProperty("enabled") boolean enabled
) {
    @JsonCreator
    public McpServiceConfig {}

    /** Whether this service requires API keys. */
    public boolean requiresAuth() {
        return auth != null && auth.safeKey() != null;
    }

    /** Whether this service has per-use costs. */
    public boolean isMetered() {
        return "metered".equals(tier);
    }

    /** Whether this service is free (local). */
    public boolean isLocal() {
        return "local".equals(tier);
    }

    /**
     * Auth configuration for a service.
     *
     * @param type    Auth type: "bearer", "api_key", "basic"
     * @param safeKey Key name in TheSafe for retrieving the credential
     * @param header  HTTP header to inject (default: "Authorization")
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthConfig(
        @JsonProperty("type") String type,
        @JsonProperty("safe_key") String safeKey,
        @JsonProperty("header") String header
    ) {
        @JsonCreator
        public AuthConfig {}
    }
}
