/**
 * openZone tests ( orchestration).
 *
 * Real zoneBankStore (with in-memory secureStorage); mocked zoneConnect.
 */
const mem = new Map<string, string>();
jest.mock('../../state/secureStorage', () => ({
  secureStorage: {
    async getItem(k: string) { return mem.has(k) ? mem.get(k)! : null; },
    async setItem(k: string, v: string) { mem.set(k, v); },
    async removeItem(k: string) { mem.delete(k); },
  },
}));

let connectImpl: jest.Mock;
jest.mock('../zoneConnect', () => ({
  connectToZone: (...args: unknown[]) => connectImpl(...args),
}));

import { openZone, forgetZonePassword } from '../openZone';
import { useZoneBankStore, zonePasswordKey } from '../../state/zoneBankStore';

const A = 'wss://relay-node:4443';
const B = 'wss://relay-b:4443';
const fakeClient = { _id: 'client' } as never;

function seedBank() {
  const s = useZoneBankStore.getState();
  s.addRelay({ wsUrl: A, natsUser: 'u', natsPass: 'p' });
  s.addRelay({ wsUrl: B, natsUser: 'u', natsPass: 'p' });
  // Entry prefers A first.
  s.addOrUpdateZone({ zoneId: 'home-server', displayName: 'home-server', relayUrls: [A, B], username: 'operator' });
}

beforeEach(() => {
  mem.clear();
  useZoneBankStore.setState({ relays: [], zones: [], loaded: false });
  connectImpl = jest.fn();
});

describe('openZone', () => {
  it('returns needs-password when no password is provided or remembered', async () => {
    seedBank();
    const r = await openZone('home-server');
    expect(r).toEqual({ ok: false, reason: 'needs-password' });
    expect(connectImpl).not.toHaveBeenCalled();
  });

  it('uses an explicit password, remembers it, and learns the winning relay', async () => {
    seedBank();
    connectImpl.mockResolvedValue({ ok: true, client: fakeClient, relayUrl: B, auth: { token: 't' } });
    const r = await openZone('home-server', { password: 'secret' });
    expect(r.ok).toBe(true);
    // Password remembered for this device.
    expect(mem.get(zonePasswordKey('home-server'))).toBe('secret');
    // Winning relay (B) bumped to the front of the entry's preference order.
    expect(useZoneBankStore.getState().getZone('home-server')?.relayUrls).toEqual([B, A]);
    // lastUsedAt set.
    expect(useZoneBankStore.getState().getZone('home-server')?.lastUsedAt).toBeDefined();
  });

  it('uses a remembered password on a later open (no prompt)', async () => {
    seedBank();
    mem.set(zonePasswordKey('home-server'), 'remembered');
    connectImpl.mockResolvedValue({ ok: true, client: fakeClient, relayUrl: A, auth: { token: 't' } });
    const r = await openZone('home-server');
    expect(r.ok).toBe(true);
    // connectToZone was called with the remembered password.
    expect(connectImpl).toHaveBeenCalledWith(
      expect.objectContaining({ zoneId: 'home-server' }),
      expect.any(Array),
      'remembered',
      expect.any(Object),
    );
  });

  it('maps an auth rejection to reason auth-rejected', async () => {
    seedBank();
    connectImpl.mockResolvedValue({ ok: false, authRejected: true, error: 'bad password', attempts: [] });
    const r = await openZone('home-server', { password: 'wrong' });
    expect(r).toEqual({ ok: false, reason: 'auth-rejected', error: 'bad password' });
  });

  it('maps an unreachable result to reason unreachable', async () => {
    seedBank();
    connectImpl.mockResolvedValue({ ok: false, authRejected: false, error: 'no route', attempts: [] });
    const r = await openZone('home-server', { password: 'x' });
    expect(r).toEqual({ ok: false, reason: 'unreachable', error: 'no route' });
  });

  it('errors when the zone is not in the bank', async () => {
    const r = await openZone('ghost', { password: 'x' });
    expect(r).toMatchObject({ ok: false, reason: 'unreachable' });
  });

  it('forgetZonePassword clears the remembered password', async () => {
    mem.set(zonePasswordKey('home-server'), 'secret');
    await forgetZonePassword('home-server');
    expect(mem.has(zonePasswordKey('home-server'))).toBe(false);
  });
});
