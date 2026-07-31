/**
 * ServerClient — thin HTTP client for the wyrdsekai server's MCP REST API.
 *
 * Provides:
 *   - probe()             — checks if a URL hosts a wyrdsekai server (calls /api/auth/status)
 *   - registerAndLogin()  — auto-create an anonymous account, then log in via /api/mcp/login
 *   - login()             — log in an existing account
 *   - tell()              — POST /api/mcp/tell (handles cross-zone via server's CrossZoneTellService)
 *   - doCommand()         — POST /api/mcp/do (say/emote/use/take/drop)
 *
 * All authenticated calls use `Authorization: Bearer <mcpToken>`. Token is
 * cached in the instance and refreshed on 401. Methods throw on network
 * failure; HTTP errors are returned as { ok: false, error } so the caller
 * can decide whether to fall back to local handling.
 */

export interface AuthOk {
  token: string;
  userId: string;
  username: string;
}

export interface ServerStatus {
  hasUsers: boolean;
  openRegistration: boolean;
}

export interface McpResult<T = string> {
  ok: boolean;
  data?: T;
  error?: string;
  status?: number;
}

/** Random suffix for generating anonymous phone usernames. */
function randomSuffix(len = 8): string {
  const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
  let s = '';
  for (let i = 0; i < len; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return s;
}

export class ServerClient {
  private readonly baseUrl: string;
  private mcpToken: string | null = null;

  constructor(baseUrl: string, mcpToken: string | null = null) {
    // Strip trailing slash for clean URL concatenation
    this.baseUrl = baseUrl.replace(/\/+$/, '');
    this.mcpToken = mcpToken;
  }

  getToken(): string | null {
    return this.mcpToken;
  }

  /**
   * Probe a URL to determine whether it hosts a wyrdsekai server.
   * Returns server status on 200, null otherwise. Fast (~100ms) and
   * non-mutating — safe to call repeatedly.
   */
  async probe(timeoutMs = 5000): Promise<ServerStatus | null> {
    try {
      const ctrl = new AbortController();
      const t = setTimeout(() => ctrl.abort(), timeoutMs);
      const resp = await fetch(`${this.baseUrl}/api/auth/status`, {
        method: 'GET',
        signal: ctrl.signal,
      });
      clearTimeout(t);
      if (!resp.ok) return null;
      return (await resp.json()) as ServerStatus;
    } catch {
      return null;
    }
  }

  /**
   * Auto-create an anonymous phone account and log in.
   *
   * Username is `phone-<companion-name>-<8-char-random>` to keep accounts
   * visually distinct. Password is a 32-char random string stored alongside
   * the token so the same identity can be re-used across sessions. Returns
   * the credentials so the caller can persist them.
   */
  async registerAndLogin(companionName: string): Promise<{
    creds: { username: string; password: string };
    auth: AuthOk;
  }> {
    const username = `phone-${companionName.toLowerCase().replace(/[^a-z0-9]/g, '')}-${randomSuffix()}`;
    // 32 hex chars is plenty for an anonymous account
    let password = '';
    for (let i = 0; i < 32; i++) {
      password += Math.floor(Math.random() * 16).toString(16);
    }
    const displayName = companionName + "'s phone";

    // First register the account (idempotent if username collision — we'd
    // retry with new suffix, but collision odds at 36^8 are negligible).
    const regResp = await fetch(`${this.baseUrl}/api/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, display_name: displayName }),
    });
    if (!regResp.ok && regResp.status !== 409) {
      throw new Error(`Register failed: ${regResp.status} ${regResp.statusText}`);
    }
    // Now log in via MCP login (which also enters the user into nexus + returns token)
    return { creds: { username, password }, auth: await this.loginInternal(username, password) };
  }

  async login(username: string, password: string): Promise<AuthOk> {
    return this.loginInternal(username, password);
  }

  private async loginInternal(username: string, password: string): Promise<AuthOk> {
    const resp = await fetch(`${this.baseUrl}/api/mcp/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    if (!resp.ok) {
      throw new Error(`Login failed: ${resp.status} ${resp.statusText}`);
    }
    const body = await resp.json();
    // McpResponse.okWithToken returns { ok: true, token, data: {...room} }
    const token = body.token as string | undefined;
    if (!token) throw new Error('Login response missing token');
    // userId/username come from the auth subsystem — server doesn't echo them
    // in the MCP login response, so we trust the request-side values.
    this.mcpToken = token;
    return { token, userId: username, username };
  }

  /** POST /api/mcp/tell — server routes cross-zone via CrossZoneTellService when target is dotted. */
  async tell(target: string, message: string): Promise<McpResult<string>> {
    if (!this.mcpToken) return { ok: false, error: 'Not logged in', status: 401 };
    try {
      const resp = await fetch(`${this.baseUrl}/api/mcp/tell`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${this.mcpToken}`,
        },
        body: JSON.stringify({ target, message }),
      });
      const body = await resp.json().catch(() => ({}));
      if (!resp.ok) {
        return { ok: false, error: body?.error ?? `HTTP ${resp.status}`, status: resp.status };
      }
      // McpResponse.ok wraps data; tell endpoint returns the companion's
      // response (or "(delivered to ...)" for cross-zone).
      const text = typeof body?.data === 'string'
        ? body.data
        : typeof body?.response === 'string'
          ? body.response
          : JSON.stringify(body?.data ?? body ?? {});
      return { ok: true, data: text, status: resp.status };
    } catch (e) {
      return { ok: false, error: e instanceof Error ? e.message : String(e) };
    }
  }

  /** POST /api/mcp/do — general command (say, emote, use, take, drop). */
  async doCommand(command: string): Promise<McpResult<string>> {
    if (!this.mcpToken) return { ok: false, error: 'Not logged in', status: 401 };
    try {
      const resp = await fetch(`${this.baseUrl}/api/mcp/do`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${this.mcpToken}`,
        },
        body: JSON.stringify({ command }),
      });
      const body = await resp.json().catch(() => ({}));
      if (!resp.ok) {
        return { ok: false, error: body?.error ?? `HTTP ${resp.status}`, status: resp.status };
      }
      const text = typeof body?.data === 'string'
        ? body.data
        : JSON.stringify(body?.data ?? body ?? {});
      return { ok: true, data: text, status: resp.status };
    } catch (e) {
      return { ok: false, error: e instanceof Error ? e.message : String(e) };
    }
  }

  /**
   * POST /api/study/journal — write a journal entry for the given user DID.
   *
   * StudyRoutes endpoint is currently auth-free (takes user DID in body),
   * not Bearer-gated. The phone caller supplies its persisted @wyrd_user_id.
   * Returns the new entry id on success.
   */
  async writeJournal(userId: string, content: string, isPrivate = false): Promise<McpResult<string>> {
    try {
      const resp = await fetch(`${this.baseUrl}/api/study/journal`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // Server's JournalRequest record uses `isPrivate` (Jackson does NOT
        // coerce JSON `private` → record field `isPrivate`). Mismatch produced
        // an HTTP 400 with `Unrecognized field "private"` until 2026-05-11.
        body: JSON.stringify({ user: userId, content, isPrivate }),
      });
      const body = await resp.json().catch(() => ({}));
      if (!resp.ok) {
        return { ok: false, error: body?.error ?? `HTTP ${resp.status}`, status: resp.status };
      }
      return { ok: true, data: `Journal entry saved (${body?.id ?? 'ok'}).`, status: resp.status };
    } catch (e) {
      return { ok: false, error: e instanceof Error ? e.message : String(e) };
    }
  }

  /**
   * GET /api/library/search — query the household's knowledge base.
   *
   * Hits the shared library Lucene index regardless of which room the user
   * is in. Returns formatted prose so the caller can drop it straight into
   * the prose log.
   */
  async searchLibrary(query: string, limit = 5): Promise<McpResult<string>> {
    try {
      const url = `${this.baseUrl}/api/library/search?q=${encodeURIComponent(query)}&limit=${limit}`;
      const resp = await fetch(url, { method: 'GET' });
      const body = await resp.json().catch(() => ({}));
      if (!resp.ok) {
        return { ok: false, error: body?.error ?? `HTTP ${resp.status}`, status: resp.status };
      }
      const results = Array.isArray(body?.results) ? body.results : [];
      if (results.length === 0) {
        return { ok: true, data: `No library results for "${query}".`, status: resp.status };
      }
      const lines = [`Library results for "${query}" (${results.length}):`];
      for (const r of results) {
        const title = r?.title || r?.source || 'untitled';
        const snippet = (r?.text || r?.snippet || '').slice(0, 180).replace(/\s+/g, ' ');
        lines.push(`  • ${title}${snippet ? ` — ${snippet}…` : ''}`);
      }
      return { ok: true, data: lines.join('\n'), status: resp.status };
    } catch (e) {
      return { ok: false, error: e instanceof Error ? e.message : String(e) };
    }
  }
}
