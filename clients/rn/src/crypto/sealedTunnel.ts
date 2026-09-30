/**
 * The sealed tunnel (v2), phone side, W3. The home
 * side is core/.../crypto/SealedTunnel.java; both check the same vectors
 * (core/src/test/resources/crypto/tunnel-v2-vectors.json).
 *
 * The phone knows its home's static X25519 key `zk` from the pairing invite.
 * Per session it sends an ephemeral key on `.open`; the home answers on `.down`
 * with its own ephemeral key. Both derive
 *   HKDF-SHA256(salt = session id, ikm = DH(e_p, zk) || DH(e_p, e_z),
 *               info = "wyrd-tunnel-v2" || e_p || e_z) -> k_up || k_down.
 * Only the holder of the home's static key can compute the first term, so a
 * relay cannot stand in for the home. Every later frame is ChaCha20-Poly1305,
 * nonce = 4 zero bytes || 64-bit big-endian counter, strictly in order per
 * direction, AAD = the NATS subject.
 */
import { x25519 as curve } from '@noble/curves/ed25519';
import { chacha20poly1305 } from '@noble/ciphers/chacha';
import { extract, expand } from '@noble/hashes/hkdf';
import { sha256 } from '@noble/hashes/sha2';
import { bytesEqual, concatBytes, fromBase64Url, toBase64Url, utf8 } from './bytes';
import { randomBytes } from './random';

export const KEY_LEN = 32;
export const NONCE_LEN = 12;
export const TAG_LEN = 16;
const TUNNEL_INFO = utf8('wyrd-tunnel-v2');

/** A key, frame or handshake that must not be used. The message is for logs only. */
export class SealedCryptoError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SealedCryptoError';
  }
}

/** An X25519 key pair as raw 32-byte values (RFC 7748 encoding). */
export interface X25519KeyPair {
  priv: Uint8Array;
  pub: Uint8Array;
}

export function generateKeyPair(): X25519KeyPair {
  return keyPairFromPrivate(randomBytes(KEY_LEN));
}

export function keyPairFromPrivate(priv: Uint8Array): X25519KeyPair {
  if (priv.length !== KEY_LEN) throw new SealedCryptoError('X25519 keys are 32 bytes');
  return { priv, pub: curve.getPublicKey(priv) };
}

/** X25519(priv, pub). Refuses an all-zero result (a low-order public key). */
export function x25519(priv: Uint8Array, pub: Uint8Array): Uint8Array {
  if (priv.length !== KEY_LEN || pub.length !== KEY_LEN) {
    throw new SealedCryptoError('X25519 keys are 32 bytes');
  }
  let shared: Uint8Array;
  try {
    shared = curve.getSharedSecret(priv, pub);
  } catch {
    throw new SealedCryptoError('X25519 refused the public key');
  }
  let zero = 0;
  for (let i = 0; i < shared.length; i++) zero |= shared[i];
  if (zero === 0) throw new SealedCryptoError('X25519 result is all zero (low-order public key)');
  return shared;
}

/** A 32-byte public key as it travels in invites and handshakes, or null. */
export function decodePublicKey(s: string | null | undefined): Uint8Array | null {
  const k = fromBase64Url(s);
  return k && k.length === KEY_LEN ? k : null;
}

export function tunnelNonce(counter: number): Uint8Array {
  const n = new Uint8Array(NONCE_LEN);
  let c = counter;
  for (let i = NONCE_LEN - 1; i >= 4; i--) {
    n[i] = c % 256;
    c = Math.floor(c / 256);
  }
  return n;
}

/** One direction of a session: its key and its frame counter. Frames must arrive in order. */
export class TunnelChannel {
  private counter = 0;
  private readonly key: Uint8Array;

  constructor(key: Uint8Array) {
    if (key.length !== KEY_LEN) throw new SealedCryptoError('channel keys are 32 bytes');
    this.key = key.slice();
  }

