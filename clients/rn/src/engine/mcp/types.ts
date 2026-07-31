/**
 * MCP Gateway types for the phone node.
 *
 * Matches KMP data classes:
 * - McpServerConfig: allowed MCP server configuration
 * - McpResult: result of an MCP tool call
 * - McpProxyRequest/Response: Between-proxied MCP call protocol
 *
 */

/** Configuration for an MCP server that the phone can call directly. */
export interface McpServerConfig {
  /** Human-readable server identifier (e.g., "weather", "search"). */
  name: string;
  /** Base URL for the MCP server. */
  url: string;
  /** Key name for credential lookup. Null if no auth needed. */
  credentialKey: string | null;
  /** Maximum calls per hour for this server. */
  rateLimit: number;
}

/** Result of an MCP tool call. */
export interface McpResult {
  success: boolean;
  content: string | null;
  error: string | null;
}

/** Request sent via Between for proxied MCP calls (phone -> household server). */
export interface McpProxyRequest {
  requestId: string;
  server: string;
  tool: string;
  args: Record<string, string>;
  nodeId: string;
}

/** Response received via Between for proxied MCP calls (household server -> phone). */
export interface McpProxyResponse {
  requestId: string;
  success: boolean;
  content: string | null;
  error: string | null;
}

/**
 * Default server configurations for common low-risk services.
 * Available for direct MCP at T3 (§17.3).
 */
export const DEFAULT_MCP_SERVERS: Record<string, McpServerConfig> = {
  weather: {
    name: 'weather',
    url: 'https://api.open-meteo.com/v1',
    credentialKey: null,
    rateLimit: 120,
  },
  'weather-text': {
    name: 'weather-text',
    url: 'https://wttr.in',
    credentialKey: null,
    rateLimit: 60,
  },
  search: {
    name: 'search',
    url: 'https://api.search.brave.com/res/v1',
    credentialKey: 'brave_api_key',
    rateLimit: 60,
  },
  'search-ddg': {
    name: 'search-ddg',
    url: 'https://api.duckduckgo.com',
    credentialKey: null,
    rateLimit: 60,
  },
  time: {
    name: 'time',
    url: 'http://worldtimeapi.org/api',
    credentialKey: null,
    rateLimit: 120,
  },
};
