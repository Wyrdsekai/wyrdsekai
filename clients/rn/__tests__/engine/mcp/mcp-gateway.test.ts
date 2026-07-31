import { McpGatewayLite } from '../../../src/engine/mcp/McpGatewayLite';
import { DEFAULT_MCP_SERVERS } from '../../../src/engine/mcp/types';
import type { McpServerConfig, McpResult } from '../../../src/engine/mcp/types';
import { InMemoryBetweenClient } from '../../../src/engine/between/BetweenClient';

describe('McpGatewayLite', () => {
  let gateway: McpGatewayLite;

  beforeEach(() => {
    gateway = new McpGatewayLite();
  });

  afterEach(() => {
    gateway.shutdown();
  });

  describe('rate limiter', () => {
    it('allows calls within limit', () => {
      expect(gateway.checkRateLimit('test-server', 3)).toBe(true);
      expect(gateway.checkRateLimit('test-server', 3)).toBe(true);
      expect(gateway.checkRateLimit('test-server', 3)).toBe(true);
    });

    it('blocks excess calls', () => {
      expect(gateway.checkRateLimit('test-server', 2)).toBe(true);
      expect(gateway.checkRateLimit('test-server', 2)).toBe(true);
      expect(gateway.checkRateLimit('test-server', 2)).toBe(false);
    });

    it('tracks servers independently', () => {
      expect(gateway.checkRateLimit('server-a', 1)).toBe(true);
      expect(gateway.checkRateLimit('server-b', 1)).toBe(true);
      expect(gateway.checkRateLimit('server-a', 1)).toBe(false);
      expect(gateway.checkRateLimit('server-b', 1)).toBe(false);
    });

    it('resets state', () => {
      expect(gateway.checkRateLimit('test-server', 1)).toBe(true);
      expect(gateway.checkRateLimit('test-server', 1)).toBe(false);
      gateway.resetRateLimits();
      expect(gateway.checkRateLimit('test-server', 1)).toBe(true);
    });
  });

  describe('server registration', () => {
    it('registers and retrieves server config', () => {
      expect(gateway.getServer('weather')).toBeNull();

      gateway.registerServer({
        name: 'weather',
        url: 'https://api.open-meteo.com/v1',
        credentialKey: null,
        rateLimit: 120,
      });

      const config = gateway.getServer('weather');
      expect(config).not.toBeNull();
      expect(config!.name).toBe('weather');
      expect(config!.url).toBe('https://api.open-meteo.com/v1');
      expect(config!.rateLimit).toBe(120);
    });

    it('registers all defaults', () => {
      gateway.registerDefaults();
      expect(gateway.getServer('weather')).not.toBeNull();
      expect(gateway.getServer('weather-text')).not.toBeNull();
      expect(gateway.getServer('search')).not.toBeNull();
      expect(gateway.getServer('search-ddg')).not.toBeNull();
      expect(gateway.getServer('time')).not.toBeNull();
    });
  });

  describe('direct call', () => {
    it('returns error for unregistered server', async () => {
      const result = await gateway.callDirect('nonexistent', 'tool', {});
      expect(result.success).toBe(false);
      expect(result.error).toContain('not registered');
    });

    it('returns error when API key required but not provided', async () => {
      gateway.registerServer({
        name: 'search',
        url: 'https://api.example.com',
        credentialKey: 'api_key',
        rateLimit: 60,
      });

      const result = await gateway.callDirect('search', 'query', { q: 'test' });
      expect(result.success).toBe(false);
      expect(result.error).toContain('API key required');
    });

    it('returns error when rate limited', async () => {
      gateway.registerServer({
        name: 'limited',
        url: 'https://api.example.com',
        credentialKey: null,
        rateLimit: 1,
      });

      // Use up the rate limit via checkRateLimit
      gateway.checkRateLimit('limited', 1);

      const result = await gateway.callDirect('limited', 'tool', {});
      expect(result.success).toBe(false);
      expect(result.error).toContain('Rate limit exceeded');
    });

    it('constructs correct HTTP request', async () => {
      // Mock fetch
      const fetchSpy = jest.fn().mockResolvedValue({
        ok: true,
        status: 200,
        text: () => Promise.resolve('{"result":"ok"}'),
      });
      global.fetch = fetchSpy;

      gateway.registerServer({
        name: 'test',
        url: 'https://api.test.com',
        credentialKey: null,
        rateLimit: 60,
      });

      const result = await gateway.callDirect('test', 'search', { q: 'hello' });

      expect(fetchSpy).toHaveBeenCalledTimes(1);
      const [url, options] = fetchSpy.mock.calls[0];
      expect(url).toBe('https://api.test.com/search');
      expect(options.method).toBe('POST');
      expect(JSON.parse(options.body)).toEqual({
        tool: 'search',
        arguments: { q: 'hello' },
      });

      expect(result.success).toBe(true);
      expect(result.content).toBe('{"result":"ok"}');
    });

    it('includes authorization header when API key provided', async () => {
      const fetchSpy = jest.fn().mockResolvedValue({
        ok: true,
        status: 200,
        text: () => Promise.resolve('ok'),
      });
      global.fetch = fetchSpy;

      gateway.registerServer({
        name: 'authed',
        url: 'https://api.test.com',
        credentialKey: 'key',
        rateLimit: 60,
      });

      await gateway.callDirect('authed', 'tool', {}, 'my-secret-key');

      const [, options] = fetchSpy.mock.calls[0];
      expect(options.headers['Authorization']).toBe('Bearer my-secret-key');
    });

    it('handles fetch failure gracefully', async () => {
      global.fetch = jest.fn().mockRejectedValue(new Error('Network error'));

      gateway.registerServer({
        name: 'failing',
        url: 'https://unreachable.com',
        credentialKey: null,
        rateLimit: 60,
      });

      const result = await gateway.callDirect('failing', 'tool', {});
      expect(result.success).toBe(false);
      expect(result.error).toContain('Network error');
    });
  });

  describe('proxy call', () => {
    it('returns error when no Between client', async () => {
      const result = await gateway.callProxy('iot', 'lights.on', {});
      expect(result.success).toBe(false);
      expect(result.error).toContain('No Between client');
    });

    it('returns error when Between not connected', async () => {
      const between = new InMemoryBetweenClient();
      gateway.betweenClient = between;
      // Not connected yet

      const result = await gateway.callProxy('iot', 'lights.on', {});
      expect(result.success).toBe(false);
      expect(result.error).toContain('not connected');
    });

    it('returns error when household ID not set', async () => {
      const between = new InMemoryBetweenClient();
      await between.connect('ws://localhost');
      gateway.betweenClient = between;

      const result = await gateway.callProxy('iot', 'lights.on', {});
      expect(result.success).toBe(false);
      expect(result.error).toContain('Household ID');
    });

    it('publishes proxy request to correct subject', async () => {
      const between = new InMemoryBetweenClient();
      await between.connect('ws://localhost');
      gateway.betweenClient = between;
      gateway.householdId = 'hh-123';
      gateway.nodeId = 'phone-abc';

      // Start proxy call (will time out since no response comes)
      const resultPromise = gateway.callProxy('iot', 'lights.on', { room: 'living' }, 100);

      // Check that request was published
      expect(between.published.length).toBe(1);
      expect(between.published[0].subject).toBe('between.hh-123.mcp.request');

      const payload = JSON.parse(new TextDecoder().decode(between.published[0].data));
      expect(payload.server).toBe('iot');
      expect(payload.tool).toBe('lights.on');
      expect(payload.args).toEqual({ room: 'living' });
      expect(payload.nodeId).toBe('phone-abc');

      // Wait for timeout
      const result = await resultPromise;
      expect(result.success).toBe(false);
      expect(result.error).toContain('timed out');
    });
  });

  describe('default servers', () => {
    it('has correct config for open-meteo', () => {
      const config = DEFAULT_MCP_SERVERS['weather'];
      expect(config.name).toBe('weather');
      expect(config.credentialKey).toBeNull();
      expect(config.rateLimit).toBe(120);
    });

    it('has correct config for brave search', () => {
      const config = DEFAULT_MCP_SERVERS['search'];
      expect(config.name).toBe('search');
      expect(config.credentialKey).toBe('brave_api_key');
    });

    it('includes all expected servers', () => {
      const names = Object.keys(DEFAULT_MCP_SERVERS);
      expect(names).toContain('weather');
      expect(names).toContain('weather-text');
      expect(names).toContain('search');
      expect(names).toContain('search-ddg');
      expect(names).toContain('time');
    });
  });
});
