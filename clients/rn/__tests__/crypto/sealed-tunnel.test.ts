/**
 * Sealed tunnel v2 ( W3), phone side. The vector file is
 * a byte-for-byte copy of core/src/test/resources/crypto/tunnel-v2-vectors.json,
 * which the Java and Kotlin sides check too.
 */
import V from '../fixtures/crypto/tunnel-v2-vectors.json';
import {
  SealedCryptoError,
  TunnelChannel,
  TunnelHandshake,
  deriveTunnelKeys,
  generateKeyPair,
  keyPairFromPrivate,
  parseTunnelAccept,
  x25519,
} from '../../src/crypto/sealedTunnel';
import { bytesToHex, fromUtf8, hexToBytes, toBase64Url, utf8 } from '../../src/crypto/bytes';

const h = hexToBytes;

describe('sealed tunnel v2 — interop vector', () => {
  it('derives the RFC 7748 public keys', () => {
    expect(bytesToHex(keyPairFromPrivate(h(V.zone_static_priv)).pub)).toBe(V.zone_static_pub);
    expect(bytesToHex(keyPairFromPrivate(h(V.phone_ephemeral_priv)).pub)).toBe(V.phone_ephemeral_pub);
    expect(bytesToHex(keyPairFromPrivate(h(V.zone_ephemeral_priv)).pub)).toBe(V.zone_ephemeral_pub);
  });

  it('computes both DH terms the same way from either side', () => {
    expect(bytesToHex(x25519(h(V.phone_ephemeral_priv), h(V.zone_static_pub)))).toBe(V.dh_static);
    expect(bytesToHex(x25519(h(V.zone_static_priv), h(V.phone_ephemeral_pub)))).toBe(V.dh_static);
    expect(bytesToHex(x25519(h(V.phone_ephemeral_priv), h(V.zone_ephemeral_pub)))).toBe(V.dh_ephemeral);
    expect(bytesToHex(x25519(h(V.zone_ephemeral_priv), h(V.phone_ephemeral_pub)))).toBe(V.dh_ephemeral);
  });

  it('derives k_up and k_down', () => {
    const { kUp, kDown } = deriveTunnelKeys(
      h(V.dh_static), h(V.dh_ephemeral), V.session, h(V.phone_ephemeral_pub), h(V.zone_ephemeral_pub));
    expect(bytesToHex(kUp)).toBe(V.k_up);
    expect(bytesToHex(kDown)).toBe(V.k_down);
  });

  it('seals the first frame up and opens the first frame down, byte for byte', () => {
    const hs = new TunnelHandshake(h(V.zone_static_pub), V.session, keyPairFromPrivate(h(V.phone_ephemeral_priv)));
    const { up, down } = hs.finish(h(V.zone_ephemeral_pub));
    expect(bytesToHex(up.seal(utf8(V.up_plain), utf8(V.up_subject)))).toBe(V.up_sealed);
    expect(fromUtf8(down.open(h(V.down_sealed), utf8(V.down_subject)))).toBe(V.down_plain);
  });

  it('reproduces the home side of the vector too', () => {
    const homeUp = new TunnelChannel(h(V.k_up));
    const homeDown = new TunnelChannel(h(V.k_down));
    expect(fromUtf8(homeUp.open(h(V.up_sealed), utf8(V.up_subject)))).toBe(V.up_plain);
    expect(bytesToHex(homeDown.seal(utf8(V.down_plain), utf8(V.down_subject)))).toBe(V.down_sealed);
  });

  it('opens with {"v":2,"e":...} and no token in the clear', () => {
    const hs = new TunnelHandshake(h(V.zone_static_pub), V.session, keyPairFromPrivate(h(V.phone_ephemeral_priv)));
    const open = JSON.parse(hs.openPayload());
    expect(open).toEqual({ v: 2, e: toBase64Url(h(V.phone_ephemeral_pub)) });
    expect(hs.openPayload()).not.toContain('token');
  });

  it("reads the home's accept frame", () => {
    const accept = JSON.stringify({ v: 2, e: toBase64Url(h(V.zone_ephemeral_pub)) });
    expect(bytesToHex(parseTunnelAccept(accept)!)).toBe(V.zone_ephemeral_pub);
  });
});

