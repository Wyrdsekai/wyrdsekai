/**
 * Native NATS client that speaks the raw NATS text protocol over WebSocket.
 *
 * Used on native RN (iOS/Android) where nats.ws may not work reliably due
 * to binaryType='arraybuffer' issues in the RN WebSocket polyfill. This
 * client uses only text frames, avoiding the binary frame path entirely.
 *
 * Mirrors the KMP NatsBetweenClient approach
 * (shared/src/commonMain/kotlin/.../NatsBetweenClient.kt).
 *
 * Protocol reference: https://docs.nats.io/reference/reference-protocols/nats-protocol
 *
 * Wire format (all messages delimited by \r\n):
 *   Server INFO -> Client CONNECT -> SUB/PUB/MSG/PING/PONG
 */
import type { BetweenClient, BetweenMessageHandler } from './BetweenClient';
import { createRelaySocket, type RelaySocketLike } from './RelaySocket';

export type NativeNatsState = 'disconnected' | 'connecting' | 'connected' | 'reconnecting' | 'error';
export type NativeNatsStateListener = (state: NativeNatsState) => void;

/** Max reconnect backoff delay (16 seconds). */
export const MAX_BACKOFF_MS = 16_000;

/** Default ping interval (30 seconds). */
export const PING_INTERVAL_MS = 30_000;

/**
 * Exponential backoff: 1s, 2s, 4s, 8s, 16s (capped).
 * Exported for testing.
 */
export function backoffDelayMs(attempt: number): number {
  const base = 1000;
  const delay = base * Math.pow(2, attempt);
  return Math.min(delay, MAX_BACKOFF_MS);
}

/** Index of the first \r\n in [buf], or -1. Exported for tests. */
export function findCrlf(buf: Uint8Array): number {
  for (let i = 0; i + 1 < buf.length; i++) {
    if (buf[i] === 0x0d && buf[i + 1] === 0x0a) return i;
  }
  return -1;
}

interface Subscription {
  sid: number;
  subject: string;
  handler: BetweenMessageHandler;
}

/**
 * Pending MSG header parsed from the buffer, waiting for payload bytes.
 */
interface PendingMsg {
  subject: string;
  sid: number;
  length: number;
}

export class NativeNatsClient implements BetweenClient {
  // RelaySocketLike: a real WebSocket on Android/web/tests, or the native
  // NSURLSession-backed pinned relay socket on iOS (see RelaySocket.ts). Same
  // surface either way (binaryType/onopen/onmessage/onerror/onclose/send/close).
  private ws: RelaySocketLike | null = null;
  private _state: NativeNatsState = 'disconnected';
  private _connected = false;
  private nextSid = 1;
  private subscriptions = new Map<number, Subscription>();
  private stateListeners: NativeNatsStateListener[] = [];
  // Receive buffer is BYTES, not a decoded string. The NATS MSG header's
  // {length} is a BYTE count; buffering decoded text and slicing by that count
  // over-reads whenever a payload contains multi-byte UTF-8 (em-dash, ellipsis,
  // Japanese…), splicing the next frame's "\r\nMSG …" onto the payload and
  // desyncing the whole stream — the exact KMP NatsBetweenClient bug fixed in
  // af163bf2, twinned here (2026-07-25). Byte buffering also survives a
  // multi-byte char split across two WebSocket frames, which a per-frame
  // TextDecoder.decode() would mangle.
  private buffer: Uint8Array = new Uint8Array(0);
  private pendingMsg: PendingMsg | null = null;
  private lastConnectUrl: string | null = null;
  private authCreds: { user?: string; pass?: string } | null = null;
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private connectResolve: (() => void) | null = null;
  private connectReject: ((err: Error) => void) | null = null;

  /** Auto-reconnect on connection drop. */
  autoReconnect = true;

  get isConnected(): boolean {
    return this._connected;
  }

  get state(): NativeNatsState {
    return this._state;
  }

  onStateChange(listener: NativeNatsStateListener): () => void {
    this.stateListeners.push(listener);
    return () => {
      this.stateListeners = this.stateListeners.filter(l => l !== listener);
    };
  }

  private setState(state: NativeNatsState): void {
    this._state = state;
    for (const listener of this.stateListeners) {
      try {
        listener(state);
      } catch {
        // Listener threw; don't crash.
      }
    }
  }

