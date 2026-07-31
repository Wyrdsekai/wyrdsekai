/**
 * NatsServerClient — phone-side NATS request/reply client for the wyrdsekai
 * relay's NATS WS-TLS surface.
 *
 * Replaces the HTTP McpRoutes path used by {@link ServerClient}. Each public
 * method maps to a server-side NATS subject:
 *
 *   login          → wyrd.zone.{zone}.mcp.login
 *   tell           → wyrd.zone.{zone}.mcp.tell
 *   writeJournal   → wyrd.zone.{zone}.study.journal           (write op)
 *   listJournal    → wyrd.zone.{zone}.study.journal           (op: "list")
 *   searchLibrary  → wyrd.zone.{zone}.library.search
 *
 * Not yet on NATS server-side (server still serves these over HTTP — pending
 * Phase 4 follow-ups):
 *   - mcp/do (say/emote/...)   (use ServerClient.doCommand)
 *
 * Transport: nats.ws WebSocket → wss://{relay}:4443 with household-CA leaf
 * cert. TLS verification + pinning piggy-backs on the existing OkHttp
 * HouseholdTrust pin (Android) — phones must call probeAndTrust against the
 * relay host before opening this connection, OR have the OS-level user cert
 * installed. iOS uses system trust + the CA in the keychain.
 */

import { connect, type NatsConnection, type Msg } from 'nats.ws';
import {
  createRelaySocket,
  nativeRelaySocketAvailable,
} from '../engine/between/RelaySocket';
import type { AuthOk, McpResult } from './ServerClient';
import type { BetweenClient, BetweenMessageHandler } from '../engine/between/BetweenClient';

/** Re-export the HTTP-client return types so callers can swap clients freely. */
export type NatsAuthOk = AuthOk & { role?: string; zoneId?: string };
export type NatsResult<T = string> = McpResult<T>;

