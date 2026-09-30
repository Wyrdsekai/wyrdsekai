/**
 * No trust on first use ( W2): the phone never scans
 * the network for homes on plain http://<ip>:7070 (a 0.5.0 home answers that
 * on its own machine only). The homes it lists are the ones its invites named,
 * probed over HTTPS after pinning each to its household CA.
 */
const mem = new Map<string, string>();
jest.mock('../../../src/state/secureStorage', () => ({
  secureStorage: {
    async getItem(k: string) { return mem.has(k) ? mem.get(k)! : null; },
    async setItem(k: string, v: string) { mem.set(k, v); },
    async removeItem(k: string) { mem.delete(k); },
    async keys() { return [...mem.keys()]; },
  },
}));
const native = { pinCaFingerprint: jest.fn(async () => true) };
jest.mock('react-native', () => {
  const actual = jest.requireActual('../../../__mocks__/react-native');
  actual.Platform.OS = 'ios';
  actual.NativeModules.HouseholdTrust = native;
  return actual;
});

import { discoverInference, discoverWyrdsekaiServers } from '../../../src/engine/discovery/InferenceDiscovery';
import { useZoneBankStore } from '../../../src/state/zoneBankStore';

const CA = 'ab'.repeat(32);
const fetched: string[] = [];

beforeEach(() => {
  fetched.length = 0;
  native.pinCaFingerprint.mockClear();
  useZoneBankStore.setState({ relays: [], zones: [], trust: {}, loaded: true });
  (globalThis as { fetch: unknown }).fetch = jest.fn(async (url: string) => {
    fetched.push(url);
    return { ok: url.startsWith('https://198.51.100.20:27443'), json: async () => ({ name: 'home' }) };
  });
});

describe('finding a home', () => {
  it('scans nothing without an invite', async () => {
    expect(await discoverWyrdsekaiServers()).toEqual([]);
    expect(fetched).toEqual([]);
  });

  it("lists only invite-named homes, over https, pinned before the probe", async () => {
    useZoneBankStore.getState().setZoneTrust('z', {
      homeCaFp: CA, lanHttps: 'https://198.51.100.20:27443', homeBus: 'wss://198.51.100.20:27223',
    });
    useZoneBankStore.getState().setZoneTrust('no-ca', { lanHttps: 'https://198.51.100.30:7443' });
    const found = await discoverWyrdsekaiServers();
    expect(found.map((d) => d.url)).toEqual(['https://198.51.100.20:27443']);
    expect(fetched).toEqual(['https://198.51.100.20:27443/health']);
    expect(native.pinCaFingerprint).toHaveBeenCalledWith('198.51.100.20:27443', expect.stringMatching(/^AB(:AB){31}$/));
  });

  it('never probes an inference address on the home network over plain http', async () => {
    const found = await discoverInference({ savedUrl: 'http://198.51.100.20:11434' });
    expect(fetched.filter((u) => u.includes('198.51.100.20'))).toEqual([]);
    expect(found.some((d) => d.url.includes('198.51.100.20'))).toBe(false);
    // Only the device's own ports are tried besides.
    for (const u of fetched) expect(u).toMatch(/^http:\/\/localhost:(8080|11434)\//);
  });

  it("probes a saved https address, pinned first when an invite named it", async () => {
    useZoneBankStore.getState().setZoneTrust('z', { homeCaFp: CA, lanHttps: 'https://198.51.100.20:27443' });
    const found = await discoverInference({ savedUrl: 'https://198.51.100.20:27443' });
    expect(native.pinCaFingerprint).toHaveBeenCalledWith('198.51.100.20:27443', expect.any(String));
    expect(fetched[0]).toBe('https://198.51.100.20:27443');
    expect(found[0]).toMatchObject({ url: 'https://198.51.100.20:27443', label: 'Saved endpoint' });
  });
});
