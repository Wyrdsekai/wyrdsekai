/**
 * discoverZones tests ( "Find a zone").
 * Mock secureStorage; drive the real zoneBankStore; stub the directory client.
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
  discoverZones,
  normalizeDiscovered,
  type DirectorySearchClient,
} from '../discoverZones';
import { useZoneBankStore } from '../../state/zoneBankStore';
import type { NatsResult } from '../NatsServerClient';

const HOME_SERVER_MANIFEST = {
  did: 'did:key:home-server', zoneLabel: 'home-server', displayName: 'home-server',
  tagline: 'a quiet study', tags: ['personal', 'household'],
};
const QF_MANIFEST = { did: 'did:key:qf', zoneLabel: 'relay-b', tags: [] };

beforeEach(() => {
  mem.clear();
  useZoneBankStore.setState({ relays: [], zones: [], loaded: true });
});

function client(result: NatsResult<Array<Record<string, unknown>>>): DirectorySearchClient {
  return { async searchDirectory() { return result; } };
}

describe('normalizeDiscovered', () => {
  it('maps manifests and carries tags + tagline', () => {
    const zones = normalizeDiscovered([HOME_SERVER_MANIFEST]);
    expect(zones).toHaveLength(1);
    expect(zones[0]).toMatchObject({
      zoneLabel: 'home-server', did: 'did:key:home-server', displayName: 'home-server',
      tagline: 'a quiet study', tags: ['personal', 'household'], inBank: false,
    });
  });

  it('flags zones already in the bank', () => {
    useZoneBankStore.getState().addOrUpdateZone({
      zoneId: 'home-server', displayName: 'home-server', relayUrls: [], username: 'operator',
    });
    const zones = normalizeDiscovered([HOME_SERVER_MANIFEST, QF_MANIFEST]);
    expect(zones.find((z) => z.zoneLabel === 'home-server')?.inBank).toBe(true);
    expect(zones.find((z) => z.zoneLabel === 'relay-b')?.inBank).toBe(false);
  });

  it('drops entries with no zone label', () => {
    const zones = normalizeDiscovered([{ did: 'did:key:x' }, HOME_SERVER_MANIFEST]);
    expect(zones.map((z) => z.zoneLabel)).toEqual(['home-server']);
  });
});

describe('discoverZones', () => {
  it('returns normalised results on success', async () => {
    const r = await discoverZones(client({ ok: true, data: [HOME_SERVER_MANIFEST, QF_MANIFEST] }));
    expect(r.error).toBeUndefined();
    expect(r.zones.map((z) => z.zoneLabel).sort()).toEqual(['home-server', 'relay-b']);
  });

  it('returns an empty list + error on transport failure, never throws', async () => {
    const r = await discoverZones(client({ ok: false, error: 'no responders' }));
    expect(r.zones).toEqual([]);
    expect(r.error).toBe('no responders');
  });
});
