/**
 * Lightweight MCP gateway for the phone node.
 *
 * Supports two call modes:
 * - **Direct**: fetch() call from phone to MCP server (T3, low-risk services)
 * - **Proxy**: Request forwarded via Between to household server (T2+)
 *
 * Rate limiting is per-server with hourly reset windows.
 *
 */
import type { BetweenClient } from '../between/BetweenClient';
import type {
  McpServerConfig,
  McpResult,
  McpProxyRequest,
  McpProxyResponse,
} from './types';
import { DEFAULT_MCP_SERVERS } from './types';

/** Rate limit window: 1 hour in milliseconds. */
const RATE_LIMIT_WINDOW_MS = 3_600_000;

interface RateLimitEntry {
  count: number;
  windowStartMs: number;
}

export class McpGatewayLite {
  /** Registry of allowed direct MCP servers. */
  private servers = new Map<string, McpServerConfig>();

  /** Per-server call counts for rate limiting. */
  private rateLimitState = new Map<string, RateLimitEntry>();

  /** Optional Between client for proxy calls. */
  betweenClient: BetweenClient | null = null;

  /** Node ID for proxy request routing. */
  nodeId = 'unknown';

  /** Household ID for Between subject construction. */
  householdId = '';

  /** Pending proxy responses keyed by requestId. */
  private pendingProxyResponses = new Map<
    string,
    { resolve: (result: McpResult) => void; reject: (err: Error) => void }
  >();

  /** Unsubscribe function for proxy response subscription. */
  private proxyResponseUnsub: (() => void) | null = null;

  /** Counter for generating unique request IDs. */
  private requestCounter = 0;

  /**
   * Register an MCP server for direct calls.
   */
  registerServer(config: McpServerConfig): void {
    this.servers.set(config.name, config);
  }

  /**
   * Register all default MCP servers.
   */
  registerDefaults(): void {
    for (const config of Object.values(DEFAULT_MCP_SERVERS)) {
      this.registerServer(config);
    }
  }

  /**
   * Get a registered server config by name.
   */
  getServer(name: string): McpServerConfig | null {
    return this.servers.get(name) ?? null;
  }

  /**
   * Call an MCP server directly via fetch().
   *
   * @param server Server name (must be registered)
   * @param tool Tool/endpoint name
   * @param args Tool arguments
   * @param apiKey Optional API key (from local Safe lookup)
   */
  async callDirect(
    server: string,
    tool: string,
    args: Record<string, string>,
    apiKey?: string,
  ): Promise<McpResult> {
    const config = this.servers.get(server);
    if (!config) {
      return { success: false, content: null, error: `Server not registered: ${server}` };
    }

    if (!this.checkRateLimit(config.name, config.rateLimit)) {
      return {
        success: false,
        content: null,
        error: `Rate limit exceeded for ${server} (${config.rateLimit}/hour)`,
      };
    }

    if (config.credentialKey && !apiKey) {
      return {
        success: false,
        content: null,
        error: `API key required for ${server} but not provided`,
      };
    }

    try {
      const requestBody = JSON.stringify({
        tool,
        arguments: args,
      });

      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
      };
      if (apiKey) {
        headers['Authorization'] = `Bearer ${apiKey}`;
      }

      const response = await fetch(`${config.url}/${tool}`, {
        method: 'POST',
        headers,
        body: requestBody,
      });

      const body = await response.text();
      return {
        success: response.ok,
        content: body,
        error: response.ok ? null : `HTTP ${response.status}`,
      };
    } catch (e) {
      return {
        success: false,
        content: null,
        error: `MCP call failed: ${e instanceof Error ? e.message : String(e)}`,
      };
    }
  }

  /**
   * Call an MCP server via household proxy (Between).
   *
   */
  async callProxy(
    server: string,
    tool: string,
    args: Record<string, string>,
    timeoutMs = 30_000,
  ): Promise<McpResult> {
    if (!this.betweenClient) {
      return { success: false, content: null, error: 'No Between client configured for proxy calls' };
    }

    if (!this.betweenClient.isConnected) {
      return { success: false, content: null, error: 'Between client not connected' };
    }

    if (!this.householdId) {
      return { success: false, content: null, error: 'Household ID not configured' };
    }

    const requestId = this.generateRequestId();
    const request: McpProxyRequest = {
      requestId,
      server,
      tool,
      args,
      nodeId: this.nodeId,
    };

    this.ensureProxyResponseSubscription();

    return new Promise<McpResult>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingProxyResponses.delete(requestId);
        resolve({ success: false, content: null, error: 'Proxy call timed out' });
      }, timeoutMs);

      this.pendingProxyResponses.set(requestId, {
        resolve: (result) => {
          clearTimeout(timer);
          this.pendingProxyResponses.delete(requestId);
          resolve(result);
        },
        reject: (err) => {
          clearTimeout(timer);
          this.pendingProxyResponses.delete(requestId);
          reject(err);
        },
      });

      const payload = new TextEncoder().encode(JSON.stringify(request));
      this.betweenClient!.publish(
        `between.${this.householdId}.mcp.request`,
        payload,
      );
    });
  }

  /**
   * Subscribe to proxy response subject on the Between client.
   */
  private ensureProxyResponseSubscription(): void {
    if (this.proxyResponseUnsub) return;
    if (!this.betweenClient) return;

    this.proxyResponseUnsub = this.betweenClient.subscribe(
      `between.${this.householdId}.mcp.${this.nodeId}.response`,
      (_subject, data) => {
        try {
          const response: McpProxyResponse = JSON.parse(
            new TextDecoder().decode(data),
          );
          const pending = this.pendingProxyResponses.get(response.requestId);
          if (pending) {
            pending.resolve({
              success: response.success,
              content: response.content,
              error: response.error,
            });
          }
        } catch {
          // Malformed response — ignore
        }
      },
    );
  }

  /**
   * Check and increment rate limit for a server.
   * Returns true if the call is allowed, false if rate limited.
   */
  checkRateLimit(server: string, limit: number): boolean {
    const now = Date.now();
    const entry = this.rateLimitState.get(server);

    if (!entry || now - entry.windowStartMs >= RATE_LIMIT_WINDOW_MS) {
      this.rateLimitState.set(server, { count: 1, windowStartMs: now });
      return true;
    }

    if (entry.count >= limit) {
      return false;
    }

    this.rateLimitState.set(server, { ...entry, count: entry.count + 1 });
    return true;
  }

  /**
   * Reset rate limit state. Useful for testing.
   */
  resetRateLimits(): void {
    this.rateLimitState.clear();
  }

  /**
   * Generate a unique request ID.
   */
  private generateRequestId(): string {
    return `mcp-${++this.requestCounter}-${Date.now()}`;
  }

  /**
   * Shut down the gateway. Cancels proxy response subscription.
   */
  shutdown(): void {
    this.proxyResponseUnsub?.();
    this.proxyResponseUnsub = null;
    for (const [, pending] of this.pendingProxyResponses) {
      pending.reject(new Error('Gateway shutting down'));
    }
    this.pendingProxyResponses.clear();
  }
}
