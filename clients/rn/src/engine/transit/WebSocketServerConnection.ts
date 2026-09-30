import type { ServerConnection } from './ServerConnection';
import type { C2SMessage } from '../../protocol/c2s';
import type { S2CMessage } from '../../protocol/s2c';
import { createRelaySocket, nativeRelaySocketAvailable } from '../between/RelaySocket';

/** The part of a WebSocket this connection uses; the native pinned socket has it too. */
interface SessionSocket {
  onopen: ((ev?: unknown) => void) | null;
  onmessage: ((ev: { data: unknown }) => void) | null;
  onerror: ((ev?: unknown) => void) | null;
  onclose: ((ev?: unknown) => void) | null;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

/**
 * The socket for a session address. A wss:// home on iOS goes through the
 * app's native pinned socket in text mode (RN's WebSocket cannot be pinned on
 * iOS, so it would fail TLS against the household CA), after pinning the home's
 * address to its household CA when an invite named it (
 * W2). Elsewhere: RN's WebSocket (pinned by OkHttp on Android).
 */
async function openSessionSocket(url: string): Promise<SessionSocket> {
  if (url.startsWith('wss://') && nativeRelaySocketAvailable()) {
    const { pinKnownHome } = await import('../../server/HouseholdTrust');
    await pinKnownHome(url);
    return createRelaySocket(url, { text: true }) as unknown as SessionSocket;
  }
  return new WebSocket(url) as unknown as SessionSocket;
}

/**
 * WebSocket-based ServerConnection for visiting rooms on the household server.
 * Connects to the Wyrdsekai server's /ws endpoint and proxies C2S/S2C messages.
 */
/** The session socket closed before the home sent anything: it refused the login (or went away). */
export class SessionRefusedError extends Error {
  constructor(readonly code: number | undefined, readonly reason: string) {
    super(`the home closed the session${code ? ` (${code}${reason ? ` ${reason}` : ''})` : ''}`);
    this.name = 'SessionRefusedError';
  }
}

export class WebSocketServerConnection implements ServerConnection {
  private ws: SessionSocket | null = null;
  private handlers: Array<(msg: S2CMessage) => void> = [];
  /** Frames that arrived before anyone listened (the home's first room frames). */
  private early: S2CMessage[] = [];
  private _isConnected = false;
  private readonly wsUrl: string;
  private pingInterval: ReturnType<typeof setInterval> | null = null;

  constructor(wsUrl: string) {
    this.wsUrl = wsUrl;
  }

  get isConnected(): boolean {
    return this._isConnected;
  }

  /**
   * Resolves on the home's first frame: the home sends the room as soon as it
   * has taken the login, and closes the socket without a word when it refuses
   * it (an expired session: 4001, a revoked device: 4004), which rejects with
   * SessionRefusedError instead of reporting a dead session as connected.
   */
  async connect(): Promise<void> {
    const ws = await openSessionSocket(this.wsUrl);
    return new Promise((resolve, reject) => {
      let settled = false;
      const settle = (err?: Error) => {
        if (settled) return;
        settled = true;
        clearTimeout(connectTimer);
        if (err) reject(err);
        else resolve();
      };
      const connectTimer = setTimeout(() => {
        this.ws?.close();
        settle(new Error('Connection timeout'));
      }, 10000);
      try {
        this.ws = ws;

        this.ws.onopen = () => {
          // Keepalive ping every 30s (server has 5min idle timeout)
          this.pingInterval = setInterval(() => {
            try { this.ws?.send('{"type":"ping"}'); } catch {}
          }, 30_000);
        };

        this.ws.onmessage = (event) => {
          let msg: S2CMessage;
          try {
            msg = JSON.parse(String(event.data));
          } catch {
            return; // Malformed message — skip
          }
          this._isConnected = true;
          if (this.handlers.length === 0) this.early.push(msg);
          for (const handler of this.handlers) handler(msg);
          settle();
        };

        this.ws.onerror = () => {
          if (!this._isConnected) settle(new Error('WebSocket connection failed'));
        };

        this.ws.onclose = (ev?: unknown) => {
          const e = (ev ?? {}) as { code?: number; reason?: string };
          const wasOpen = this._isConnected;
          this._isConnected = false;
          if (this.pingInterval) {
            clearInterval(this.pingInterval);
            this.pingInterval = null;
          }
          if (!wasOpen) settle(new SessionRefusedError(e.code, e.reason ?? ''));
        };
      } catch (e) {
        settle(e instanceof Error ? e : new Error(String(e)));
      }
    });
  }

  async send(message: C2SMessage): Promise<void> {
    if (this.ws && this._isConnected) {
      this.ws.send(JSON.stringify(message));
    }
  }

  onMessage(handler: (msg: S2CMessage) => void): () => void {
    this.handlers.push(handler);
    const early = this.early;
    this.early = [];
    for (const msg of early) handler(msg);
    return () => {
      this.handlers = this.handlers.filter((h) => h !== handler);
    };
  }

  remoteRoomIds(): Set<string> {
    return new Set(); // Not used for visiting
  }

  disconnect(): void {
    if (this.pingInterval) {
      clearInterval(this.pingInterval);
      this.pingInterval = null;
    }
    this.ws?.close();
    this.ws = null;
    this._isConnected = false;
  }
}