  /** `nonce(12) || ciphertext || tag(16)` for the next frame in this direction. */
  seal(plaintext: Uint8Array, aad: Uint8Array): Uint8Array {
    const nonce = tunnelNonce(this.counter);
    const ct = chacha20poly1305(this.key, nonce, aad).encrypt(plaintext);
    this.counter++;
    return concatBytes(nonce, ct);
  }

  /** Opens the next frame; a frame out of order, forged or altered throws. */
  open(frame: Uint8Array, aad: Uint8Array): Uint8Array {
    if (frame.length < NONCE_LEN + TAG_LEN) throw new SealedCryptoError('sealed frame too short');
    const nonce = frame.subarray(0, NONCE_LEN);
    if (!bytesEqual(nonce, tunnelNonce(this.counter))) {
      throw new SealedCryptoError(`sealed frame out of order (expected frame ${this.counter})`);
    }
    let pt: Uint8Array;
    try {
      pt = chacha20poly1305(this.key, nonce, aad).decrypt(frame.subarray(NONCE_LEN));
    } catch {
      throw new SealedCryptoError('sealed frame did not open');
    }
    this.counter++;
    return pt;
  }

  get nextFrame(): number {
    return this.counter;
  }
}

/** {kUp, kDown} from the two DH results, the session id and both ephemeral keys. */
export function deriveTunnelKeys(
  dhStatic: Uint8Array,
  dhEphemeral: Uint8Array,
  sessionId: string,
  phoneEphemeralPub: Uint8Array,
  zoneEphemeralPub: Uint8Array,
): { kUp: Uint8Array; kDown: Uint8Array } {
  const prk = extract(sha256, concatBytes(dhStatic, dhEphemeral), utf8(sessionId));
  const okm = expand(sha256, prk, concatBytes(TUNNEL_INFO, phoneEphemeralPub, zoneEphemeralPub), 2 * KEY_LEN);
  return { kUp: okm.slice(0, KEY_LEN), kDown: okm.slice(KEY_LEN, 2 * KEY_LEN) };
}

/** The phone's half of one session, from `.open` until the home's key arrives. */
export class TunnelHandshake {
  readonly ephemeral: X25519KeyPair;

  constructor(
    private readonly zoneStaticPub: Uint8Array,
    private readonly sessionId: string,
    ephemeral?: X25519KeyPair,
  ) {
    if (zoneStaticPub.length !== KEY_LEN) throw new SealedCryptoError("the home's key is not 32 bytes");
    this.ephemeral = ephemeral ?? generateKeyPair();
  }

  /** The `.open` body: `{"v":2,"e":<ephemeral public key>}`. No token in the clear. */
  openPayload(): string {
    return JSON.stringify({ v: 2, e: toBase64Url(this.ephemeral.pub) });
  }

  /** Both directions once the home's ephemeral key has arrived (SealedTunnel.complete in Java). */
  finish(zoneEphemeralPub: Uint8Array): { up: TunnelChannel; down: TunnelChannel } {
    const { kUp, kDown } = deriveTunnelKeys(
      x25519(this.ephemeral.priv, this.zoneStaticPub),
      x25519(this.ephemeral.priv, zoneEphemeralPub),
      this.sessionId,
      this.ephemeral.pub,
      zoneEphemeralPub,
    );
    return { up: new TunnelChannel(kUp), down: new TunnelChannel(kDown) };
  }
}

/** The home's first `.down` frame `{"v":2,"e":...}` → its ephemeral key, or null if it is not one. */
export function parseTunnelAccept(text: string): Uint8Array | null {
  let o: unknown;
  try {
    o = JSON.parse(text);
  } catch {
    return null;
  }
  if (!o || typeof o !== 'object') return null;
  const m = o as { v?: unknown; e?: unknown };
  if (String(m.v) !== '2' || typeof m.e !== 'string') return null;
  return decodePublicKey(m.e);
}
