/**
 * A pairing reply never sets the relay ( W2/W3): the
 * relay address and its credentials come only from an invite. What a pairing
 * reply gives is kept: the device token, the home bus address (`natsUrl`, the
 * bus websocket as a phone reaches it) and the phone's own bus login.
 */
const mem = new Map<string, string>();
jest.mock('../../src/state/secureStorage', () => ({
  secureStorage: {
    async getItem(k: string) { return mem.has(k) ? mem.get(k)! : null; },
    async setItem(k: string, v: string) {
      if (typeof v !== 'string') throw new Error(`not a string for ${k}`);
      mem.set(k, v);
    },
    async removeItem(k: string) { mem.delete(k); },
  },
}));

import { useAppModeStore } from '../../src/state/appModeStore';
import { HOME_BUS_URL_KEY, HOME_NATS_PASS_KEY, HOME_NATS_USER_KEY } from '../../src/server/homeBus';

const INVITE_RELAY = 'wss://192.0.2.105:24443';

const reply = {
  token: 'wyrd_dev_x',
  householdId: 'rehearsal',
  householdName: 'Rehearsal',
  serverDid: 'did:key:z6Mk',
  natsUrl: 'wss://192.0.2.105:27223',
  serverUrl: 'https://192.0.2.105:27443',
  nats_user: 'phone-fa23',
  nats_pass: 'np',
};

beforeEach(() => {
  mem.clear();
  mem.set('@wyrd_relay_url', INVITE_RELAY);
  mem.set('@wyrd_relay_token', 'from-before');
  useAppModeStore.setState({ relayUrl: INVITE_RELAY, relayToken: null, natsUrl: null });
});

describe('setPairingCredentials', () => {
  it("keeps the invite's relay and saves the home bus from the reply", async () => {
    await useAppModeStore.getState().setPairingCredentials(reply);
    expect(mem.get('@wyrd_relay_url')).toBe(INVITE_RELAY);
    expect(mem.get('@wyrd_relay_token')).toBe('from-before');
    expect(useAppModeStore.getState().relayUrl).toBe(INVITE_RELAY);
    expect(mem.get(HOME_BUS_URL_KEY)).toBe('wss://192.0.2.105:27223');
    expect(mem.get(HOME_NATS_USER_KEY)).toBe('phone-fa23');
    expect(mem.get(HOME_NATS_PASS_KEY)).toBe('np');
    expect(mem.get('@wyrd_pairing_token')).toBe('wyrd_dev_x');
    expect(mem.get('@wyrd_inference_url')).toBe('https://192.0.2.105:27443');
  });

  it("ignores relay fields an older home still sends (the zone's own relay leg)", async () => {
    await useAppModeStore.getState().setPairingCredentials({
      ...reply, relayUrl: 'nats://192.0.2.105:24222', relayToken: 'leg-token',
    } as typeof reply);
    expect(mem.get('@wyrd_relay_url')).toBe(INVITE_RELAY);
    expect(mem.get('@wyrd_relay_token')).toBe('from-before');
    expect(useAppModeStore.getState().relayUrl).toBe(INVITE_RELAY);
  });

  it('keeps no bus address that is not encrypted, and stores nothing for a missing one', async () => {
    await useAppModeStore.getState().setPairingCredentials({ ...reply, natsUrl: 'nats://127.0.0.1:27222' });
    expect(mem.has(HOME_BUS_URL_KEY)).toBe(false);
    mem.clear();
    await useAppModeStore.getState().setPairingCredentials({ ...reply, natsUrl: null });
    expect(mem.has(HOME_BUS_URL_KEY)).toBe(false);
    expect(mem.has('@wyrd_nats_url')).toBe(false);
  });
});
