/**
 * zoneConnect tests ( — cross-relay auto-attempt).
 *
 * Mock NatsServerClient so each relay's connect/probe/login behavior is driven
 * by a per-relayUrl script. Asserts the three-stage fall-through:
 *   connect-fail → next relay · probe-null → next relay · login-reject → STOP.
 */
type Behavior = {
  connect?: 'ok' | 'throw';
  probe?: 'ok' | 'null' | 'throw' | 'sealed';
  login?: 'ok' | 'throw' | 'sealed';
};
const script = new Map<string, Behavior>();
const disconnected: string[] = [];

const constructed: Array<{ relayUrl: string; zk?: string | null }> = [];

jest.mock('../NatsServerClient', () => ({
  NatsServerClient: class {
    relayUrl: string;
    zoneId: string;
    constructor(opts: { relayUrl: string; zoneId: string; zk?: string | null }) {
      this.relayUrl = opts.relayUrl;
      this.zoneId = opts.zoneId;
      constructed.push({ relayUrl: opts.relayUrl, zk: opts.zk });
    }
    async connect() {
      if (script.get(this.relayUrl)?.connect === 'throw') throw new Error('connect() timed out after 8s');
    }
    async probe() {
      const b = script.get(this.relayUrl)?.probe ?? 'ok';
      if (b === 'throw') throw new Error('probe boom');
      if (b === 'sealed') throw new SealedChannelError('sealed_refused', 'the home refused the sealed request');
      if (b === 'null') return null;
      return { hasUsers: true, openRegistration: false, zoneId: this.zoneId };
    }
    async login(username: string) {
      if (script.get(this.relayUrl)?.login === 'throw') throw new Error('invalid credentials');
      if (script.get(this.relayUrl)?.login === 'sealed') throw new SealedChannelError('reply_unverified', 'reply could not be checked');
      return { token: `tok-${this.relayUrl}`, userId: username, username, zoneId: this.zoneId };
    }
    async disconnect() { disconnected.push(this.relayUrl); }
  },
}));

import { connectToZone } from '../zoneConnect';
import { SealedChannelError } from '../../crypto/sealedRequest';
import type { HeldRelay, ZoneBankEntry } from '../../state/zoneBankStore';

const relay = (wsUrl: string): HeldRelay => ({ wsUrl, natsUser: 'relay_phone', natsPass: 'pw', addedAt: 0 });
const ZONE: ZoneBankEntry = { zoneId: 'home-server', displayName: 'home-server', relayUrls: [], username: 'operator', addedAt: 0 };

const A = 'wss://relay-node:4443';
const B = 'wss://relay-b:4443';
const C = 'wss://wyrdsekai.org:4443';

beforeEach(() => { script.clear(); disconnected.length = 0; constructed.length = 0; });

describe('connectToZone', () => {
  it('logs in on the first reachable relay and reports which one won', async () => {
    script.set(A, { connect: 'ok', probe: 'ok', login: 'ok' });
    const r = await connectToZone(ZONE, [relay(A), relay(B)], 'pw');
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.relayUrl).toBe(A);
      expect(r.auth.token).toBe(`tok-${A}`);
    }
  });

  it('falls through a down relay (connect throws) to the next', async () => {
    script.set(A, { connect: 'throw' });
    script.set(B, { connect: 'ok', probe: 'ok', login: 'ok' });
    const r = await connectToZone(ZONE, [relay(A), relay(B)], 'pw');
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.relayUrl).toBe(B);
    expect(disconnected).toContain(A); // cleaned up the failed connection
  });

  it('falls through a relay that does not carry the zone (probe null) to the next', async () => {
    script.set(A, { connect: 'ok', probe: 'null' });
    script.set(B, { connect: 'ok', probe: 'ok', login: 'ok' });
    const r = await connectToZone(ZONE, [relay(A), relay(B)], 'pw');
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.relayUrl).toBe(B);
  });

  it('STOPS on auth rejection — does not try other relays after a real login failure', async () => {
    script.set(A, { connect: 'ok', probe: 'ok', login: 'throw' });
    script.set(B, { connect: 'ok', probe: 'ok', login: 'ok' }); // would succeed, must NOT be tried
    const r = await connectToZone(ZONE, [relay(A), relay(B)], 'wrong');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.authRejected).toBe(true);
    expect(disconnected).not.toContain(B); // B never opened
  });

  it('reports unreachable (authRejected=false) when no relay carries the zone', async () => {
    script.set(A, { connect: 'ok', probe: 'null' });
    script.set(B, { connect: 'throw' });
    const r = await connectToZone(ZONE, [relay(A), relay(B)], 'pw');
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.authRejected).toBe(false);
      expect(r.attempts).toHaveLength(2);
    }
  });

  it("hands every relay's client the home's key", async () => {
    script.set(A, { connect: 'throw' });
    script.set(B, { connect: 'ok', probe: 'ok', login: 'ok' });
    await connectToZone(ZONE, [relay(A), relay(B)], 'pw', { zk: 'ZK' });
    expect(constructed).toEqual([{ relayUrl: A, zk: 'ZK' }, { relayUrl: B, zk: 'ZK' }]);
  });

  it('STOPS on a sealed-layer refusal at probe, and does not call it an auth rejection', async () => {
    script.set(A, { connect: 'ok', probe: 'sealed' });
    script.set(B, { connect: 'ok', probe: 'ok', login: 'ok' }); // same home, same key: not tried
    const r = await connectToZone(ZONE, [relay(A), relay(B)], 'pw');
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.authRejected).toBe(false);
      expect(r.error).toMatch(/refused the sealed request/);
    }
    expect(constructed.map((c) => c.relayUrl)).toEqual([A]);
  });

  it('a reply that cannot be checked at login is not a wrong password', async () => {
    script.set(A, { connect: 'ok', probe: 'ok', login: 'sealed' });
    const r = await connectToZone(ZONE, [relay(A)], 'pw');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.authRejected).toBe(false);
  });

  it('errors clearly when the device holds no relay for the zone', async () => {
    const r = await connectToZone(ZONE, [], 'pw');
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.authRejected).toBe(false);
      expect(r.error).toMatch(/no relay/i);
    }
  });
});
