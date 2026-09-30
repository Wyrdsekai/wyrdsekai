/**
 * a ServerConnection tunneled through the relay.
 *
 * The phone interface is a terminal that speaks the C2S/S2C session protocol to
 * a ServerConnection. Offline mode drives the in-process PhoneNode; remote-over-
 * relay points HERE. The terminal can't tell the difference — it sends C2S and
 * renders S2C; this transport just carries those frames over the relay's dumb
 * pipe instead of an in-process node. Mirrors the KMP RelayTunnelServerConnection.
 *
 * Wire: the same C2S/S2C JSON the zone's `/ws` reads/writes, sealed end to end
 * (the sealed tunnel v2, W3; src/crypto/sealedTunnel.ts):
 *   1. `.open` carries only the phone's ephemeral key `{"v":2,"e":...}`.
 *   2. The home answers on `.down` with its own ephemeral key; both sides derive
 *      the session keys, which need the home's static key `zk` from the invite —
 *      a relay cannot stand in for the home.
 *   3. The first sealed frame up carries what `.open` used to carry
 *      (`{"token":...}`); every later frame on `.up`/`.down` is sealed, in order.
 * Frames the phone sends before step 2 completes wait in order. A frame that
 * does not open closes the session. Without `zk` (a phone paired before 0.5.0)
 * the tunnel does not open at all and says to pair again.
 */
import type { BetweenClient } from '../between/BetweenClient';
import type { C2SMessage } from '../../protocol/c2s';
import { serializeC2S } from '../../protocol/c2s';
import type { S2CMessage } from '../../protocol/s2c';
import { parseS2CMessage } from '../../protocol/s2c';
import type { ServerConnection, S2CHandler } from './ServerConnection';
import { fromUtf8, utf8 } from '../../crypto/bytes';
import { randomHex } from '../../crypto/random';
import {
  TunnelHandshake,
  decodePublicKey,
  parseTunnelAccept,
  type TunnelChannel,
} from '../../crypto/sealedTunnel';
import { securityText } from '../../security/securityText';

/** Frames sent before the home answered, held in order (same bound as the home's). */
const MAX_PENDING_UP = 64;

/**
 * The session id is a CAPABILITY, not just a correlation key (audit F1
 * residual, 2026-07-25). Household phones share one relay NATS account, and
 * static NATS ACLs cannot express "only the sessions you own" — so knowing a
 * sibling's session id is enough to inject `.up` frames into their session or
 * read their `.down` stream. It must therefore be unguessable: 128 bits from
 * the platform CSPRNG (react-native-get-random-values polyfills
 * crypto.getRandomValues; imported in index.js), hex, no dots — the zone splits
 * the subject on the last dot. With the sealed tunnel a guessed id no longer
 * reads or writes a session (frames do not open without its keys), but it
 * could still close one, so there is no Math.random fallback: randomHex throws.
 */
function newSessionId(): string {
  return randomHex(16);
}

export class RelayTunnelServerConnection implements ServerConnection {
  private readonly base: string;
  private readonly upAad: Uint8Array;
  private readonly downAad: Uint8Array;
  private readonly zk: Uint8Array | null;
  private handlers: S2CHandler[] = [];
  private downUnsub: (() => void) | null = null;
  private opened = false;
  /** Closed sessions stay closed; a new one is a new connection. */
  private ended = false;
  private handshake: TunnelHandshake | null = null;
  private up: TunnelChannel | null = null;
  private down: TunnelChannel | null = null;
  private pendingUp: Uint8Array[] = [];

  constructor(
    private readonly between: BetweenClient,
    private readonly zoneId: string,
    private readonly token: string | null,
    zk: string | null,
    private readonly sessionId: string = newSessionId(),
  ) {
    this.base = `wyrd.tunnel.${zoneId}.${this.sessionId}`;
    this.upAad = utf8(`${this.base}.up`);
    this.downAad = utf8(`${this.base}.down`);
    this.zk = decodePublicKey(zk);
  }

  get isConnected(): boolean {
    return this.between.isConnected && this.opened;
  }

  /** Whether the session keys are agreed (frames now travel sealed). */
  get isSealed(): boolean {
    return this.up != null;
  }

  /**
   * Subscribe the downlink and announce the session with this phone's
   * ephemeral key. Call once after the relay NATS connection is up. Idempotent.
   */
  open(): void {
    if (this.opened || this.ended) return;
    this.opened = true;
    if (!this.zk) {
      this.fail('tunnel_pair_again', securityText().pairAgain);
      return;
    }
    this.handshake = new TunnelHandshake(this.zk, this.sessionId);
    this.downUnsub = this.between.subscribe(`${this.base}.down`, (_subject, data) => this.onDown(data));
    this.between.publish(`${this.base}.open`, utf8(this.handshake.openPayload()));
  }

  private onDown(data: Uint8Array): void {
    if (!this.opened) return;
    if (this.down) {
      let text: string;
      try {
        text = fromUtf8(this.down.open(data, this.downAad));
      } catch {
        this.fail('tunnel_interrupted', securityText().tunnelInterrupted);
        return;
      }
      const msg = parseS2CMessage(text);
      if (msg) this.deliver(msg);
      return;
    }
    // Before the keys: the home's accept frame, or its refusal in the clear.
    const text = fromUtf8(data);
    const zoneEph = parseTunnelAccept(text);
    if (zoneEph && this.handshake) {
      let channels: { up: TunnelChannel; down: TunnelChannel };
      try {
        channels = this.handshake.finish(zoneEph);
      } catch {
        this.fail('tunnel_key_refused', securityText().tunnelRefused);
        return;
      }
      this.handshake = null;
      this.up = channels.up;
      this.down = channels.down;
      this.publishUp(utf8(JSON.stringify(this.token ? { token: this.token } : {})));
      const queued = this.pendingUp;
      this.pendingUp = [];
      for (const f of queued) this.publishUp(f);
      return;
    }
    const refusal = parseS2CMessage(text);
    if (refusal && refusal.type === 'error') {
      const plain = refusal.code === 'tunnel_key_refused' || refusal.code === 'tunnel_plaintext_refused';
      this.fail(refusal.code, plain ? securityText().tunnelRefused : refusal.message);
    }
    // Anything else before the accept is not from the home; ignore it.
  }

  private publishUp(plaintext: Uint8Array): void {
    this.between.publish(`${this.base}.up`, this.up!.seal(plaintext, this.upAad));
  }

  private deliver(msg: S2CMessage): void {
    for (const h of [...this.handlers]) h(msg);
  }

  /** Tell the terminal why, then end the session. */
  private fail(code: string, message: string): void {
    this.deliver({ type: 'error', seq: 0, code, message });
    this.close();
  }

  send(message: C2SMessage): void {
    if (!this.opened) this.open();
    if (!this.opened) return;
    const frame = utf8(serializeC2S(message));
    if (this.up) {
      this.publishUp(frame);
    } else if (this.handshake && this.pendingUp.length < MAX_PENDING_UP) {
      this.pendingUp.push(frame);
    }
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

  /** End the tunneled session. `.close` stays a plain signal: it carries no data. */
  close(): void {
    if (this.opened && this.zk) {
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
    this.ended = true;
    this.handshake = null;
    this.up = null;
    this.down = null;
    this.pendingUp = [];
  }
}
