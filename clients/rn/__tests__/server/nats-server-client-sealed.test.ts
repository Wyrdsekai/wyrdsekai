/**
 * NatsServerClient seals every zone request to the home's key and believes only
 * sealed replies ( W3), and asks the relay for replies
 * on its own inbox prefix (D4). nats.ws is replaced by a fake whose "home" opens
 * requests with the zone's private key, as McpNatsHandler does.
 */
import { chacha20poly1305 } from '@noble/ciphers/chacha';
import { fromBase64Url, fromUtf8, toBase64Url, utf8 } from '../../src/crypto/bytes';
import { SealedChannelError, requestKeysFromDh } from '../../src/crypto/sealedRequest';
import { generateKeyPair, x25519 } from '../../src/crypto/sealedTunnel';

type Handler = (subject: string, inner: { ts: number; body: Record<string, unknown> } | null, raw: string)
  => { sealed?: Record<string, unknown>; plain?: string };

const zone = generateKeyPair();
const otherZone = generateKeyPair();
const ZERO = new Uint8Array(12);
/** Which home answers a subject: `wyrd.zone.other-zone.*` is another home with its own key. */
const homeKeyFor = (subject: string) => (subject.startsWith('wyrd.zone.other-zone.') ? otherZone : zone);
let handler: Handler;
let connectOpts: Record<string, unknown> | null = null;
const sent: Array<{ subject: string; raw: string }> = [];

jest.mock('nats.ws', () => ({
  connect: async (opts: Record<string, unknown>) => {
    connectOpts = opts;
    return {
      isClosed: () => false,
      drain: async () => {},
      publish: () => {},
      subscribe: () => ({ unsubscribe: () => {} }),
      request: async (subject: string, payload: Uint8Array) => {
        const raw = fromUtf8(payload);
        sent.push({ subject, raw });
        let inner = null;
        let kRep: Uint8Array | null = null;
        try {
          const w = JSON.parse(raw);
          if (w.v === 2) {
            const e = fromBase64Url(w.e)!;
            const keys = requestKeysFromDh(x25519(homeKeyFor(subject).priv, e), e, fromBase64Url(w.n)!);
            kRep = keys.kRep;
            inner = JSON.parse(fromUtf8(chacha20poly1305(keys.kReq, ZERO, utf8(subject)).decrypt(fromBase64Url(w.c)!)));
          }
        } catch {
          inner = null;
        }
        const out = handler(subject, inner, raw);
        const text = out.sealed && kRep
          ? JSON.stringify({ v: 2, c: toBase64Url(chacha20poly1305(kRep, ZERO, utf8(subject)).encrypt(utf8(JSON.stringify(out.sealed)))) })
          : out.plain ?? '{}';
        return { data: utf8(text) };
      },
    };
  },
}));

import { NatsServerClient } from '../../src/server/NatsServerClient';

const ZK = toBase64Url(zone.pub);
const client = (zk: string | null = ZK) => new NatsServerClient({
  relayUrl: 'wss://relay.example:4443', zoneId: 'zone-example', user: 'hh-1', password: 'relay-pw', zk,
});

beforeEach(() => {
  sent.length = 0;
  connectOpts = null;
  handler = () => ({ sealed: { ok: true } });
});

