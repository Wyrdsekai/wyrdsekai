/**
 * Wire format for all Between messages.
 * Signed with Ed25519 — signature covers src:dst:ts:payload.
 *
 * Mirrors server's BetweenEnvelope.java and KMP's BetweenEnvelope.kt.
 */

import type { NodeIdentity, CryptoProvider } from './NodeIdentity';

export interface BetweenEnvelope {
  v: number;
  src: string;
  dst: string | null;
  ts: number;
  sig: string;
  payload: unknown;
}

/** Build the data that gets signed: "src:dst:ts:payload". */
export function signingData(src: string, dst: string | null, ts: number, payload: unknown): Uint8Array {
  const dstStr = dst ?? '*';
  const payloadStr = JSON.stringify(payload);
  return new TextEncoder().encode(`${src}:${dstStr}:${ts}:${payloadStr}`);
}

/** Create and sign an envelope. */
export function createEnvelope(
  src: string,
  dst: string | null,
  payload: unknown,
  identity: NodeIdentity,
  crypto: CryptoProvider,
): BetweenEnvelope {
  const ts = Date.now();
  const data = signingData(src, dst, ts, payload);
  const sig = crypto.sign(identity.privateKey, data);
  return { v: 1, src, dst, ts, sig, payload };
}

/** Verify an envelope's signature against a peer's public key. */
export function verifyEnvelope(
  envelope: BetweenEnvelope,
  peerPublicKey: string,
  crypto: CryptoProvider,
): boolean {
  const data = signingData(envelope.src, envelope.dst, envelope.ts, envelope.payload);
  return crypto.verify(peerPublicKey, data, envelope.sig);
}

/** Serialize envelope to bytes. */
export function envelopeToBytes(envelope: BetweenEnvelope): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(envelope));
}

/** Deserialize envelope from bytes. */
export function envelopeFromBytes(data: Uint8Array): BetweenEnvelope {
  return JSON.parse(new TextDecoder().decode(data)) as BetweenEnvelope;
}