  /**
   * Connect to a NATS server via WebSocket.
   *
   * The NATS handshake is:
   *   1. Server sends INFO {...}\r\n
   *   2. Client sends CONNECT {...}\r\n
   *
   * After handshake, all existing subscriptions are re-sent (for reconnect).
   */
  async connect(url: string, creds?: { user?: string; pass?: string }): Promise<void> {
    if (this._connected) return;
    this.lastConnectUrl = url;
    // the relay requires the shared relay_phone user/pass
    // for the study-sync Between leg. A LAN household NATS needs no auth (creds
    // undefined → anonymous CONNECT, unchanged).
    if (creds) this.authCreds = creds;
    this.setState('connecting');
    this.buffer = new Uint8Array(0);
    this.pendingMsg = null;

    return new Promise<void>((resolve, reject) => {
      this.connectResolve = resolve;
      this.connectReject = reject;

      try {
        // nats-server frames the NATS protocol as WebSocket BINARY frames. On
        // iOS the RN WebSocket delivers those as ArrayBuffer; binaryType must be
        // 'arraybuffer' (not the default 'blob', which is async to read and would
        // strand the bytes). Decode both string and binary frames as UTF-8 — the
        // old `typeof === 'string' ? : ''` silently dropped every MSG on iOS, so
        // discovery/login/tunnel replies never arrived (the KMP #1268 bug, RN twin).
        const ws = createRelaySocket(url);
        ws.binaryType = 'arraybuffer';
        this.ws = ws;

        ws.onopen = () => {
          // Wait for the INFO message from the server (handled in onmessage)
        };

        ws.onmessage = (event: { data: string | ArrayBuffer }) => {
          const raw = event.data;
          let data: Uint8Array | null = null;
          if (typeof raw === 'string') {
            data = new TextEncoder().encode(raw);
          } else if (raw instanceof ArrayBuffer) {
            data = new Uint8Array(raw);
          } else if (raw && ArrayBuffer.isView(raw)) {
            const view = raw as ArrayBufferView;
            data = new Uint8Array(view.buffer, view.byteOffset, view.byteLength);
          }
          if (data && data.length > 0) this.onData(data);
        };

        ws.onerror = () => {
          if (this.connectReject) {
            const rej = this.connectReject;
            this.connectResolve = null;
            this.connectReject = null;
            this.setState('error');
            rej(new Error(`WebSocket connection error to ${url}`));
          }
        };

        ws.onclose = () => {
          const wasConnected = this._connected;
          this._connected = false;
          this.stopPingTimer();

          if (this.connectReject) {
            // Connection dropped before handshake completed
            const rej = this.connectReject;
            this.connectResolve = null;
            this.connectReject = null;
            this.setState('error');
            rej(new Error(`WebSocket closed before NATS handshake completed`));
            return;
          }

          this.ws = null;

          if (wasConnected && this.autoReconnect) {
            this.scheduleReconnect();
          } else {
            this.setState('disconnected');
          }
        };
      } catch (e) {
        this.connectResolve = null;
        this.connectReject = null;
        this.setState('error');
        reject(e instanceof Error ? e : new Error(String(e)));
      }
    });
  }

  async disconnect(): Promise<void> {
    this.autoReconnect = false; // Prevent reconnect on intentional close
    this._connected = false;
    this.cancelReconnect();
    this.stopPingTimer();

    if (this.connectReject) {
      const rej = this.connectReject;
      this.connectResolve = null;
      this.connectReject = null;
      rej(new Error('disconnect() called during connect'));
    }

    if (this.ws) {
      try {
        this.ws.onclose = null; // Prevent reconnect trigger
        this.ws.onerror = null;
        this.ws.onmessage = null;
        this.ws.close();
      } catch {
        // Best-effort close
      }
      this.ws = null;
    }

    this.setState('disconnected');
  }

  publish(subject: string, data: Uint8Array): void {
    if (!this.ws || !this._connected) return;

    const payload = new TextDecoder().decode(data);
    // PUB {subject} {length}\r\n{payload}\r\n
    const msg = `PUB ${subject} ${data.length}\r\n${payload}\r\n`;
    try {
      this.ws.send(msg);
    } catch {
      // Send failure is non-fatal
    }
  }

  subscribe(subject: string, handler: BetweenMessageHandler): () => void {
    const sid = this.nextSid++;
    const sub: Subscription = { sid, subject, handler };
    this.subscriptions.set(sid, sub);

    if (this.ws && this._connected) {
      try {
        this.ws.send(`SUB ${subject} ${sid}\r\n`);
      } catch {
        // Will be re-subscribed on reconnect
      }
    }

    return () => {
      this.subscriptions.delete(sid);
      if (this.ws && this._connected) {
        try {
          this.ws.send(`UNSUB ${sid}\r\n`);
        } catch {
          // Best-effort unsubscribe
        }
      }
    };
  }

  // --- Protocol parsing ---

  /**
   * Process incoming WebSocket data. Appends raw bytes to the buffer and
   * parses complete NATS messages. Handles partial frames across multiple
   * onmessage events (including a multi-byte char split mid-frame).
   */
  private onData(data: Uint8Array): void {
    if (this.buffer.length === 0) {
      this.buffer = data;
    } else {
      const merged = new Uint8Array(this.buffer.length + data.length);
      merged.set(this.buffer, 0);
      merged.set(data, this.buffer.length);
      this.buffer = merged;
    }
    this.processBuffer();
  }