describe('NatsServerClient — sealed requests', () => {
  it('asks for replies on its own inbox prefix', async () => {
    await client().connect();
    expect(connectOpts?.inboxPrefix).toBe('_INBOX.hh-1');
  });

  it('seals a login: the password never crosses the relay in the clear', async () => {
    handler = (subject, inner) => {
      expect(subject).toBe('wyrd.zone.zone-example.mcp.login');
      expect(inner?.body).toEqual({ username: 'rose', password: 'correct horse' });
      expect(Math.abs((inner?.ts ?? 0) - Date.now())).toBeLessThan(5000);
      return { sealed: { ok: true, token: 'sess-1', userId: 'u-1', username: 'rose', role: 'member' } };
    };
    const auth = await client().login('rose', 'correct horse');
    expect(auth.token).toBe('sess-1');
    expect(sent[0].raw).not.toContain('correct horse');
    expect(Object.keys(JSON.parse(sent[0].raw)).sort()).toEqual(['c', 'e', 'n', 'v']);
  });

  it('seals token-bearing requests too (tell, journal, zone bank)', async () => {
    handler = (subject) => ({ sealed: subject.endsWith('mcp.login') ? { ok: true, token: 'sess-1' } : { ok: true, entries: [] } });
    const c = client();
    await c.login('rose', 'pw');
    await c.tell('mia', 'hello there');
    await c.listJournal();
    await c.getZoneBank();
    for (const s of sent) {
      expect(s.raw).not.toContain('sess-1');
      expect(JSON.parse(s.raw).v).toBe(2);
    }
  });

  it('refuses a reply the relay could have written, without calling it a wrong password', async () => {
    handler = () => ({ plain: '{"ok":true,"token":"forged"}' });
    await expect(client().login('rose', 'pw')).rejects.toBeInstanceOf(SealedChannelError);
    handler = () => ({ plain: '{"ok":false,"error":"invalid credentials"}' });
    await expect(client().login('rose', 'pw')).rejects.toMatchObject({ code: 'reply_not_sealed' });
  });

  it("reports the home's refusal of the sealed request in plain words", async () => {
    handler = () => ({ plain: '{"ok":false,"error":"sealed_refused"}' });
    const err = await client().login('rose', 'pw').catch((e) => e);
    expect(err).toBeInstanceOf(SealedChannelError);
    expect(err.code).toBe('sealed_refused');
    expect(err.message).toMatch(/date and time/);
  });

  it('passes the account decision through unchanged', async () => {
    handler = () => ({ sealed: { ok: false, error: 'invalid credentials' } });
    const err = await client().login('rose', 'pw').catch((e) => e);
    expect(err).not.toBeInstanceOf(SealedChannelError);
    expect(err.message).toBe('invalid credentials');
  });

  it('without the home key sends nothing and asks to pair again', async () => {
    const c = client(null);
    expect(c.hasHomeKey()).toBe(false);
    await expect(c.probe()).rejects.toMatchObject({ code: 'pair_again' });
    await expect(c.login('rose', 'pw')).rejects.toBeInstanceOf(SealedChannelError);
    expect(sent).toHaveLength(0);
  });

  it('keeps zone discovery in the clear (it carries nothing)', async () => {
    handler = (_s, inner, raw) => {
      expect(inner).toBeNull();
      expect(raw).toBe('{}');
      return { plain: '{"ok":true,"zoneId":"zone-example"}' };
    };
    expect(await client(null).discoverZone()).toBe('zone-example');
  });

  it("reads this phone's home-bus credentials from pair.device", async () => {
    handler = (subject, inner) => {
      if (subject.endsWith('mcp.login')) return { sealed: { ok: true, token: 'sess-1' } };
      expect(subject).toBe('wyrd.zone.zone-example.pair.device');
      expect(inner?.body).toMatchObject({ token: 'sess-1', deviceName: 'phone-abc', deviceType: 'phone' });
      return { sealed: { ok: true, deviceToken: 'dev-1', natsUrl: 'nats://home:4222', nats_user: 'phone-7', nats_pass: 'np' } };
    };
    const c = client();
    await c.login('rose', 'pw');
    expect(await c.pairDevice('phone-abc')).toEqual({
      deviceToken: 'dev-1', natsUrl: 'nats://home:4222', natsUser: 'phone-7', natsPass: 'np',
    });
  });

  it('seals a knock to the zone it goes to, and sends none without that zone\'s key', async () => {
    handler = (subject, inner) => {
      expect(inner?.body).toMatchObject({ requesterName: 'rose' });
      return { sealed: { ok: true, requestId: subject.includes('other-zone') ? 'r-other' : 'r-own' } };
    };
    const c = client();
    expect(await c.requestAccess('zone-example', 'rose')).toEqual({ ok: true, data: { requestId: 'r-own' } });
    expect(await c.requestAccess('other-zone', 'rose', undefined, undefined, toBase64Url(otherZone.pub)))
      .toEqual({ ok: true, data: { requestId: 'r-other' } });
    const before = sent.length;
    const r = await c.requestAccess('third-zone', 'rose');
    expect(r.ok).toBe(false);
    expect(r.error).toMatch(/not sent/);
    expect(sent.length).toBe(before);
  });

  it('a relay that will not carry a knock to another zone gets a plain answer, at once', async () => {
    handler = (subject) => {
      throw new Error(`Permissions Violation for Publish to "${subject}"`);
    };
    const t0 = Date.now();
    const r = await client().requestAccess('other-zone', 'rose', undefined, undefined, toBase64Url(otherZone.pub));
    expect(Date.now() - t0).toBeLessThan(1000);
    expect(r.ok).toBe(false);
    expect(r.error).toMatch(/does not carry knocks to other zones/);
    expect(r.error).toMatch(/did not reach that zone/);
  });

  it('a knock nobody answers (503 no responders) is said plainly', async () => {
    handler = () => { throw new Error('503'); };
    const r = await client().requestAccess('other-zone', 'rose', undefined, undefined, toBase64Url(otherZone.pub));
    expect(r).toEqual({ ok: false, error: expect.stringMatching(/did not answer/) });
  });

  it('says plainly when the relay will not carry a request (a permissions violation), without waiting', async () => {
    handler = () => {
      throw new Error('Permissions Violation for Publish to "wyrd.zone.zone-example.auth.status"');
    };
    const c = client();
    await c.connect();
    const t0 = Date.now();
    const reply = await (c as unknown as { request: (s: string, b: object) => Promise<Record<string, unknown>> })
      .request('wyrd.zone.zone-example.auth.status', {});
    expect(Date.now() - t0).toBeLessThan(1000);
    expect(reply.ok).toBe(false);
    expect(String(reply.error)).toMatch(/relay would not carry this request/);
    expect(reply.refusedBySubject).toBe('wyrd.zone.zone-example.auth.status');
    // probe() reads it as "not this relay", so the zone ladder moves on.
    expect(await c.probe()).toBeNull();
  });

  it('makes anonymous phone passwords from the CSPRNG', async () => {
    const passwords = new Set<string>();
    handler = (_s, inner) => {
      passwords.add(String(inner?.body.password));
      return { sealed: { ok: true, token: 't' } };
    };
    await client().registerAndLogin('Wyrd');
    await client().registerAndLogin('Wyrd');
    expect(passwords.size).toBe(2);
    for (const p of passwords) expect(p).toMatch(/^[0-9a-f]{32}$/);
  });
});
