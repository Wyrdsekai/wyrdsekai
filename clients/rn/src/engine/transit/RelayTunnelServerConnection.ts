/**
 * a ServerConnection tunneled through the relay.
 *
 * The phone interface is a terminal that speaks the C2S/S2C session protocol to
 * a ServerConnection. Offline mode drives the in-process PhoneNode; remote-over-
 * relay points HERE. The terminal can't tell the difference — it sends C2S and
 * renders S2C; this transport just carries those frames over the relay's dumb
 * pipe instead of an in-process node. Mirrors the KMP RelayTunnelServerConnection.
 *
 * Wire: the same C2S/S2C JSON the zone's `/ws` reads/writes. We publish the
 * phone's C2S frames to `wyrd.tunnel.{zone}.{session}.up` and subscribe the
 * zone's S2C frames on `...down`. The relay only shuffles bytes; the zone
 * tunnels them into its own session server (see TunnelSessionHandler.java).
 */
import type { BetweenClient } from '../between/BetweenClient';
import type { C2SMessage } from '../../protocol/c2s';
import { serializeC2S } from '../../protocol/c2s';
import type { S2CMessage } from '../../protocol/s2c';
import { parseS2CMessage } from '../../protocol/s2c';
import type { ServerConnection, S2CHandler } from './ServerConnection';

const enc = new TextEncoder();
const dec = new TextDecoder();

/**
 * The session id is a CAPABILITY, not just a correlation key (audit F1
 * residual, 2026-07-25). Household phones share one relay NATS account, and
 * static NATS ACLs cannot express "only the sessions you own" — so knowing a
 * sibling's session id is enough to inject `.up` frames into their session or
 * read their `.down` stream. It must therefore be unguessable: 128 bits from
 * the platform CSPRNG (react-native-get-random-values polyfills
 * crypto.getRandomValues; imported in index.js), hex, no dots — the zone splits
 * the subject on the last dot. The old value — Date.now() hex plus 32 bits of
 * Math.random — was both low-entropy and largely predictable from the clock.
 */
function newSessionId(): string {
  const bytes = new Uint8Array(16);
  const g = globalThis as { crypto?: { getRandomValues?: (a: Uint8Array) => void } };
  if (g.crypto?.getRandomValues) {
    g.crypto.getRandomValues(bytes);
  } else {
    // Should not happen — index.js imports react-native-get-random-values. A
    // predictable session id is a real (if household-scoped) vulnerability, so
    // this is loud rather than silent.
    // eslint-disable-next-line no-console
    console.error('[RelayTunnel] crypto.getRandomValues unavailable — session id is NOT unguessable');
    for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  }
  let hex = '';
  for (let i = 0; i < bytes.length; i++) hex += bytes[i].toString(16).padStart(2, '0');
  return hex;
}

export class RelayTunnelServerConnection implements ServerConnection {
  private readonly base: string;
  private handlers: S2CHandler[] = [];
  private downUnsub: (() => void) | null = null;
  private opened = false;

  constructor(
    private readonly between: BetweenClient,
    private readonly zoneId: string,
    private readonly token: string | null,
    private readonly sessionId: string = newSessionId(),
  ) {
    this.base = `wyrd.tunnel.${zoneId}.${this.sessionId}`;
  }

  get isConnected(): boolean {
    return this.between.isConnected && this.opened;
  }

  /**
   * Subscribe the downlink and announce the session. Call once after the relay
   * NATS connection is up. Idempotent.
   */
  open(): void {
    if (this.opened) return;
    this.downUnsub = this.between.subscribe(`${this.base}.down`, (_subject, data) => {
      let msg: S2CMessage | null = null;
      try {
        msg = parseS2CMessage(dec.decode(data));
      } catch {
        msg = null;
      }
      if (msg) {
        for (const h of [...this.handlers]) h(msg);
      }
    });
    const openPayload = JSON.stringify(this.token ? { token: this.token } : {});
    this.between.publish(`${this.base}.open`, enc.encode(openPayload));
    this.opened = true;
  }

  send(message: C2SMessage): void {
    if (!this.opened) this.open();
    this.between.publish(`${this.base}.up`, enc.encode(serializeC2S(message)));
  }

  onMessage(handler: S2CHandler): () => void {
    this.handlers.push(handler);
    return () => {
      this.handlers = this.handlers.filter((h) => h !== handler);
    };
  }

  remoteRoomIds(): Set<string> {
    return new Set();
  }

  /** End the tunneled session. */
  close(): void {
    if (this.opened) {
      try {
        this.between.publish(`${this.base}.close`, new Uint8Array(0));
      } catch {
        /* best effort */
      }
    }
    this.downUnsub?.();
    this.downUnsub = null;
    this.handlers = [];
    this.opened = false;
  }
}