/** Random suffix for generating anonymous phone usernames. */
function randomSuffix(len = 8): string {
  const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
  let s = '';
  for (let i = 0; i < len; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return s;
}

export interface NatsServerClientOptions {
  /**
   * Full wss:// URL to the relay's NATS WebSocket+TLS listener, e.g.
   * `wss://relay-node.example.com:4443`. The phone discovers this URL through
   * the existing relay pairing flow; the
   * household-CA trust pin must already be installed via probeAndTrust.
   */
  relayUrl: string;
  /** Zone ID for subject scoping: `wyrd.zone.{zoneId}.{op}`. */
  zoneId: string;
  /**
   * NATS credentials for this phone's account on the relay. Minted by the
   * relay sidecar during pairing (Phase 4b TODO: add `relay.register-phone`
   * subject). For now: caller supplies the user/pass pair.
   */
  user: string;
  password: string;
  /** Optional pre-existing MCP session token (skip login() on reconnect). */
  mcpToken?: string;
  /** Per-request timeout, ms. Default 5000. */
  requestTimeoutMs?: number;
}

export class NatsServerClient {
  private readonly opts: NatsServerClientOptions;
  private nc: NatsConnection | null = null;
  private mcpToken: string | null;
  private readonly timeout: number;

  constructor(opts: NatsServerClientOptions) {
    this.opts = opts;
    this.mcpToken = opts.mcpToken ?? null;
    this.timeout = opts.requestTimeoutMs ?? 5000;
  }

  getToken(): string | null {
    return this.mcpToken;
  }

  /**
   * expose this client's already-authenticated relay
   * connection as a raw pub/sub {@link BetweenClient}, so the phone terminal
   * can tunnel a FULL session over `wyrd.tunnel.{zone}.*` without opening a
   * second NATS connection or re-plumbing relay credentials. Returns null
   * until {@link connect} has succeeded.
   */
  asBetweenClient(): BetweenClient | null {
    const nc = this.nc;
    if (!nc) return null;
    return {
      get isConnected(): boolean {
        return !nc.isClosed();
      },
      async connect(_url: string): Promise<void> {
        /* already connected — the NatsServerClient owns the lifecycle */
      },
      async disconnect(): Promise<void> {
        /* lifecycle owned by NatsServerClient.disconnect() */
      },
      publish(subject: string, data: Uint8Array): void {
        try {
          nc.publish(subject, data);
        } catch {
          /* send failure is non-fatal; caller can check isConnected */
        }
      },
      subscribe(subject: string, handler: BetweenMessageHandler): () => void {
        const sub = nc.subscribe(subject, {
          callback: (_err, msg: Msg) => {
            if (msg) handler(msg.subject, msg.data);
          },
        });
        return () => {
          try {
            sub.unsubscribe();
          } catch {
            /* best effort */
          }
        };
      },
    };
  }

  /**
   * Open the NATS WebSocket connection. Idempotent — safe to call multiple
   * times. Must succeed before any other method is invoked. Times out after
   * 8s (deliberate — nats.ws can hang if the underlying WebSocket polyfill
   * has issues, and we'd rather surface the failure quickly than block UI).
   */
  async connect(): Promise<void> {
    if (this.nc) return;
    const connectPromise = connect({
      servers: this.opts.relayUrl,
      user: this.opts.user,
      pass: this.opts.password,
      // TLS to a relay-only zone uses a self-signed household leaf. On Android
      // the OkHttp-backed JS WebSocket pins it via the HouseholdTrust
      // TrustManager. On iOS the global WebSocket (SocketRocket) CANNOT pin
      // under New Architecture, so we hand nats.ws a `wsFactory` that returns
      // our native NSURLSessionWebSocketTask socket — its URLSessionDelegate
      // does the serverTrust pin against the household-CA allowlist (seeded
      // from the invite fingerprints in openZone). nats.ws drives it as a
      // binaryType='arraybuffer' WebSocket; RelaySocket base64-bridges frames.
      ...(nativeRelaySocketAvailable()
        ? {
            wsFactory: async (u: string) => ({
              socket: createRelaySocket(u) as unknown as WebSocket,
              encrypted: u.startsWith('wss://'),
            }),
          }
        : {}),
      name: 'wyrd-phone',
      reconnect: true,
      maxReconnectAttempts: -1,
      reconnectTimeWait: 2000,
      timeout: 6000, // nats.ws's internal connect timeout
      // A relay's nats-server advertises its OWN internal addresses (e.g.
      // 127.0.0.1:4222) via connect_urls for cluster failover. A phone must
      // NEVER chase those — they don't exist on the device, and trying them
      // churns the (pinned, working) relay connection. The phone only ever
      // talks to the relay it was handed in the invite. (Confirmed mover login
      // succeeds over the pinned wss; this just stops the post-login churn.)
      ignoreClusterUpdates: true,
    });
    const timeoutPromise = new Promise<NatsConnection>((_, reject) =>
      setTimeout(() => reject(new Error('connect() timed out after 8s')), 8000),
    );
    this.nc = await Promise.race([connectPromise, timeoutPromise]);
  }

  async disconnect(): Promise<void> {
    if (!this.nc) return;
    await this.nc.drain();
    this.nc = null;
  }

  // ── subjects ──

  private subject(op: string): string {
    return `wyrd.zone.${this.opts.zoneId}.${op}`;
  }

  /**
   * Send a request to a NATS subject and parse the JSON reply.
   * Always returns a Record — transport failures are shaped as
   * `{ ok: false, error: "..." }` so callers can use a single code path.
   */
  private async request(subject: string, body: object): Promise<Record<string, unknown>> {
    if (!this.nc) {
      return { ok: false, error: 'Not connected — call connect() first' };
    }
    try {
      const payload = new TextEncoder().encode(JSON.stringify(body));
      const msg: Msg = await this.nc.request(subject, payload, { timeout: this.timeout });
      const text = new TextDecoder().decode(msg.data);
      return JSON.parse(text) as Record<string, unknown>;
    } catch (e) {
      const err = e instanceof Error ? e.message : String(e);
      return { ok: false, error: `request-failed: ${err}` };
    }
  }

  // ── wyrd.discover.zone ──

  /**
   * Zone-agnostic discovery. The phone doesn't know its zone label yet
   * (fresh pairing, or stored zone was reset). Publish to the global
   * `wyrd.discover.zone` subject; the server replies with its zone id.
   * Use this once at connect time, then scope all subsequent requests
   * under `wyrd.zone.{returnedZoneId}.*`.
   */
  async discoverZone(): Promise<string | null> {
    await this.connect();
    const reply = await this.request('wyrd.discover.zone', {});
    if (!reply.ok) return null;
    return (reply.zoneId as string) ?? null;
  }

  /**
   * Replace the zone scope this client publishes to. Used after
   * {@link discoverZone} or when the server's auth reply tells us the
   * canonical zone label.
   */
  setZoneId(zoneId: string): void {
    if (!zoneId || zoneId === 'home') {
      throw new Error('Invalid zone id: "home" is reserved');
    }
    (this.opts as { zoneId: string }).zoneId = zoneId;
  }

  // ── auth.status ──

  /**
   * Probe whether the relay's zone is reachable + learn registration policy.
   * Replaces the HTTP probe (`/api/auth/status`). Connecting successfully
   * implies the relay is alive; the reply tells us whether we can
   * register an anonymous account or need an invite.
   */
  async probe(): Promise<{ hasUsers: boolean; openRegistration: boolean; zoneId?: string } | null> {
    // Don't swallow connect/transport errors — the caller needs to know WHY
    // probe failed (nats.ws hang, TLS pin mismatch, auth-violation, etc.).
    // We only return null when the server replies with `{ok: false}`.
    await this.connect();
    const reply = await this.request(this.subject('auth.status'), {});
    if (!reply.ok) return null;
    return {
      hasUsers: !!reply.hasUsers,
      openRegistration: !!reply.openRegistration,
      zoneId: reply.zoneId as string | undefined,
    };
  }

  /**
   * Auto-create an anonymous phone account and log in over NATS.
   * Mirrors the HTTP `registerAndLogin` flow but uses
   * `wyrd.zone.{zone}.auth.register` instead of POST /api/auth/register.
   * Returns the persisted creds + the AuthOk token bundle.
   */
  async registerAndLogin(companionName: string): Promise<{
    creds: { username: string; password: string };
    auth: NatsAuthOk;
  }> {
    await this.connect();
    const username = `phone-${companionName.toLowerCase().replace(/[^a-z0-9]/g, '')}-${randomSuffix()}`;
    let password = '';
    for (let i = 0; i < 32; i++) password += Math.floor(Math.random() * 16).toString(16);
    const displayName = companionName + "'s phone";
    const reply = await this.request(this.subject('auth.register'), {
      username, password, displayName,
    });
    if (!reply.ok) {
      // 409 (username_taken) → caller can retry with a new suffix; for now bubble up.
      throw new Error((reply.error as string) ?? 'register failed');
    }
    const token = reply.token as string | undefined;
    if (!token) throw new Error('register reply missing token');
    this.mcpToken = token;
    return {
      creds: { username, password },
      auth: {
        token,
        userId: (reply.userId as string) ?? username,
        username: (reply.username as string) ?? username,
        role: reply.role as string | undefined,
      },
    };
  }

  /**
   * Create a NAMED account over the relay — the user's chosen username and
   * password, not the auto-generated anonymous phone account above. This is
   * the phone-first onboarding path (2026-07-23): a fresh household's first
   * registrant becomes the steward and receives a one-time recoveryKey the
   * caller MUST surface (it's the only password-reset credential).
   * Fails with `registration_closed` once the household has a steward —
   * callers should then collect an invite code and use {@link redeemNamed}.
   */
  async registerNamed(
    username: string,
    password: string,
    displayName?: string,
  ): Promise<NatsAuthOk & { recoveryKey?: string }> {
    await this.connect();
    const reply = await this.request(this.subject('auth.register'), {
      username, password, displayName: displayName ?? username,
    });
    if (!reply.ok) {
      throw new Error((reply.error as string) ?? 'register failed');
    }
    const token = reply.token as string | undefined;
    if (!token) throw new Error('register reply missing token');
    this.mcpToken = token;
    return {
      token,
      userId: (reply.userId as string) ?? username,
      username: (reply.username as string) ?? username,
      role: reply.role as string | undefined,
      zoneId: reply.zoneId as string | undefined,
      recoveryKey: reply.recoveryKey as string | undefined,
    };
  }

  /**
   * Redeem a steward-minted invite code into a NAMED account (closed
   * registration). Same contract as {@link registerNamed} plus the code.
   */
  async redeemNamed(
    code: string,
    username: string,
    password: string,
    displayName?: string,
  ): Promise<NatsAuthOk & { recoveryKey?: string }> {
    await this.connect();
    const reply = await this.request(this.subject('auth.redeem'), {
      code, username, password, displayName: displayName ?? username,
    });
    if (!reply.ok) {
      throw new Error((reply.error as string) ?? 'redeem failed');
    }
    const token = reply.token as string | undefined;
    if (!token) throw new Error('redeem reply missing token');
    this.mcpToken = token;
    return {
      token,
      userId: (reply.userId as string) ?? username,
      username: (reply.username as string) ?? username,
      role: reply.role as string | undefined,
      zoneId: reply.zoneId as string | undefined,
      recoveryKey: reply.recoveryKey as string | undefined,
    };
  }

  // ── auth.redeem ──

  /**
   * Redeem an invite code and create an account on a closed-registration
   * household. Mirrors POST /api/auth/redeem.
   * Use this when {@link probe} reports `openRegistration: false` and the
   * user has an invite code from the steward (typically a 6-word passphrase).
   */
  async redeemInvite(
    code: string,
    companionName: string,
  ): Promise<{
    creds: { username: string; password: string };
    auth: NatsAuthOk;
  }> {
    await this.connect();
    const username = `phone-${companionName.toLowerCase().replace(/[^a-z0-9]/g, '')}-${randomSuffix()}`;
    let password = '';
    for (let i = 0; i < 32; i++) password += Math.floor(Math.random() * 16).toString(16);
    const displayName = companionName + "'s phone";
    const reply = await this.request(this.subject('auth.redeem'), {
      code, username, password, displayName,
    });
    if (!reply.ok) {
      throw new Error((reply.error as string) ?? 'redeem failed');
    }
    const token = reply.token as string | undefined;
    if (!token) throw new Error('redeem reply missing token');
    this.mcpToken = token;
    return {
      creds: { username, password },
      auth: {
        token,
        userId: (reply.userId as string) ?? username,
        username: (reply.username as string) ?? username,
        role: reply.role as string | undefined,
      },
    };
  }

  // ── login ──

  /**
   * Log in with username/password. Replaces POST /api/mcp/login.
   * Caches the token for subsequent calls.
   */
  async login(username: string, password: string): Promise<NatsAuthOk> {
    await this.connect();
    const reply = await this.request(this.subject('mcp.login'), { username, password });
    if (!reply.ok) {
      const err = (reply.error as string | undefined) ?? 'login failed';
      throw new Error(err);
    }
    const token = reply.token as string | undefined;
    if (!token) throw new Error('login reply missing token');
    this.mcpToken = token;
    return {
      token,
      userId: (reply.userId as string) ?? username,
      username: (reply.username as string) ?? username,
      role: reply.role as string | undefined,
      zoneId: reply.zoneId as string | undefined,
    };
  }

  // ── tell ──

  /**
   * Send a tell to an in-zone or cross-zone target. Mirrors POST /api/mcp/tell.
   * Cross-zone routing handled server-side via CrossZoneTellService.
   */
  async tell(target: string, message: string): Promise<NatsResult<string>> {
    if (!this.mcpToken) return { ok: false, error: 'Not logged in', status: 401 };
    const reply = await this.request(this.subject('mcp.tell'), {
      token: this.mcpToken,
      target,
      message,
    });
    if (!reply.ok) {
      return {
        ok: false,
        error: (reply.error as string) ?? 'tell failed',
        status: (reply._status as number) ?? undefined,
      };
    }
    return {
      ok: true,
      data: `Delivered to ${reply.target ?? target}`,
    };
  }

  // ── library.search ──

  /**
   * Search the household knowledge library. Returns formatted prose
   * matching ServerClient.searchLibrary so the caller can swap clients
   * without changing the consumer.
   */
  async searchLibrary(query: string, limit = 5): Promise<NatsResult<string>> {
    if (!this.mcpToken) return { ok: false, error: 'Not logged in', status: 401 };
    const reply = await this.request(this.subject('library.search'), {
      token: this.mcpToken,
      query,
      limit,
    });
    if (!reply.ok) {
      return {
        ok: false,
        error: (reply.error as string) ?? 'library search failed',
      };
    }
    const results = Array.isArray(reply.results)
      ? (reply.results as Array<Record<string, unknown>>)
      : [];
    if (results.length === 0) {
      return { ok: true, data: `No library results for "${query}".` };
    }
    const lines = [`Library results for "${query}" (${results.length}):`];
    for (const r of results) {
      const title = (r.title as string) || (r.source as string) || 'untitled';
      const snippet = String(r.text ?? r.snippet ?? '').slice(0, 180).replace(/\s+/g, ' ');
      lines.push(`  • ${title}${snippet ? ` — ${snippet}…` : ''}`);
    }
    return { ok: true, data: lines.join('\n') };
  }

  // ── study.journal ──

  /**
   * Write a journal entry. The user DID is derived from the auth token
   * server-side — phones can't forge a different user.
   */
  async writeJournal(_userId: string, content: string, isPrivate = false): Promise<NatsResult<string>> {
    if (!this.mcpToken) return { ok: false, error: 'Not logged in', status: 401 };
    const reply = await this.request(this.subject('study.journal'), {
      token: this.mcpToken,
      content,
      isPrivate,
    });
    if (!reply.ok) {
      return {
        ok: false,
        error: (reply.error as string) ?? 'journal write failed',
      };
    }
    return { ok: true, data: `Journal entry saved (${reply.id ?? 'ok'}).` };
  }

  // ── directory.search ── (: "Find a zone")

  /**
   * Query the opt-in zone directory for published zones. No token — only zones
   * that advertise themselves are returned, and a relay's roster is never
   * enumerated. A blank query lists the most-recently-refreshed zones; a
   * `tag:`/`capability:` prefix filters. Returns the raw manifest entries.
   */
  async searchDirectory(
    query = '',
    limit = 20,
  ): Promise<NatsResult<Array<Record<string, unknown>>>> {
    await this.connect();
    const reply = await this.request(this.subject('directory.search'), { query, limit });
    if (!reply.ok) {
      return { ok: false, error: (reply.error as string) ?? 'directory search failed' };
    }
    return {
      ok: true,
      data: Array.isArray(reply.zones) ? (reply.zones as Array<Record<string, unknown>>) : [],
    };
  }

  // ── directory.knock ── (: request access)

  /**
   * Knock on a discovered zone's door to request access. Token-free — you need
   * no account on the target zone yet (that's the point). Sent to the TARGET
   * zone's own subject (`wyrd.zone.{targetZone}.directory.knock`), not this
   * client's scoped zone, so it reaches the zone you discovered if it homes on
   * a relay you hold. The steward sees it and approves out-of-band (mints an
   * invite). Returns the recorded request id.
   */
  async requestAccess(
    targetZone: string,
    requesterName: string,
    requesterContact?: string,
    reason?: string,
  ): Promise<NatsResult<{ requestId: string }>> {
    await this.connect();
    const reply = await this.request(`wyrd.zone.${targetZone}.directory.knock`, {
      requesterName,
      ...(requesterContact ? { requesterContact } : {}),
      ...(reason ? { reason } : {}),
    });
    if (!reply.ok) {
      return { ok: false, error: (reply.error as string) ?? 'request failed' };
    }
    return { ok: true, data: { requestId: (reply.requestId as string) ?? '' } };
  }

  // ── account.zonebank ── (: cross-device sync)

  /**
   * Pull this account's synced zone bank from its home zone. Returns the raw
   * JSON blob the server holds (or null if nothing is stored yet) plus the
   * server's updatedAt stamp. The caller merges per-entry LWW into the local
   * store. The account is resolved server-side from the auth token, so a phone
   * only ever sees its own bank.
   */
  async getZoneBank(): Promise<NatsResult<{ bank: string | null; updatedAt: number }>> {
    if (!this.mcpToken) return { ok: false, error: 'Not logged in', status: 401 };
    const reply = await this.request(this.subject('account.zonebank.get'), {
      token: this.mcpToken,
    });
    if (!reply.ok) {
      return { ok: false, error: (reply.error as string) ?? 'zonebank get failed' };
    }
    return {
      ok: true,
      data: {
        bank: (reply.bank as string | null) ?? null,
        updatedAt: (reply.updatedAt as number) ?? 0,
      },
    };
  }

  /**
   * Push the merged zone bank up to the home zone. The server is a dumb
   * last-write blob store keyed by account; LWW merge already happened on the
   * client. `updatedAt` is echoed back so the device can record what it
   * persisted. Secrets (zone passwords) must NOT be included in `bankJson` —
   * they live in per-device secure storage only.
   */
  async putZoneBank(bankJson: string, updatedAt: number): Promise<NatsResult<{ updatedAt: number }>> {
    if (!this.mcpToken) return { ok: false, error: 'Not logged in', status: 401 };
    const reply = await this.request(this.subject('account.zonebank.put'), {
      token: this.mcpToken,
      bank: bankJson,
      updatedAt,
    });
    if (!reply.ok) {
      return { ok: false, error: (reply.error as string) ?? 'zonebank put failed' };
    }
    return { ok: true, data: { updatedAt: (reply.updatedAt as number) ?? updatedAt } };
  }

  /**
   * List recent journal entries (most recent first). Returns the raw array
   * — caller can format as needed.
   */
  async listJournal(limit = 20): Promise<NatsResult<Array<Record<string, unknown>>>> {
    if (!this.mcpToken) return { ok: false, error: 'Not logged in', status: 401 };
    const reply = await this.request(this.subject('study.journal'), {
      token: this.mcpToken,
      op: 'list',
      limit,
    });
    if (!reply.ok) {
      return {
        ok: false,
        error: (reply.error as string) ?? 'journal list failed',
      };
    }
    return {
      ok: true,
      data: Array.isArray(reply.entries) ? (reply.entries as Array<Record<string, unknown>>) : [],
    };
  }
}
