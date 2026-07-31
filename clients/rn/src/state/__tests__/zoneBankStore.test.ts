/**
 * zoneBankStore tests.
 *
 * secureStorage pulls in react-native-mmkv / react-native, which don't load in
 * the node test env — mock it with an in-memory map so we test pure store logic.
 */
const mem = new Map<string, string>();
jest.mock('../secureStorage', () => ({
  secureStorage: {
    async getItem(k: string) { return mem.has(k) ? mem.get(k)! : null; },
    async setItem(k: string, v: string) { mem.set(k, v); },
    async removeItem(k: string) { mem.delete(k); },
  },
}));

import { useZoneBankStore, zonePasswordKey } from '../zoneBankStore';

const reset = () => {
  mem.clear();
  useZoneBankStore.setState({ relays: [], zones: [], loaded: false });
};

const RELAY_NODE = {
  wsUrl: 'wss://relay-node.local:4443',
  caFp: 'e6:fd:62',
  natsUser: 'relay_phone',
  natsPass: 'pw',
  label: 'relay-node',
};
const QF = {
  wsUrl: 'wss://relay.example.com:4443',
  caFp: 'aa:bb:cc',
  natsUser: 'relay_phone',
  natsPass: 'pw2',
};

describe('zoneBankStore', () => {
  beforeEach(reset);

  it('adds relays and dedupes by wsUrl (refresh in place, keep addedAt)', () => {
    const s = useZoneBankStore.getState();
    s.addRelay({ ...RELAY_NODE, addedAt: 100 });
    s.addRelay({ ...RELAY_NODE, natsPass: 'rotated', addedAt: 999 });
    const relays = useZoneBankStore.getState().relays;
    expect(relays).toHaveLength(1);
    expect(relays[0].natsPass).toBe('rotated');
    expect(relays[0].addedAt).toBe(100); // original addedAt preserved
  });

  it('addOrUpdateZone is last-write-wins by zoneId and unions relayUrls', () => {
    const s = useZoneBankStore.getState();
    s.addOrUpdateZone({ zoneId: 'home-server', displayName: 'home-server', relayUrls: [RELAY_NODE.wsUrl], username: 'operator', addedAt: 1 });
    s.addOrUpdateZone({ zoneId: 'home-server', displayName: 'Lain (home)', relayUrls: [QF.wsUrl], username: 'operator' });
    const zones = useZoneBankStore.getState().zones;
    expect(zones).toHaveLength(1);
    expect(zones[0].displayName).toBe('Lain (home)');
    expect(zones[0].relayUrls).toEqual([RELAY_NODE.wsUrl, QF.wsUrl]); // union, prev order first
    expect(zones[0].addedAt).toBe(1); // preserved
  });

  it('relaysForZone returns held relays in the entry preference order', () => {
    const s = useZoneBankStore.getState();
    s.addRelay(RELAY_NODE);
    s.addRelay(QF);
    // Entry prefers relay-b first.
    s.addOrUpdateZone({ zoneId: 'home-server', displayName: 'home-server', relayUrls: [QF.wsUrl, RELAY_NODE.wsUrl], username: 'm' });
    const ordered = useZoneBankStore.getState().relaysForZone('home-server');
    expect(ordered.map((r) => r.wsUrl)).toEqual([QF.wsUrl, RELAY_NODE.wsUrl]);
  });

  it('relaysForZone falls back to all held relays when the entry names an unpinned relay', () => {
    const s = useZoneBankStore.getState();
    s.addRelay(RELAY_NODE);
    // Synced-from-another-device entry naming a relay this device has not pinned.
    s.addOrUpdateZone({ zoneId: 'beta', displayName: 'beta', relayUrls: ['wss://unknown:4443'], username: 'm' });
    const fallback = useZoneBankStore.getState().relaysForZone('beta');
    expect(fallback.map((r) => r.wsUrl)).toEqual([RELAY_NODE.wsUrl]);
  });

  it('setHomeZone marks exactly one anchor', () => {
    const s = useZoneBankStore.getState();
    s.addOrUpdateZone({ zoneId: 'home-server', displayName: 'home-server', relayUrls: [], username: 'm' });
    s.addOrUpdateZone({ zoneId: 'beta', displayName: 'beta', relayUrls: [], username: 'm' });
    s.setHomeZone('home-server');
    expect(useZoneBankStore.getState().homeZone()?.zoneId).toBe('home-server');
    s.setHomeZone('beta');
    const homes = useZoneBankStore.getState().zones.filter((z) => z.homeZone);
    expect(homes.map((z) => z.zoneId)).toEqual(['beta']);
  });

  it('persists and reloads relays + zones', async () => {
    const s = useZoneBankStore.getState();
    s.addRelay(RELAY_NODE);
    s.addOrUpdateZone({ zoneId: 'home-server', displayName: 'home-server', relayUrls: [RELAY_NODE.wsUrl], username: 'operator' });
    // Simulate a fresh app start: blow away in-memory store state, reload from storage.
    useZoneBankStore.setState({ relays: [], zones: [], loaded: false });
    await useZoneBankStore.getState().loadFromStorage();
    const st = useZoneBankStore.getState();
    expect(st.loaded).toBe(true);
    expect(st.relays.map((r) => r.wsUrl)).toEqual([RELAY_NODE.wsUrl]);
    expect(st.getZone('home-server')?.username).toBe('operator');
  });

  it('zonePasswordKey is per-zone and never collides with the bank blob', () => {
    expect(zonePasswordKey('home-server')).toBe('@wyrd_zone_pw_home-server');
    expect(zonePasswordKey('home-server')).not.toBe('@wyrd_zone_bank');
  });
});
