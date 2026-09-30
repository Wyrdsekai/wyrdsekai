/**
 * Sealed requests v2 ( W3), phone side. The vector file
 * is a byte-for-byte copy of core/src/test/resources/crypto/request-v2-vectors.json.
 */
import { chacha20poly1305 } from '@noble/ciphers/chacha';
import { extract } from '@noble/hashes/hkdf';
import { sha256 } from '@noble/hashes/sha2';
import V from '../fixtures/crypto/request-v2-vectors.json';
import {
  SealedChannelError,
  openSealedReply,
  requestKeys,
  requestKeysFromDh,
  sealRequest,
} from '../../src/crypto/sealedRequest';
import { generateKeyPair, keyPairFromPrivate, x25519 } from '../../src/crypto/sealedTunnel';
import { bytesToHex, fromBase64Url, fromUtf8, hexToBytes, toBase64Url, utf8 } from '../../src/crypto/bytes';

const h = hexToBytes;
const ZERO = new Uint8Array(12);
const phoneEph = () => keyPairFromPrivate(h(V.phone_ephemeral_priv));

/** The home's side: open a request wire body and seal a reply, as McpNatsHandler does. */
function homeOpen(zoneStaticPriv: Uint8Array, subject: string, wire: string) {
  const w = JSON.parse(wire);
  const e = fromBase64Url(w.e)!;
  const n = fromBase64Url(w.n)!;
  const { kReq, kRep } = requestKeysFromDh(x25519(zoneStaticPriv, e), e, n);
  const inner = JSON.parse(fromUtf8(chacha20poly1305(kReq, ZERO, utf8(subject)).decrypt(fromBase64Url(w.c)!)));
  const reply = (obj: unknown) => JSON.stringify({
    v: 2, c: toBase64Url(chacha20poly1305(kRep, ZERO, utf8(subject)).encrypt(utf8(JSON.stringify(obj)))),
  });
  return { inner, reply };
}

describe('sealed request v2 — interop vector', () => {
  it('derives dh, prk, k_req and k_rep', () => {
    expect(bytesToHex(x25519(h(V.phone_ephemeral_priv), h(V.zone_static_pub)))).toBe(V.dh);
    expect(bytesToHex(extract(sha256, h(V.dh), h(V.n)))).toBe(V.prk);
    const { kReq, kRep } = requestKeys(h(V.zone_static_pub), phoneEph(), h(V.n));
    expect(bytesToHex(kReq)).toBe(V.k_req);
    expect(bytesToHex(kRep)).toBe(V.k_rep);
  });

  it('builds the exact request wire body', () => {
    const body = JSON.parse(V.request_plain).body;
    const req = sealRequest(h(V.zone_static_pub), V.subject, body,
      { nowMs: JSON.parse(V.request_plain).ts, ephemeral: phoneEph(), n: h(V.n) });
    expect(req.wire).toBe(V.request_wire);
    expect(bytesToHex(fromBase64Url(JSON.parse(req.wire).c)!)).toBe(V.request_ct);
    expect(bytesToHex(req.replyKey)).toBe(V.k_rep);
  });

  it("opens the vector's reply wire body", () => {
    const req = sealRequest(h(V.zone_static_pub), V.subject, {}, { ephemeral: phoneEph(), n: h(V.n) });
    expect(openSealedReply(req, V.reply_wire)).toEqual(JSON.parse(V.reply_plain));
  });

  it('reproduces the home side of the vector too', () => {
    const inner = chacha20poly1305(h(V.k_req), ZERO, utf8(V.subject)).decrypt(h(V.request_ct));
    expect(fromUtf8(inner)).toBe(V.request_plain);
    const replyCt = chacha20poly1305(h(V.k_rep), ZERO, utf8(V.subject)).encrypt(utf8(V.reply_plain));
    expect(bytesToHex(replyCt)).toBe(V.reply_ct);
    expect(JSON.stringify({ v: 2, c: toBase64Url(replyCt) })).toBe(V.reply_wire);
  });
});

describe('sealed request v2 — round trips and refusals', () => {
  const zone = generateKeyPair();
  const subject = 'wyrd.zone.home.mcp.login';

  it('round-trips a request and its reply', () => {
    const req = sealRequest(zone.pub, subject, { username: 'rose-petal', password: 'correct-horse-battery-staple' });
    expect(req.wire).not.toContain('rose-petal');
    expect(req.wire).not.toContain('correct-horse-battery-staple');
    const home = homeOpen(zone.priv, subject, req.wire);
    expect(home.inner.body).toEqual({ username: 'rose-petal', password: 'correct-horse-battery-staple' });
    expect(Math.abs(home.inner.ts - Date.now())).toBeLessThan(5000);
    expect(openSealedReply(req, home.reply({ ok: true, token: 't' }))).toEqual({ ok: true, token: 't' });
  });

  it('uses a fresh key and nonce per request', () => {
    const a = JSON.parse(sealRequest(zone.pub, subject, {}).wire);
    const b = JSON.parse(sealRequest(zone.pub, subject, {}).wire);
    expect(a.e).not.toBe(b.e);
    expect(a.n).not.toBe(b.n);
    expect(fromBase64Url(a.n)!.length).toBe(16);
  });

  it('refuses a plaintext reply a relay could have written', () => {
    const req = sealRequest(zone.pub, subject, {});
    const code = (text: string) => {
      try {
        openSealedReply(req, text);
        return 'opened';
      } catch (e) {
        return e instanceof SealedChannelError ? e.code : 'other';
      }
    };
    expect(code('{"ok":true,"token":"forged"}')).toBe('reply_not_sealed');
    expect(code('{"ok":false,"error":"invalid credentials"}')).toBe('reply_not_sealed');
    expect(code('not json')).toBe('reply_not_sealed');
  });

  it("passes the home's own refusals through as refusals", () => {
    const req = sealRequest(zone.pub, subject, {});
    expect(() => openSealedReply(req, '{"ok":false,"error":"sealed_refused"}')).toThrow(
      expect.objectContaining({ code: 'sealed_refused' }));
    expect(() => openSealedReply(req, '{"ok":false,"error":"sealed_required"}')).toThrow(
      expect.objectContaining({ code: 'sealed_required' }));
  });

  it('refuses an altered reply, a reply for another subject and a reply to another request', () => {
    const req = sealRequest(zone.pub, subject, {});
    const home = homeOpen(zone.priv, subject, req.wire);
    const good = JSON.parse(home.reply({ ok: true }));
    const ct = fromBase64Url(good.c)!;
    ct[0] ^= 1;
    expect(() => openSealedReply(req, JSON.stringify({ v: 2, c: toBase64Url(ct) }))).toThrow(
      expect.objectContaining({ code: 'reply_unverified' }));

    const other = sealRequest(zone.pub, 'wyrd.zone.home.study.journal', {});
    const otherHome = homeOpen(zone.priv, 'wyrd.zone.home.study.journal', other.wire);
    expect(() => openSealedReply(req, otherHome.reply({ ok: true }))).toThrow(
      expect.objectContaining({ code: 'reply_unverified' }));
    const sameSubject = sealRequest(zone.pub, subject, {});
    expect(() => openSealedReply(sameSubject, home.reply({ ok: true }))).toThrow(
      expect.objectContaining({ code: 'reply_unverified' }));
  });

  it('cannot be opened by anyone but the home', () => {
    const req = sealRequest(zone.pub, subject, { password: 'pw' });
    expect(() => homeOpen(generateKeyPair().priv, subject, req.wire)).toThrow();
  });
});
