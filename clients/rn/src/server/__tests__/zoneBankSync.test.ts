/**
 * zoneBankSync tests ( cross-device sync).
 * Mock secureStorage; drive the real zoneBankStore; stub the sync client.
 */
const mem = new Map<string, string>();
jest.mock('../../state/secureStorage', () => ({
  secureStorage: {
    async getItem(k: string) { return mem.has(k) ? mem.get(k)! : null; },
    async setItem(k: string, v: string) { mem.set(k, v); },
    async removeItem(k: string) { mem.delete(k); },
  },
}));

import {
  mergeRemoteZones,
  serializeBank,
  syncZoneBank,
  type ZoneBankSyncClient,
} from '../zoneBankSync';
import { useZoneBankStore, type ZoneBankEntry } from '../../state/zoneBankStore';
import type { NatsResult } from '../NatsServerClient';

const HOME_SERVER: ZoneBankEntry = {
  zoneId: 'home-server', displayName: 'home-server', relayUrls: ['wss://relay-node:4443'],
  username: 'operator', addedAt: 1000, lastUsedAt: 1000,
};
const QF: ZoneBankEntry = {
  zoneId: 'relay-b', displayName: 'example-relay', relayUrls: ['wss://qf:4443'],
  username: 'operator', addedAt: 2000,
};

beforeEach(() => {
  mem.clear();
  useZoneBankStore.setState({ relays: [], zones: [], loaded: true });
});

/** A stub client backed by an in-memory server blob. */
function makeClient(initialBlob: string | null, updatedAt = 0): {
  client: ZoneBankSyncClient;
  stored: () => { bank: string | null; updatedAt: number };
} {
  let blob = initialBlob;
  let stamp = updatedAt;
  const client: ZoneBankSyncClient = {
    async getZoneBank(): Promise<NatsResult<{ bank: string | null; updatedAt: number }>> {
      return { ok: true, data: { bank: blob, updatedAt: stamp } };
    },
    async putZoneBank(bankJson, ts): Promise<NatsResult<{ updatedAt: number }>> {
      blob = bankJson;
      stamp = ts;
      return { ok: true, data: { updatedAt: ts } };
    },
  };
  return { client, stored: () => ({ bank: blob, updatedAt: stamp }) };
}

describe('mergeRemoteZones', () => {
  it('adds remote-only zones', () => {
    useZoneBankStore.setState({ relays: [], zones: [HOME_SERVER], loaded: true });
    const changed = mergeRemoteZones([QF]);
    expect(changed).toBe(1);
    expect(useZoneBankStore.getState().zones.map((z) => z.zoneId).sort()).toEqual(['home-server', 'relay-b']);
  });

  it('keeps the locally-newer entry (LWW)', () => {
    const localNewer = { ...HOME_SERVER, username: 'local-name', lastUsedAt: 5000 };
    useZoneBankStore.setState({ relays: [], zones: [localNewer], loaded: true });
    const remoteOlder = { ...HOME_SERVER, username: 'remote-name', lastUsedAt: 1000 };
    const changed = mergeRemoteZones([remoteOlder]);
    expect(changed).toBe(0);
    expect(useZoneBankStore.getState().getZone('home-server')?.username).toBe('local-name');
  });

  it('takes the remotely-newer entry (LWW)', () => {
    const localOlder = { ...HOME_SERVER, username: 'local-name', lastUsedAt: 1000 };
    useZoneBankStore.setState({ relays: [], zones: [localOlder], loaded: true });
    const remoteNewer = { ...HOME_SERVER, username: 'remote-name', lastUsedAt: 9000 };
    const changed = mergeRemoteZones([remoteNewer]);
    expect(changed).toBe(1);
    expect(useZoneBankStore.getState().getZone('home-server')?.username).toBe('remote-name');
  });

  it('ignores malformed entries', () => {
    const changed = mergeRemoteZones([{ zoneId: '' } as ZoneBankEntry, undefined as unknown as ZoneBankEntry]);
    expect(changed).toBe(0);
    expect(useZoneBankStore.getState().zones).toHaveLength(0);
  });
});

describe('syncZoneBank', () => {
  it('pulls remote zones, merges, and pushes the union', async () => {
    useZoneBankStore.setState({ relays: [], zones: [HOME_SERVER], loaded: true });
    const { client, stored } = makeClient(JSON.stringify([QF]));
    const res = await syncZoneBank(client, 7777);
    expect(res).toEqual({ ok: true, pulled: 1, pushed: true });
    // Local now has both.
    expect(useZoneBankStore.getState().zones.map((z) => z.zoneId).sort()).toEqual(['home-server', 'relay-b']);
    // Server received the merged superset with our stamp.
    const up = stored();
    expect(up.updatedAt).toBe(7777);
    const pushed = JSON.parse(up.bank!) as ZoneBankEntry[];
    expect(pushed.map((z) => z.zoneId).sort()).toEqual(['home-server', 'relay-b']);
  });

  it('pushes local zones when the server is empty', async () => {
    useZoneBankStore.setState({ relays: [], zones: [HOME_SERVER], loaded: true });
    const { client, stored } = makeClient(null);
    const res = await syncZoneBank(client, 100);
    expect(res.ok).toBe(true);
    expect(res.pulled).toBe(0);
    expect(res.pushed).toBe(true);
    expect(JSON.parse(stored().bank!)).toHaveLength(1);
  });

  it('does not push when both local and server are empty', async () => {
    const { client, stored } = makeClient(null);
    const res = await syncZoneBank(client, 100);
    expect(res).toEqual({ ok: true, pulled: 0, pushed: false });
    expect(stored().bank).toBeNull();
  });

  it('reports a get failure without throwing', async () => {
    const client: ZoneBankSyncClient = {
      async getZoneBank() { return { ok: false, error: 'boom' }; },
      async putZoneBank() { return { ok: false, error: 'unused' }; },
    };
    const res = await syncZoneBank(client, 1);
    expect(res.ok).toBe(false);
    expect(res.error).toBe('boom');
  });

  it('serializeBank excludes nothing but zones (no secrets present anyway)', () => {
    useZoneBankStore.setState({ relays: [], zones: [HOME_SERVER, QF], loaded: true });
    const blob = JSON.parse(serializeBank()) as ZoneBankEntry[];
    expect(blob).toHaveLength(2);
    // Sanity: no password field exists on the entry shape.
    expect(Object.keys(blob[0])).not.toContain('password');
  });
});
