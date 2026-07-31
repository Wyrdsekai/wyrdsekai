/**
 * addInviteToBank tests ( onboarding).
 * Mock the invite parser + secureStorage; use the real zoneBankStore.
 */
const mem = new Map<string, string>();
jest.mock('../../state/secureStorage', () => ({
  secureStorage: {
    async getItem(k: string) { return mem.has(k) ? mem.get(k)! : null; },
    async setItem(k: string, v: string) { mem.set(k, v); },
    async removeItem(k: string) { mem.delete(k); },
  },
}));

let parsed: unknown;
let isInvite = true;
jest.mock('../../network/phoneInvite', () => ({
  isPhoneInviteUrl: () => isInvite,
  parsePhoneInvite: () => parsed,
}));

import { addInviteToBank } from '../addInviteToBank';
import { useZoneBankStore } from '../../state/zoneBankStore';

const RELAY_NODE = { wsUrl: 'wss://relay-node:4443', natsUser: 'relay_phone', natsPassword: 'pw', caFp: 'aa:bb' };
const QF = { wsUrl: 'wss://relay-b:4443', natsUser: 'relay_phone', natsPassword: 'pw2' };

beforeEach(() => {
  mem.clear();
  useZoneBankStore.setState({ relays: [], zones: [], loaded: false });
  isInvite = true;
  parsed = { relays: [RELAY_NODE], zoneId: 'home-server', householdId: 'hh' };
});

describe('addInviteToBank', () => {
  it('adds the relay and a zone entry (username empty → will prompt)', () => {
    const r = addInviteToBank('wyrdphone://...');
    expect(r).toEqual({ zoneId: 'home-server', relayCount: 1, hasUsername: false });
    const bank = useZoneBankStore.getState();
    expect(bank.relays.map((x) => x.wsUrl)).toEqual([RELAY_NODE.wsUrl]);
    const zone = bank.getZone('home-server');
    expect(zone?.relayUrls).toEqual([RELAY_NODE.wsUrl]);
    expect(zone?.username).toBe('');
  });

  it('adds multiple relays as an ordered failover list', () => {
    parsed = { relays: [RELAY_NODE, QF], zoneId: 'home-server' };
    const r = addInviteToBank('wyrdphone://...');
    expect(r?.relayCount).toBe(2);
    expect(useZoneBankStore.getState().getZone('home-server')?.relayUrls).toEqual([RELAY_NODE.wsUrl, QF.wsUrl]);
  });

  it('keeps an existing username when re-scanning an invite for a banked zone', () => {
    useZoneBankStore.getState().addOrUpdateZone({
      zoneId: 'home-server', displayName: 'home-server', relayUrls: [RELAY_NODE.wsUrl], username: 'operator',
    });
    const r = addInviteToBank('wyrdphone://...');
    expect(r?.hasUsername).toBe(true);
    expect(useZoneBankStore.getState().getZone('home-server')?.username).toBe('operator');
  });

  it('returns null for a non-invite string', () => {
    isInvite = false;
    expect(addInviteToBank('http://nope')).toBeNull();
  });

  it('returns null when the invite carries no zone id', () => {
    parsed = { relays: [RELAY_NODE], zoneId: undefined };
    expect(addInviteToBank('wyrdphone://...')).toBeNull();
  });
});
