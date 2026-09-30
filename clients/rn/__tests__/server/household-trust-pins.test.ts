/**
 * D6: pins come only from invites, live in secure
 * storage, and fill the native (memory-only) pin store at every start. A pin
 * mismatch is a refusal with a plain message — no "trust the new certificate".
 */
const mem = new Map<string, string>();
jest.mock('../../src/state/secureStorage', () => ({
  secureStorage: {
    async getItem(k: string) { return mem.has(k) ? mem.get(k)! : null; },
    async setItem(k: string, v: string) { mem.set(k, v); },
    async removeItem(k: string) { mem.delete(k); },
    async keys() { return [...mem.keys()]; },
  },
}));

const native = {
  addTrustedCert: jest.fn(async () => true),
  removeTrustedCert: jest.fn(async () => true),
  listTrustedHosts: jest.fn(async () => []),
  fetchServerCertificates: jest.fn(async () => []),
  pinFingerprint: jest.fn(async () => true),
  pinCaFingerprint: jest.fn(async () => true),
};

jest.mock('react-native', () => {
  const actual = jest.requireActual('../../__mocks__/react-native');
  actual.Platform.OS = 'ios';
  actual.NativeModules.HouseholdTrust = native;
  return actual;
});

import { Alert, DeviceEventEmitter } from 'react-native';
import {
  installPinMismatchListener,
  pinHomeCa,
  pinKey,
  pinKeyForUrl,
  pinKnownHome,
  restoreNativePins,
  toColonHex,
} from '../../src/server/HouseholdTrust';
import { useZoneBankStore } from '../../src/state/zoneBankStore';

const CA = 'ab'.repeat(32);

beforeEach(() => {
  mem.clear();
  Object.values(native).forEach((f) => f.mockClear());
  useZoneBankStore.setState({ relays: [], zones: [], trust: {}, loaded: true });
});

describe('pins', () => {
  it("pins the home's network address (host:port) to its household CA", async () => {
    expect(await pinHomeCa('https://198.51.100.20:7443', CA)).toBe(true);
    expect(native.pinCaFingerprint).toHaveBeenCalledWith('198.51.100.20:7443', toColonHex(CA));
    expect(toColonHex(CA)).toMatch(/^AB(:AB){31}$/);
  });

  it('builds the same key as the native store: lowercase host, IPv6 without brackets, default ports', () => {
    expect(pinKey('Relay.Example', 4443)).toBe('relay.example:4443');
    expect(pinKeyForUrl('wss://[FE80::1]:4223')).toBe('fe80::1:4223');
    expect(pinKeyForUrl('https://home.example')).toBe('home.example:443');
    expect(pinKeyForUrl('wss://home.example/x')).toBe('home.example:443');
    expect(pinKeyForUrl('not a url')).toBeNull();
  });

  it('pins a known home before connecting to its address, and only a known one', async () => {
    useZoneBankStore.getState().setZoneTrust('z', {
      homeCaFp: CA, lanHttps: 'https://198.51.100.20:7443', homeBus: 'wss://198.51.100.20:27223',
    });
    await pinKnownHome('https://198.51.100.20:7443');
    await pinKnownHome('wss://198.51.100.20:7443/ws?token=t');
    await pinKnownHome('wss://198.51.100.20:27223');
    expect(native.pinCaFingerprint.mock.calls).toEqual([
      ['198.51.100.20:7443', toColonHex(CA)],
      ['198.51.100.20:7443', toColonHex(CA)],
      ['198.51.100.20:27223', toColonHex(CA)],
    ]);
    await pinKnownHome('https://198.51.100.99:7443');
    // Same machine, another port (a relay there): not the home, not pinned to its CA.
    await pinKnownHome('wss://198.51.100.20:4443');
    expect(native.pinCaFingerprint).toHaveBeenCalledTimes(3);
  });

  it('refills the native store at start from secure storage and the bank', async () => {
    mem.set('@wyrd_trust_relay.example:4443', JSON.stringify({ host: 'relay.example:4443', certPem: 'PEM', fingerprint: 'F', trustedAt: 1, source: 'tofu' }));
    mem.set('@wyrd_mcp_session_token', 'not a pin');
    const bank = useZoneBankStore.getState();
    bank.addRelay({ wsUrl: 'wss://relay.example:4443', natsUser: 'u', natsPass: 'p', fp: '63:24', caFp: 'E5:F0' });
    bank.setZoneTrust('z', { zk: 'k', homeCaFp: CA, lanHttps: 'https://198.51.100.20:7443', homeBus: 'wss://198.51.100.20:4223' });
    bank.setZoneTrust('old', { zk: 'k' });

    await restoreNativePins();

    expect(native.addTrustedCert).toHaveBeenCalledWith('relay.example:4443', 'PEM');
    expect(native.addTrustedCert).toHaveBeenCalledTimes(1);
    expect(native.pinFingerprint).toHaveBeenCalledWith('relay.example:4443', 'E5:F0');
    expect(native.pinFingerprint).toHaveBeenCalledWith('relay.example:4443', '63:24');
    expect(native.pinCaFingerprint).toHaveBeenCalledWith('198.51.100.20:7443', toColonHex(CA));
    expect(native.pinCaFingerprint).toHaveBeenCalledWith('198.51.100.20:4223', toColonHex(CA));
    expect(native.pinCaFingerprint).toHaveBeenCalledTimes(2);
  });

  it('keeps a relay and a home on the same machine apart', async () => {
    const bank = useZoneBankStore.getState();
    bank.addRelay({ wsUrl: 'wss://198.51.100.20:24443', natsUser: 'u', natsPass: 'p', fp: 'AA:01', caFp: 'BB:02' });
    bank.setZoneTrust('z', { homeCaFp: CA, lanHttps: 'https://198.51.100.20:27443', homeBus: 'wss://198.51.100.20:27223' });

    await restoreNativePins();

    const leafKeys = new Set(native.pinFingerprint.mock.calls.map((c) => (c as unknown[])[0]));
    const caKeys = new Set(native.pinCaFingerprint.mock.calls.map((c) => (c as unknown[])[0]));
    expect([...leafKeys]).toEqual(['198.51.100.20:24443']);
    expect([...caKeys].sort()).toEqual(['198.51.100.20:27223', '198.51.100.20:27443']);
  });
});

describe('pin mismatch', () => {
  it('is a refusal with a plain message: one OK button, the pin is kept', () => {
    const alert = jest.spyOn(Alert, 'alert');
    const detach = installPinMismatchListener();
    const event = { host: 'relay.example', newFingerprint: 'NEW', pinnedFingerprint: 'OLD' };
    (DeviceEventEmitter as unknown as { emit: (e: string, p: unknown) => void }).emit('wyrd_trust_pin_mismatch', event);
    (DeviceEventEmitter as unknown as { emit: (e: string, p: unknown) => void }).emit('wyrd_trust_pin_mismatch', event);

    expect(alert).toHaveBeenCalledTimes(1);
    const [title, body, buttons] = alert.mock.calls[0];
    expect(title).toBe('Connection refused');
    expect(body).toMatch(/relay\.example/);
    expect(body).toMatch(/wyrd phone invite/);
    expect(buttons).toEqual([{ text: 'OK' }]);
    expect(native.removeTrustedCert).not.toHaveBeenCalled();
    detach();
  });
});
