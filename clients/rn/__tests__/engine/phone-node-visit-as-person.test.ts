/**
 * A room visit on the home uses the signed-in person's session (the sealed
 * mcp.login), so the person arrives as themselves in the room the exit leads
 * to; the device token only when no one is signed in, or when the home refused
 * an expired session.
 */
const opened: string[] = [];
let refuseSessions = false;

jest.mock('../../src/engine/transit/WebSocketServerConnection', () => {
  class SessionRefusedError extends Error {
    constructor(readonly code: number | undefined, readonly reason: string) { super(`refused ${code}`); }
  }
  class WebSocketServerConnection {
    isConnected = false;
    private handlers: Array<(m: unknown) => void> = [];
    constructor(readonly url: string) {}
    async connect() {
      opened.push(this.url);
      if (refuseSessions && /[?&]token=/.test(this.url)) throw new SessionRefusedError(4001, 'Invalid or expired session token');
      this.isConnected = true;
    }
    send() {}
    onMessage(h: (m: unknown) => void) { this.handlers.push(h); return () => {}; }
    remoteRoomIds() { return new Set(); }
    disconnect() { this.isConnected = false; }
  }
  return { WebSocketServerConnection, SessionRefusedError };
});

import { PhoneNode, type PhoneNodeEvent } from '../../src/engine/PhoneNode';
import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';
import { InMemoryVitalityStore } from '../../src/engine/persistence/InMemoryVitalityStore';

const inference = { async complete() { return { content: 'ok', promptTokens: 1, completionTokens: 1 }; } };

async function nodeAtHome(): Promise<{ node: PhoneNode; events: PhoneNodeEvent[] }> {
  const node = new PhoneNode(new InMemoryEventJournal(), new InMemoryVitalityStore(), inference as never);
  await node.start();
  await node.go('player', 'You', 'north'); // Study → Home ("out" leads to the household)
  const events: PhoneNodeEvent[] = [];
  node.onEvent((e) => events.push(e));
  node.serverUrl = 'https://198.51.100.20:7443';
  node.deviceToken = 'wyrd_dev_abc';
  return { node, events };
}

beforeEach(() => {
  opened.length = 0;
  refuseSessions = false;
});

it('visits as the signed-in person, into the room the exit leads to', async () => {
  const { node, events } = await nodeAtHome();
  node.sessionToken = 'sess-1';
  await node.go('player', 'You', 'out');
  expect(opened).toEqual(['wss://198.51.100.20:7443/ws?token=sess-1&room=nexus']);
  expect(events.some((e) => e.type === 'server_room_entered')).toBe(true);
  node.stop();
});

it('uses the device token only when no one is signed in', async () => {
  const { node } = await nodeAtHome();
  await node.go('player', 'You', 'out');
  expect(opened).toEqual(['wss://198.51.100.20:7443/ws?device_token=wyrd_dev_abc']);
  node.stop();
});

it('an expired session is dropped and the device token used instead', async () => {
  const { node, events } = await nodeAtHome();
  node.sessionToken = 'expired';
  refuseSessions = true;
  await node.go('player', 'You', 'out');
  expect(opened).toEqual([
    'wss://198.51.100.20:7443/ws?token=expired&room=nexus',
    'wss://198.51.100.20:7443/ws?device_token=wyrd_dev_abc',
  ]);
  expect(node.sessionToken).toBeNull();
  expect(events.some((e) => e.type === 'server_room_entered')).toBe(true);
  node.stop();
});

it('a signed-in person without a device token can still visit', async () => {
  const { node } = await nodeAtHome();
  node.deviceToken = null;
  node.sessionToken = 'sess-1';
  await node.go('player', 'You', 'out');
  expect(opened).toEqual(['wss://198.51.100.20:7443/ws?token=sess-1&room=nexus']);
  node.stop();
});
