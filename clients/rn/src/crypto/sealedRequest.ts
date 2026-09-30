/**
 * Sealed requests (v2), phone side, W3 "sealed
 * requests". Vectors: core/src/test/resources/crypto/request-v2-vectors.json.
 *
 * One round trip, Noise N in shape: the phone knows its home's static key `zk`
 * from pairing. Per request it makes an ephemeral key e_p and 16 random bytes n:
 *   dh = X25519(e_p, zk); prk = HKDF-Extract(salt = n, dh)
 *   okm = HKDF-Expand(prk, "wyrd-request-v2" || e_p.pub, 64)
 *   k_req = okm[0..32], k_rep = okm[32..64]
 * The request `{"ts":ms,"body":...}` and the reply are each ChaCha20-Poly1305
 * under their own key with a zero nonce (each key seals exactly one message),
 * AAD = the request subject.
 */
import { chacha20poly1305 } from '@noble/ciphers/chacha';
import { extract, expand } from '@noble/hashes/hkdf';
import { sha256 } from '@noble/hashes/sha2';
import { concatBytes, fromBase64Url, fromUtf8, toBase64Url, utf8 } from './bytes';
import { randomBytes } from './random';
import { KEY_LEN, SealedCryptoError, generateKeyPair, x25519, type X25519KeyPair } from './sealedTunnel';

const REQUEST_INFO = utf8('wyrd-request-v2');
const ZERO_NONCE = new Uint8Array(12);
export const REQUEST_NONCE_LEN = 16;

/**
 * The home's refusals, sent in the clear because it could not (or would not)
 * open the request. They carry no data, so they are the only unsealed replies
 * a phone accepts.
 */
export const HOME_REFUSALS = ['sealed_refused', 'sealed_required'] as const;

/**
 * A request or reply that failed on the sealed layer (not an account decision):
 * the home refused the sealed request, or a reply could not be verified.
 * `code` is one of HOME_REFUSALS, 'reply_not_sealed', 'reply_unverified' or
 * 'pair_again' (this phone holds no key for its home).
 */
export class SealedChannelError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = 'SealedChannelError';
  }
}

export interface SealedRequest {
  subject: string;
  /** The exact JSON body to publish. */
  wire: string;
  /** Opens the one reply to this request. */
  replyKey: Uint8Array;
}

/** {kReq, kRep} from the DH result, the phone's ephemeral public key and the request nonce. */
export function requestKeysFromDh(
  dh: Uint8Array,
  ephemeralPub: Uint8Array,
  n: Uint8Array,
): { kReq: Uint8Array; kRep: Uint8Array } {
  const prk = extract(sha256, dh, n);
  const okm = expand(sha256, prk, concatBytes(REQUEST_INFO, ephemeralPub), 2 * KEY_LEN);
  return { kReq: okm.slice(0, KEY_LEN), kRep: okm.slice(KEY_LEN, 2 * KEY_LEN) };
}

export function requestKeys(
  zoneStaticPub: Uint8Array,
  ephemeral: X25519KeyPair,
  n: Uint8Array,
): { kReq: Uint8Array; kRep: Uint8Array } {
  return requestKeysFromDh(x25519(ephemeral.priv, zoneStaticPub), ephemeral.pub, n);
}

export function sealRequest(
  zoneStaticPub: Uint8Array,
  subject: string,
  body: unknown,
  opts?: { nowMs?: number; ephemeral?: X25519KeyPair; n?: Uint8Array },
): SealedRequest {
  if (zoneStaticPub.length !== KEY_LEN) throw new SealedCryptoError("the home's key is not 32 bytes");
  const ephemeral = opts?.ephemeral ?? generateKeyPair();
  const n = opts?.n ?? randomBytes(REQUEST_NONCE_LEN);
  const { kReq, kRep } = requestKeys(zoneStaticPub, ephemeral, n);
  const plain = utf8(JSON.stringify({ ts: opts?.nowMs ?? Date.now(), body }));
  const c = chacha20poly1305(kReq, ZERO_NONCE, utf8(subject)).encrypt(plain);
  const wire = JSON.stringify({ v: 2, e: toBase64Url(ephemeral.pub), n: toBase64Url(n), c: toBase64Url(c) });
  return { subject, wire, replyKey: kRep };
}

/**
 * Opens the reply to `req`. Returns the home's reply object. A plain
 * `{"ok":false,"error":"sealed_refused"|"sealed_required"}` from the home and
 * anything that is neither that nor a verifiable sealed reply throw
 * SealedChannelError — a relay can drop a reply, but it cannot make the phone
 * believe one.
 */
export function openSealedReply(req: SealedRequest, replyText: string): Record<string, unknown> {
  let o: unknown;
  try {
    o = JSON.parse(replyText);
  } catch {
    throw new SealedChannelError('reply_not_sealed', 'reply is not JSON');
  }
  if (!o || typeof o !== 'object') throw new SealedChannelError('reply_not_sealed', 'reply is not an object');
  const m = o as Record<string, unknown>;
  if (String(m.v) === '2' && typeof m.c === 'string') {
    const c = fromBase64Url(m.c);
    if (!c) throw new SealedChannelError('reply_unverified', 'sealed reply is not base64url');
    let pt: Uint8Array;
    try {
      pt = chacha20poly1305(req.replyKey, ZERO_NONCE, utf8(req.subject)).decrypt(c);
    } catch {
      throw new SealedChannelError('reply_unverified', 'sealed reply did not open');
    }
    let inner: unknown;
    try {
      inner = JSON.parse(fromUtf8(pt));
    } catch {
      throw new SealedChannelError('reply_unverified', 'sealed reply is not JSON');
    }
    if (!inner || typeof inner !== 'object') {
      throw new SealedChannelError('reply_unverified', 'sealed reply is not an object');
    }
    return inner as Record<string, unknown>;
  }
  if (m.ok === false && typeof m.error === 'string'
    && (HOME_REFUSALS as readonly string[]).includes(m.error)) {
    throw new SealedChannelError(m.error, `the home refused the request (${m.error})`);
  }
  throw new SealedChannelError('reply_not_sealed', 'reply was not sealed');
}
