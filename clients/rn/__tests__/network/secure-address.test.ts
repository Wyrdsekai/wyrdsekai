/**
 * W2: the phone never sends to another machine in
 * the clear. A typed http:// home address is refused unless an invite gave the
 * same host's https address (pinned to its household CA). And the home bus is
 * used only with the CA pin, the address and the phone's own credentials.
 */
const mem = new Map<string, string>();
jest.mock('../../src/state/secureStorage', () => ({
  secureStorage: {
    async getItem(k: string) { return mem.has(k) ? mem.get(k)! : null; },
    async setItem(k: string, v: string) { mem.set(k, v); },
    async removeItem(k: string) { mem.delete(k); },
  },
}));

import { endpointOf, isPlaintextToNetwork, sameEndpoint, secureHomeAddress } from '../../src/network/secureAddress';
import {
  HOME_BUS_URL_KEY,
  HOME_NATS_PASS_KEY,
  HOME_NATS_USER_KEY,
  ensureHomeBusCredentials,
  planBetweenLeg,
  resolveHomeBus,
  saveHomeBusCredentials,
  saveHomeBusUrl,
} from '../../src/server/homeBus';
import { useZoneBankStore } from '../../src/state/zoneBankStore';

const CA = 'ab'.repeat(32);

beforeEach(() => {
  mem.clear();
  useZoneBankStore.setState({ relays: [], zones: [], trust: {}, loaded: true });
});

describe('addresses', () => {
  it('knows plaintext to the network from encrypted or on-device', () => {
    expect(isPlaintextToNetwork('http://198.51.100.20:7070')).toBe(true);
    expect(isPlaintextToNetwork('ws://198.51.100.20:4223')).toBe(true);
    expect(isPlaintextToNetwork('198.51.100.20:7070')).toBe(true);
    expect(isPlaintextToNetwork('https://198.51.100.20:7443')).toBe(false);
    expect(isPlaintextToNetwork('wss://relay.example:4443')).toBe(false);
    expect(isPlaintextToNetwork('http://localhost:8080')).toBe(false);
    expect(isPlaintextToNetwork('http://127.0.0.1:11434')).toBe(false);
  });

  it('refuses a typed plain home address with the plain reason', () => {
    const r = secureHomeAddress('http://198.51.100.20:7070');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error).toMatch(/not encrypted/);
    expect(secureHomeAddress('198.51.100.20:7070').ok).toBe(false);
  });

  it("uses the invite's https address for the same host instead", () => {
    useZoneBankStore.getState().setZoneTrust('z', { homeCaFp: CA, lanHttps: 'https://198.51.100.20:7443' });
    expect(secureHomeAddress('http://198.51.100.20:7070')).toEqual({ ok: true, url: 'https://198.51.100.20:7443' });
    expect(secureHomeAddress('198.51.100.99:7070').ok).toBe(false);
  });

  it('passes an encrypted address as it is', () => {
    expect(secureHomeAddress('https://home.example:7443/')).toEqual({ ok: true, url: 'https://home.example:7443' });
  });
});

describe('endpoints', () => {
  it('reads host and port from any scheme, with default ports, lowercase, IPv6 without brackets', () => {
    expect(endpointOf('wss://192.0.2.105:27223')).toEqual({ host: '192.0.2.105', port: 27223 });
    expect(endpointOf('https://Home.Example/x?y')).toEqual({ host: 'home.example', port: 443 });
    expect(endpointOf('wss://[fe80::1]:4223')).toEqual({ host: 'fe80::1', port: 4223 });
    expect(endpointOf('ws://h')).toEqual({ host: 'h', port: 80 });
    expect(endpointOf('198.51.100.20:7443')).toBeNull();
    expect(sameEndpoint('https://h:7443', 'wss://h:7443/ws?token=t')).toBe(true);
    expect(sameEndpoint('https://h:7443', 'wss://h:4443')).toBe(false);
  });
});

