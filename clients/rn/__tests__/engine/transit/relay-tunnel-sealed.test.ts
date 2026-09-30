/**
 * RelayTunnelServerConnection speaks the sealed tunnel v2 (
 * W3) through a relay that only routes. The "home" here is built from the same
 * primitives as SealedTunnel.accept on the Java side.
 */
import type { BetweenClient, BetweenMessageHandler } from '../../../src/engine/between/BetweenClient';
import { RelayTunnelServerConnection } from '../../../src/engine/transit/RelayTunnelServerConnection';
import type { S2CMessage } from '../../../src/protocol/s2c';
import { fromUtf8, toBase64Url, utf8, fromBase64Url } from '../../../src/crypto/bytes';
import {
  TunnelChannel,
  deriveTunnelKeys,
  generateKeyPair,
  x25519,
  type X25519KeyPair,
} from '../../../src/crypto/sealedTunnel';

const ZONE = 'zone-example';
const SESSION = '0123456789abcdef0123456789abcdef';
const BASE = `wyrd.tunnel.${ZONE}.${SESSION}`;

/** A relay: records what the phone publishes, lets the test deliver `.down` frames. */
class FakeRelay implements BetweenClient {
  published: Array<{ subject: string; data: Uint8Array }> = [];
  private subs = new Map<string, BetweenMessageHandler>();
  get isConnected() { return true; }
  async connect() {}
  async disconnect() {}
  publish(subject: string, data: Uint8Array) { this.published.push({ subject, data: data.slice() }); }
  subscribe(subject: string, handler: BetweenMessageHandler) {
    this.subs.set(subject, handler);
    return () => { this.subs.delete(subject); };
  }
  deliver(subject: string, data: Uint8Array) { this.subs.get(subject)?.(subject, data); }
  on(suffix: string) { return this.published.filter((p) => p.subject === `${BASE}.${suffix}`); }
}

/** The home's side of the session, as SealedTunnel.accept builds it. */
function homeAccept(zoneStatic: X25519KeyPair, phoneEphPub: Uint8Array) {
  const eph = generateKeyPair();
  const { kUp, kDown } = deriveTunnelKeys(
    x25519(zoneStatic.priv, phoneEphPub), x25519(eph.priv, phoneEphPub), SESSION, phoneEphPub, eph.pub);
  return {
    acceptFrame: utf8(JSON.stringify({ v: 2, e: toBase64Url(eph.pub) })),
    up: new TunnelChannel(kUp),
    down: new TunnelChannel(kDown),
  };
}

function setup(opts?: { token?: string | null; zk?: string | null }) {
  const zoneStatic = generateKeyPair();
  const relay = new FakeRelay();
  const zk = opts?.zk === undefined ? toBase64Url(zoneStatic.pub) : opts.zk;
  const conn = new RelayTunnelServerConnection(relay, ZONE, opts?.token === undefined ? 'sess-token' : opts.token, zk, SESSION);
  const got: S2CMessage[] = [];
  conn.onMessage((m) => got.push(m));
  return { zoneStatic, relay, conn, got };
}

function openAndAccept(s: ReturnType<typeof setup>) {
  s.conn.open();
  const open = JSON.parse(fromUtf8(s.relay.on('open')[0].data));
  const home = homeAccept(s.zoneStatic, fromBase64Url(open.e)!);
  s.relay.deliver(`${BASE}.down`, home.acceptFrame);
  return home;
}

const upAad = utf8(`${BASE}.up`);
const downAad = utf8(`${BASE}.down`);

