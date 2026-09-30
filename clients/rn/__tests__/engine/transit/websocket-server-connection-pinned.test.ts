/**
 * iOS: visiting a room on the home over wss:// goes through the app's native
 * pinned socket in text mode (RN's WebSocket cannot be pinned on iOS), after
 * pinning the home's address to its household CA from the invite.
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

type Ev = { id: string; type: string; data?: string };
const listeners = new Set<(e: Ev) => void>();
const b64 = (o: unknown) => Buffer.from(JSON.stringify(o)).toString('base64');
const nativeSocket = {
  // The home opens the session and sends the room straight away.
  connect: jest.fn((id: string) => {
    setTimeout(() => {
      listeners.forEach((l) => l({ id, type: 'open' }));
      listeners.forEach((l) => l({ id, type: 'message', data: b64({ type: 'room_state', seq: 0, first: true }) }));
    }, 0);
  }),
  send: jest.fn(),
  sendText: jest.fn((id: string, text: string) => {
    const reply = JSON.stringify({ type: 'room_state', seq: 1, echo: JSON.parse(text).type });
    setTimeout(() => listeners.forEach((l) => l({ id, type: 'message', data: Buffer.from(reply).toString('base64') })), 0);
  }),
  close: jest.fn(),
};
const trust = { pinCaFingerprint: jest.fn(async () => true) };
jest.mock('react-native', () => {
  const actual = jest.requireActual('../../../__mocks__/react-native');
  actual.Platform.OS = 'ios';
  actual.NativeModules.WyrdRelaySocket = nativeSocket;
  actual.NativeModules.HouseholdTrust = trust;
  actual.NativeEventEmitter = class {
    addListener(_e: string, l: (e: Ev) => void) { listeners.add(l); return { remove: () => listeners.delete(l) }; }
  };
  return actual;
});

import { WebSocketServerConnection } from '../../../src/engine/transit/WebSocketServerConnection';
import { useZoneBankStore } from '../../../src/state/zoneBankStore';

const CA = 'cd'.repeat(32);

it('uses the pinned native socket in text mode for a wss home, pinned to its CA first', async () => {
  useZoneBankStore.setState({ relays: [], zones: [], trust: {}, loaded: true });
  useZoneBankStore.getState().setZoneTrust('z', { homeCaFp: CA, lanHttps: 'https://198.51.100.20:7443' });
  (globalThis as { WebSocket?: unknown }).WebSocket = jest.fn(() => { throw new Error('RN WebSocket must not be used'); });

  const conn = new WebSocketServerConnection('wss://198.51.100.20:7443/ws?device_token=wyrd_dev_x');
  const got: unknown[] = [];
  conn.onMessage((m) => got.push(m));
  await conn.connect();
  expect(trust.pinCaFingerprint).toHaveBeenCalledWith('198.51.100.20:7443', expect.stringMatching(/^CD(:CD){31}$/));
  expect(nativeSocket.connect).toHaveBeenCalledWith(expect.any(String), 'wss://198.51.100.20:7443/ws?device_token=wyrd_dev_x');

  await conn.send({ type: 'look', id: 'l1', roomId: '' });
  await new Promise((r) => setTimeout(r, 5));
  expect(nativeSocket.sendText).toHaveBeenCalled();
  expect(nativeSocket.send).not.toHaveBeenCalled();
  // The first frame (sent before anyone listened) is kept for the first listener.
  expect(got).toEqual([{ type: 'room_state', seq: 0, first: true }, { type: 'room_state', seq: 1, echo: 'look' }]);
  conn.disconnect();
});

it('a session the home closes before any frame is refused, with its code', async () => {
  const { SessionRefusedError } = await import('../../../src/engine/transit/WebSocketServerConnection');
  nativeSocket.connect.mockImplementationOnce((id: string) => {
    setTimeout(() => {
      listeners.forEach((l) => l({ id, type: 'open' }));
      listeners.forEach((l) => l({ id, type: 'closing', code: 4001, reason: 'Invalid or expired session token' } as never));
      listeners.forEach((l) => l({ id, type: 'closed' }));
    }, 0);
  });
  const conn = new WebSocketServerConnection('wss://198.51.100.20:7443/ws?token=old');
  const err = await conn.connect().catch((e) => e);
  expect(err).toBeInstanceOf(SessionRefusedError);
  expect(err.code).toBe(4001);
  expect(conn.isConnected).toBe(false);
});