describe('home bus', () => {
  it("uses the invite's home_bus, else the pairing reply's natsUrl, and 4223 only when neither exists", async () => {
    const bank = useZoneBankStore.getState();
    await saveHomeBusCredentials('phone-7', 'np');
    bank.setZoneTrust('z', { homeCaFp: CA, lanHttps: 'https://198.51.100.20:27443' });
    expect((await resolveHomeBus('z'))?.url).toBe('wss://198.51.100.20:4223');
    await saveHomeBusUrl('wss://198.51.100.20:27223');
    expect((await resolveHomeBus('z'))?.url).toBe('wss://198.51.100.20:27223');
    bank.setZoneTrust('z', { homeBus: 'wss://home.lan:5223' });
    expect(await resolveHomeBus('z')).toEqual({
      url: 'wss://home.lan:5223', host: 'home.lan', homeCaFp: CA, user: 'phone-7', pass: 'np',
    });
    // The invite's home_bus alone is enough (no lan_https needed).
    bank.setZoneTrust('y', { homeCaFp: CA, homeBus: 'wss://192.0.2.5:4323' });
    expect((await resolveHomeBus('y'))?.url).toBe('wss://192.0.2.5:4323');
  });

  it('keeps only an encrypted bus address from a pairing reply', async () => {
    await saveHomeBusUrl('nats://127.0.0.1:27222');
    await saveHomeBusUrl('ws://198.51.100.20:4223');
    await saveHomeBusUrl(null);
    expect(mem.has(HOME_BUS_URL_KEY)).toBe(false);
    await saveHomeBusUrl('wss://198.51.100.20:27223/');
    expect(mem.get(HOME_BUS_URL_KEY)).toBe('wss://198.51.100.20:27223');
  });

  it("keeps the bus address pair.device returns with the credentials", async () => {
    useZoneBankStore.getState().setZoneTrust('z', { homeCaFp: CA, homeBus: 'wss://198.51.100.20:27223' });
    const pairDevice = jest.fn(async () => ({ natsUser: 'phone-7', natsPass: 'np', natsUrl: 'wss://198.51.100.20:27223' }));
    await ensureHomeBusCredentials({ pairDevice }, 'z');
    expect(pairDevice).toHaveBeenCalledTimes(1);
    expect(mem.get(HOME_BUS_URL_KEY)).toBe('wss://198.51.100.20:27223');
  });

  it('needs the CA pin, the home-network address and its own credentials', async () => {
    const bank = useZoneBankStore.getState();
    bank.setZoneTrust('z', { zk: 'k', lanHttps: 'https://198.51.100.20:7443' });
    await saveHomeBusCredentials('phone-7', 'np');
    expect(await resolveHomeBus('z')).toBeNull(); // no CA fingerprint: the relay
    bank.setZoneTrust('z', { homeCaFp: CA });
    expect(await resolveHomeBus('z')).toEqual({
      url: 'wss://198.51.100.20:4223', host: '198.51.100.20', homeCaFp: CA, user: 'phone-7', pass: 'np',
    });
    mem.delete(HOME_NATS_PASS_KEY);
    expect(await resolveHomeBus('z')).toBeNull(); // no credentials: the relay
    expect(await resolveHomeBus(null)).toBeNull();
  });

  it('asks the home for credentials once, only when it can use them', async () => {
    const pairDevice = jest.fn(async () => ({ natsUser: 'phone-7', natsPass: 'np' }));
    await ensureHomeBusCredentials({ pairDevice }, 'z');
    expect(pairDevice).not.toHaveBeenCalled(); // no home-network trust yet

    useZoneBankStore.getState().setZoneTrust('z', { homeCaFp: CA, lanHttps: 'https://198.51.100.20:7443' });
    await ensureHomeBusCredentials({ pairDevice }, 'z');
    expect(pairDevice).toHaveBeenCalledTimes(1);
    expect(pairDevice.mock.calls[0]).toEqual([expect.stringMatching(/^phone-[0-9a-f]{6}$/)]);
    expect(mem.get(HOME_NATS_USER_KEY)).toBe('phone-7');
    expect(mem.get(HOME_NATS_PASS_KEY)).toBe('np');

    await ensureHomeBusCredentials({ pairDevice }, 'z');
    expect(pairDevice).toHaveBeenCalledTimes(1);
  });

  it('opens Between only on the pinned home bus: never the relay, never a saved ws:// address', async () => {
    const bank = useZoneBankStore.getState();
    // Paired before 0.5.0 through a relay, invite had a key but no CA: relay only, told once.
    bank.setZoneTrust('z', { zk: 'k' });
    expect(await planBetweenLeg('z', null, true)).toEqual({ bus: null, notice: 'lanPairAgain' });
    // An old plain LAN address is never used.
    expect(await planBetweenLeg('z', 'ws://198.51.100.20:4223', true)).toEqual({ bus: null, notice: 'lanPairAgain' });
    // LAN-only pairing from before 0.5.0: nothing works until paired again.
    expect(await planBetweenLeg(null, 'ws://198.51.100.20:4223', false)).toEqual({ bus: null, notice: 'pairAgain' });
    // No key at all: the relay login says to pair again; nothing to add here.
    expect(await planBetweenLeg('none', null, true)).toEqual({ bus: null, notice: null });
    // Everything in place: the home bus, no notice.
    bank.setZoneTrust('z', { homeCaFp: CA, lanHttps: 'https://198.51.100.20:7443' });
    await saveHomeBusCredentials('phone-7', 'np');
    const plan = await planBetweenLeg('z', 'ws://198.51.100.20:4223', true);
    expect(plan.notice).toBeNull();
    expect(plan.bus?.url).toBe('wss://198.51.100.20:4223');
  });
});