/** The home's side of one session, built the way SealedTunnel.accept does it. */
function homeAccept(zoneStatic: { priv: Uint8Array }, phoneEphPub: Uint8Array, session: string) {
  const zoneEph = generateKeyPair();
  const { kUp, kDown } = deriveTunnelKeys(
    x25519(zoneStatic.priv, phoneEphPub), x25519(zoneEph.priv, phoneEphPub), session, phoneEphPub, zoneEph.pub);
  return { zoneEphPub: zoneEph.pub, up: new TunnelChannel(kUp), down: new TunnelChannel(kDown) };
}

describe('sealed tunnel v2 — round trips and refusals', () => {
  const session = 'a1b2c3d4e5f60718293a4b5c6d7e8f90';
  const upSubj = utf8(`wyrd.tunnel.z.${session}.up`);
  const downSubj = utf8(`wyrd.tunnel.z.${session}.down`);

  function pair() {
    const zoneStatic = generateKeyPair();
    const hs = new TunnelHandshake(zoneStatic.pub, session);
    const home = homeAccept(zoneStatic, hs.ephemeral.pub, session);
    const phone = hs.finish(home.zoneEphPub);
    return { zoneStatic, phone, home };
  }

  it('carries frames both ways in order', () => {
    const { phone, home } = pair();
    for (let i = 0; i < 5; i++) {
      const f = phone.up.seal(utf8(`up ${i}`), upSubj);
      expect(fromUtf8(home.up.open(f, upSubj))).toBe(`up ${i}`);
      const d = home.down.seal(utf8(`down ${i}`), downSubj);
      expect(fromUtf8(phone.down.open(d, downSubj))).toBe(`down ${i}`);
    }
  });

  it('refuses a replayed frame and a frame out of order', () => {
    const { phone, home } = pair();
    const f0 = home.down.seal(utf8('zero'), downSubj);
    const f1 = home.down.seal(utf8('one'), downSubj);
    expect(() => phone.down.open(f1, downSubj)).toThrow(SealedCryptoError);
    expect(fromUtf8(phone.down.open(f0, downSubj))).toBe('zero');
    expect(() => phone.down.open(f0, downSubj)).toThrow(/out of order/);
  });

  it('refuses an altered frame, another subject and the other direction', () => {
    const { phone, home } = pair();
    const f = home.down.seal(utf8('hello'), downSubj);
    const altered = f.slice();
    altered[altered.length - 1] ^= 1;
    expect(() => phone.down.open(altered, downSubj)).toThrow(SealedCryptoError);
    expect(() => phone.down.open(f, utf8('wyrd.tunnel.z.other.down'))).toThrow(SealedCryptoError);
    expect(() => phone.up.open(f, downSubj)).toThrow(SealedCryptoError);
    expect(() => phone.down.open(f.subarray(0, 20), downSubj)).toThrow(/too short/);
  });

  it('cannot be completed by a relay that does not hold the home key', () => {
    const zoneStatic = generateKeyPair();
    const hs = new TunnelHandshake(zoneStatic.pub, session);
    const impostor = homeAccept(generateKeyPair(), hs.ephemeral.pub, session);
    const phone = hs.finish(impostor.zoneEphPub);
    const f = impostor.down.seal(utf8('{"type":"welcome"}'), downSubj);
    expect(() => phone.down.open(f, downSubj)).toThrow(SealedCryptoError);
  });

  it('refuses a low-order public key', () => {
    expect(() => x25519(generateKeyPair().priv, new Uint8Array(32))).toThrow(SealedCryptoError);
    expect(() => x25519(generateKeyPair().priv, new Uint8Array(31))).toThrow(SealedCryptoError);
  });

  it('does not mistake anything else for an accept frame', () => {
    expect(parseTunnelAccept('{"type":"error","seq":0,"code":"tunnel_busy"}')).toBeNull();
    expect(parseTunnelAccept('{"v":1,"e":"AAAA"}')).toBeNull();
    expect(parseTunnelAccept(JSON.stringify({ v: 2, e: toBase64Url(new Uint8Array(31)) }))).toBeNull();
    expect(parseTunnelAccept('not json')).toBeNull();
  });

  it('uses a fresh ephemeral key per session', () => {
    const zk = generateKeyPair().pub;
    expect(new TunnelHandshake(zk, session).openPayload()).not.toBe(new TunnelHandshake(zk, session).openPayload());
  });
});