describe('RelayTunnelServerConnection — sealed tunnel v2', () => {
  it('opens with an ephemeral key only; the token never crosses in the clear', () => {
    const s = setup();
    s.conn.open();
    const open = s.relay.on('open');
    expect(open).toHaveLength(1);
    const body = JSON.parse(fromUtf8(open[0].data));
    expect(Object.keys(body).sort()).toEqual(['e', 'v']);
    expect(body.v).toBe(2);
    expect(fromBase64Url(body.e)).toHaveLength(32);
    const everything = s.relay.published.map((p) => Buffer.from(p.data).toString('latin1')).join('|');
    expect(everything).not.toContain('sess-token');
  });

  it('sends the token as the first sealed frame, then the frames that waited, in order', () => {
    const s = setup();
    s.conn.open();
    s.conn.send({ type: 'command', id: 'c1', command: 'look', args: [], payload: {} } as never);
    expect(s.relay.on('up')).toHaveLength(0);
    const open = JSON.parse(fromUtf8(s.relay.on('open')[0].data));
    const home = homeAccept(s.zoneStatic, fromBase64Url(open.e)!);
    s.relay.deliver(`${BASE}.down`, home.acceptFrame);

    const up = s.relay.on('up');
    expect(up).toHaveLength(2);
    expect(JSON.parse(fromUtf8(home.up.open(up[0].data, upAad)))).toEqual({ token: 'sess-token' });
    expect(JSON.parse(fromUtf8(home.up.open(up[1].data, upAad)))).toMatchObject({ command: 'look' });
    expect(Buffer.from(up[1].data).toString('latin1')).not.toContain('look');
    expect(s.conn.isSealed).toBe(true);
  });

  it('a guest session sends an empty sealed open', () => {
    const s = setup({ token: null });
    const home = openAndAccept(s);
    expect(JSON.parse(fromUtf8(home.up.open(s.relay.on('up')[0].data, upAad)))).toEqual({});
  });

  it('renders sealed frames from the home', () => {
    const s = setup();
    const home = openAndAccept(s);
    s.relay.deliver(`${BASE}.down`, home.down.seal(utf8('{"type":"prose","seq":1,"text":"hi"}'), downAad));
    expect(s.got).toEqual([{ type: 'prose', seq: 1, text: 'hi' }]);
  });

  it('closes the session on a frame that does not open', () => {
    const s = setup();
    const home = openAndAccept(s);
    const f = home.down.seal(utf8('{"type":"prose","seq":1,"text":"hi"}'), downAad);
    f[f.length - 1] ^= 1;
    s.relay.deliver(`${BASE}.down`, f);
    expect(s.got).toHaveLength(1);
    expect(s.got[0]).toMatchObject({ type: 'error', code: 'tunnel_interrupted' });
    expect(s.relay.on('close')).toHaveLength(1);
    expect(s.conn.isConnected).toBe(false);
    // A closed session stays closed: nothing more goes up.
    const before = s.relay.published.length;
    s.conn.send({ type: 'command', id: 'c2', command: 'look', args: [], payload: {} } as never);
    expect(s.relay.published.length).toBe(before);
  });

  it('does not accept plaintext frames once sealed', () => {
    const s = setup();
    openAndAccept(s);
    s.relay.deliver(`${BASE}.down`, utf8('{"type":"prose","seq":1,"text":"forged"}'));
    expect(s.got.map((m) => m.type)).toEqual(['error']);
  });

  it('cannot be answered by a relay that lacks the home key', () => {
    const s = setup();
    s.conn.open();
    const open = JSON.parse(fromUtf8(s.relay.on('open')[0].data));
    const impostor = homeAccept(generateKeyPair(), fromBase64Url(open.e)!);
    s.relay.deliver(`${BASE}.down`, impostor.acceptFrame);
    s.relay.deliver(`${BASE}.down`, impostor.down.seal(utf8('{"type":"prose","seq":1,"text":"x"}'), downAad));
    expect(s.got).toEqual([expect.objectContaining({ type: 'error', code: 'tunnel_interrupted' })]);
  });

  it("shows the home's refusal of an unsealed or unreadable open, in plain words", () => {
    const s = setup();
    s.conn.open();
    s.relay.deliver(`${BASE}.down`, utf8(
      '{"type":"error","seq":0,"code":"tunnel_plaintext_refused","message":"This home now requires an encrypted connection."}'));
    expect(s.got[0]).toMatchObject({ type: 'error', code: 'tunnel_plaintext_refused' });
    expect((s.got[0] as { message: string }).message).toMatch(/wyrd phone invite/);
    expect(s.conn.isConnected).toBe(false);
  });

  it('passes other refusals through (tunnel_busy)', () => {
    const s = setup();
    s.conn.open();
    s.relay.deliver(`${BASE}.down`, utf8('{"type":"error","seq":0,"code":"tunnel_busy","message":"too many open sessions on this zone"}'));
    expect(s.got[0]).toMatchObject({ code: 'tunnel_busy', message: 'too many open sessions on this zone' });
  });

  it('without the home key it sends nothing and asks to pair again', () => {
    const s = setup({ zk: null });
    s.conn.open();
    s.conn.send({ type: 'command', id: 'c1', command: 'look', args: [], payload: {} } as never);
    expect(s.relay.published).toHaveLength(0);
    expect(s.got[0]).toMatchObject({ type: 'error', code: 'tunnel_pair_again' });
    expect((s.got[0] as { message: string }).message).toMatch(/wyrd phone invite/);
  });

  it('ignores anything before the accept frame that is neither accept nor refusal', () => {
    const s = setup();
    s.conn.open();
    s.relay.deliver(`${BASE}.down`, utf8('{"type":"prose","seq":1,"text":"early"}'));
    expect(s.got).toHaveLength(0);
    const open = JSON.parse(fromUtf8(s.relay.on('open')[0].data));
    const home = homeAccept(s.zoneStatic, fromBase64Url(open.e)!);
    s.relay.deliver(`${BASE}.down`, home.acceptFrame);
    expect(s.conn.isSealed).toBe(true);
  });
});