  /**
   * Parse and dispatch complete NATS messages from the byte buffer.
   * Mutates this.buffer, consuming parsed portions. MSG payloads are sliced
   * by the header's BYTE length and handed to subscribers as raw bytes;
   * protocol lines (ASCII by spec) are decoded for dispatch.
   */
  private processBuffer(): void {
    while (true) {
      // If we're waiting for a MSG payload, try to read it
      if (this.pendingMsg) {
        const needed = this.pendingMsg.length + 2; // payload + \r\n
        if (this.buffer.length < needed) {
          return; // Wait for more data
        }

        const payloadBytes = this.buffer.slice(0, this.pendingMsg.length);
        this.buffer = this.buffer.slice(needed);

        // Dispatch to handler
        const sub = this.subscriptions.get(this.pendingMsg.sid);
        if (sub) {
          try {
            sub.handler(this.pendingMsg.subject, payloadBytes);
          } catch {
            // Handler threw; don't crash receive loop
          }
        }
        this.pendingMsg = null;
        continue;
      }

      // Look for a complete \r\n-terminated protocol line
      const idx = findCrlf(this.buffer);
      if (idx === -1) return; // No complete line yet

      const line = new TextDecoder().decode(this.buffer.slice(0, idx));
      this.buffer = this.buffer.slice(idx + 2);

      this.processLine(line);
    }
  }

  /**
   * Process a single NATS protocol line.
   */
  private processLine(line: string): void {
    if (line.startsWith('INFO ')) {
      this.handleInfo(line);
    } else if (line.startsWith('MSG ')) {
      this.handleMsgHeader(line);
    } else if (line === 'PING') {
      this.handlePing();
    } else if (line === 'PONG') {
      // Response to our PING. Nothing to do.
    } else if (line === '+OK') {
      // Verbose mode acknowledgement. Nothing to do.
    } else if (line.startsWith('-ERR')) {
      // Server error. Log but don't disconnect.
      // In production, console.warn would be appropriate.
    }
    // Unknown lines are silently ignored.
  }

  /**
   * Handle INFO from server. On first INFO, complete the NATS handshake
   * by sending CONNECT and resolving the connect() promise.
   */
  private handleInfo(_infoLine: string): void {
    if (this.connectResolve) {
      // First INFO = handshake. Send CONNECT.
      const connectJson = JSON.stringify({
        verbose: false,
        pedantic: false,
        lang: 'react-native',
        version: '1.0',
        protocol: 1,
        ...(this.authCreds?.user
          ? { user: this.authCreds.user, pass: this.authCreds.pass ?? '' }
          : {}),
      });

      try {
        this.ws!.send(`CONNECT ${connectJson}\r\n`);
      } catch {
        if (this.connectReject) {
          const rej = this.connectReject;
          this.connectResolve = null;
          this.connectReject = null;
          this.setState('error');
          rej(new Error('Failed to send CONNECT command'));
        }
        return;
      }

      // Re-subscribe all existing subscriptions (for reconnect scenarios)
      for (const [sid, sub] of this.subscriptions) {
        try {
          this.ws!.send(`SUB ${sub.subject} ${sid}\r\n`);
        } catch {
          // Will retry on next reconnect
        }
      }

      this._connected = true;
      this.reconnectAttempt = 0;
      this.setState('connected');
      this.startPingTimer();

      const resolve = this.connectResolve;
      this.connectResolve = null;
      this.connectReject = null;
      resolve();
    }
    // Subsequent INFO messages (cluster change) are ignored.
  }

  /**
   * Handle MSG header line. Sets pendingMsg so the next processBuffer
   * iteration reads the payload.
   *
   * Format: MSG {subject} {sid} [{reply-to}] {length}
   */
  private handleMsgHeader(line: string): void {
    const parts = line.split(' ');
    if (parts.length < 4) return; // Malformed

    const subject = parts[1];
    const sid = parseInt(parts[2], 10);
    // Length is always the last field
    const length = parseInt(parts[parts.length - 1], 10);

    if (isNaN(sid) || isNaN(length) || length < 0) return; // Malformed

    this.pendingMsg = { subject, sid, length };
  }

  private handlePing(): void {
    if (this.ws && this._connected) {
      try {
        this.ws.send('PONG\r\n');
      } catch {
        // Send failure
      }
    }
  }

  // --- Keepalive ---

  private startPingTimer(): void {
    this.stopPingTimer();
    this.pingTimer = setInterval(() => {
      if (this.ws && this._connected) {
        try {
          this.ws.send('PING\r\n');
        } catch {
          // Ping failure will be caught by onclose
        }
      }
    }, PING_INTERVAL_MS);
  }

  private stopPingTimer(): void {
    if (this.pingTimer) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }

  // --- Reconnection ---

  private scheduleReconnect(): void {
    this.cancelReconnect();
    this.setState('reconnecting');

    const delay = backoffDelayMs(this.reconnectAttempt);
    this.reconnectAttempt++;

    this.reconnectTimer = setTimeout(async () => {
      this.reconnectTimer = null;
      const url = this.lastConnectUrl;
      if (!url || !this.autoReconnect) return;

      try {
        await this.connect(url);
      } catch {
        // connect() failed; onclose will schedule next reconnect
        // if autoReconnect is still true
      }
    }, delay);
  }

  private cancelReconnect(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
}
