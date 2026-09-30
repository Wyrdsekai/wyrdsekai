/**
 * wyrdphone:// invite parsing.
 * Payload fixtures mirror exactly what registration.py mint_phone_invite
 * emits (sorted-key compact JSON, base64url, no padding).
 */
import {isPhoneInviteUrl, parsePhoneInvite} from '../../src/network/phoneInvite';

function encodeInvite(host: string, payload: object): string {
  const json = JSON.stringify(payload);
  const b64 = Buffer.from(json, 'utf8')
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  return `wyrdphone://${host}/${b64}`;
}

const SELF_SIGNED_PAYLOAD = {
  household_id: 'hh-9ce1d3b57ebf',
  kind: 'phone',
  minted_at: 1781225931,
  relays: [
    {
      ca_fp: 'E5:F0:A2:3E',
      fp: '63:24:CB:6B',
      nats_password: 'pw-secret',
      nats_user: 'relay_phone',
      ws_url: 'wss://127.0.0.1:4443',
    },
  ],
  v: 1,
  zone_id: 'unspecified',
};

describe('phoneInvite', () => {
  it('detects wyrdphone URLs case-insensitively with surrounding whitespace', () => {
    expect(isPhoneInviteUrl('  wyrdphone://x/abc ')).toBe(true);
    expect(isPhoneInviteUrl('WYRDPHONE://x/abc')).toBe(true);
    expect(isPhoneInviteUrl('wyrdrelay://x/abc')).toBe(false);
    expect(isPhoneInviteUrl('https://example.org')).toBe(false);
  });

  it('parses a self-signed relay invite end-to-end', () => {
    const invite = parsePhoneInvite(
      encodeInvite('127.0.0.1:4443', SELF_SIGNED_PAYLOAD),
    );
    expect(invite.relays).toHaveLength(1);
    const r = invite.relays[0];
    expect(r.wsUrl).toBe('wss://127.0.0.1:4443');
    expect(r.natsUser).toBe('relay_phone');
    expect(r.natsPassword).toBe('pw-secret');
    expect(r.fp).toBe('63:24:CB:6B');
    expect(r.caFp).toBe('E5:F0:A2:3E');
    expect(invite.householdId).toBe('hh-9ce1d3b57ebf');
    expect(invite.mintedAt).toBe(1781225931);
  });

  it('maps "unspecified" sentinel fields to undefined', () => {
    const invite = parsePhoneInvite(
      encodeInvite('relay.example.org', SELF_SIGNED_PAYLOAD),
    );
    expect(invite.zoneId).toBeUndefined();
  });

  it('parses an ACME invite (no pin material, default port)', () => {
    const invite = parsePhoneInvite(
      encodeInvite('relay.example.org', {
        kind: 'phone',
        relays: [
          {
            nats_password: 'pw',
            nats_user: 'relay_phone',
            ws_url: 'wss://relay.example.org',
          },
        ],
        v: 1,
      }),
    );
    expect(invite.relays[0].fp).toBeUndefined();
    expect(invite.relays[0].caFp).toBeUndefined();
  });

  it('preserves relay failover ordering', () => {
    const invite = parsePhoneInvite(
      encodeInvite('a', {
        kind: 'phone',
        relays: [
          {nats_password: 'p1', nats_user: 'u1', ws_url: 'wss://first'},
          {nats_password: 'p2', nats_user: 'u2', ws_url: 'wss://second'},
        ],
        v: 1,
      }),
    );
    expect(invite.relays.map(r => r.wsUrl)).toEqual(['wss://first', 'wss://second']);
  });

  it('reads the 0.5.0 fields: the home key, the CA fingerprint and the home-network address', () => {
    const zk = 'hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo';
    const fp = 'AB'.repeat(32);
    const invite = parsePhoneInvite(
      encodeInvite('relay.example.org', {
        ...SELF_SIGNED_PAYLOAD,
        zone_id: 'zone-example',
        zk,
        home_ca_fp: fp.match(/.{2}/g)!.join(':'),
        lan_https: 'https://198.51.100.20:7443/',
      }),
    );
    expect(invite.zk).toBe(zk);
    expect(invite.homeCaFp).toBe('ab'.repeat(32));
    expect(invite.lanHttps).toBe('https://198.51.100.20:7443');
  });

  it("reads the home bus as the home gives it (its port, not an assumed 4223), wss only", () => {
    const invite = parsePhoneInvite(encodeInvite('h', {
      ...SELF_SIGNED_PAYLOAD, lan_https: 'https://198.51.100.20:27443', home_bus: 'wss://198.51.100.20:27223/',
    }));
    expect(invite.homeBus).toBe('wss://198.51.100.20:27223');
    expect(parsePhoneInvite(encodeInvite('h', { ...SELF_SIGNED_PAYLOAD, home_bus: 'ws://198.51.100.20:4223' })).homeBus)
      .toBeUndefined();
    expect(parsePhoneInvite(encodeInvite('h', { ...SELF_SIGNED_PAYLOAD, home_bus: 'nats://198.51.100.20:4222' })).homeBus)
      .toBeUndefined();
  });

  it('an invite older than 0.5.0 simply has none of them', () => {
    const invite = parsePhoneInvite(encodeInvite('h', SELF_SIGNED_PAYLOAD));
    expect(invite.zk).toBeUndefined();
    expect(invite.homeCaFp).toBeUndefined();
    expect(invite.lanHttps).toBeUndefined();
    expect(invite.homeBus).toBeUndefined();
  });

  it('refuses a damaged home key or fingerprint, and never takes a plain http address', () => {
    expect(() => parsePhoneInvite(encodeInvite('h', { ...SELF_SIGNED_PAYLOAD, zk: 'short' }))).toThrow(/home key/);
    expect(() => parsePhoneInvite(encodeInvite('h', { ...SELF_SIGNED_PAYLOAD, home_ca_fp: 'abcd' }))).toThrow(/fingerprint/);
    const invite = parsePhoneInvite(
      encodeInvite('h', { ...SELF_SIGNED_PAYLOAD, lan_https: 'http://198.51.100.20:7070' }));
    expect(invite.lanHttps).toBeUndefined();
  });

  it('rejects malformed input with readable errors', () => {
    expect(() => parsePhoneInvite('https://nope')).toThrow(/wyrdphone/);
    expect(() => parsePhoneInvite('wyrdphone://host-only')).toThrow(/payload/);
    expect(() => parsePhoneInvite('wyrdphone://h/!!notb64!!')).toThrow(/not valid/);
    expect(() =>
      parsePhoneInvite(encodeInvite('h', {kind: 'zone', relays: [], v: 1})),
    ).toThrow(/Not a phone invite/);
    expect(() =>
      parsePhoneInvite(encodeInvite('h', {kind: 'phone', relays: [], v: 1})),
    ).toThrow(/no relays/);
    expect(() =>
      parsePhoneInvite(
        encodeInvite('h', {kind: 'phone', relays: [{ws_url: 'wss://x'}], v: 1}),
      ),
    ).toThrow(/incomplete/);
  });
});
