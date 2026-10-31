/**
 * The app names itself on every connect, so a connection that drops comes back to the same
 * session on the home: no one in the room sees it leave or arrive, and the replay asks that same
 * session for what was said meanwhile. A close that says a newer connection took the session up
 * (4005) is not reconnected.
 */
jest.mock('../../src/engine/between/RelaySocket', () => ({
  createRelaySocket: () => { throw new Error('not used'); },
  nativeRelaySocketAvailable: () => false,
}));
jest.mock('../../src/server/HouseholdTrust', () => ({ knownHomeCaFor: () => null }));

import { WyrdWebSocket } from '../../src/network/websocket';

class FakeSocket {
  static opened: FakeSocket[] = [];
  readyState = 0;
  sent: string[] = [];
  onopen: ((ev?: unknown) => void) | null = null;
  onmessage: ((ev: { data: unknown }) => void) | null = null;
  onclose: ((ev?: unknown) => void) | null = null;
  onerror: ((ev?: unknown) => void) | null = null;
  constructor(readonly url: string) { FakeSocket.opened.push(this); }
  send(data: string) { this.sent.push(data); }
  close() {}
  open() { this.readyState = 1; this.onopen?.(); }
  frame(seq: number) {
    this.onmessage?.({ data: JSON.stringify({ type: 'prose', seq, speaker: 'narrator', text: `line ${seq}`, hints: [] }) });
  }
  drop(code: number) { this.readyState = 3; this.onclose?.({ code }); }
}

const clientOf = (url: string) => new URL(url.replace(/^ws/, 'http')).searchParams.get('client');

beforeEach(() => {
  FakeSocket.opened = [];
  (globalThis as unknown as { WebSocket: unknown }).WebSocket = FakeSocket;
  jest.useFakeTimers();
});

afterEach(() => {
  jest.useRealTimers();
});

it('names itself on every connect, the same name after a drop', () => {
  const ws = new WyrdWebSocket();
  ws.connect('http://home.local:7070', 'tok');
  const first = FakeSocket.opened[0];
  first.open();
  expect(clientOf(first.url)).toBeTruthy();

  first.drop(1006);
  jest.advanceTimersByTime(3000);

  const again = FakeSocket.opened[1];
  expect(again).toBeDefined();
  expect(clientOf(again.url)).toBe(clientOf(first.url));
  ws.disconnect();
});

it('two apps have names of their own', () => {
  const a = new WyrdWebSocket();
  const b = new WyrdWebSocket();
  a.connect('http://home.local:7070', 'tok');
  b.connect('http://home.local:7070', 'tok');
  expect(clientOf(FakeSocket.opened[0].url)).not.toBe(clientOf(FakeSocket.opened[1].url));
  a.disconnect();
  b.disconnect();
});

it('asks for the replay from the newest line it saw', () => {
  const ws = new WyrdWebSocket();
  ws.connect('http://home.local:7070', 'tok');
  const first = FakeSocket.opened[0];
  first.open();
  first.frame(41);
  first.frame(42);

  first.drop(1006);
  jest.advanceTimersByTime(3000);
  const again = FakeSocket.opened[1];
  again.open();

  const replay = again.sent.map((s) => JSON.parse(s)).find((m) => m.type === 'reconnect');
  expect(replay?.lastSeenSeq).toBe(42);
  ws.disconnect();
});

it('does not reconnect when a newer connection took the session up', () => {
  const ws = new WyrdWebSocket();
  ws.connect('http://home.local:7070', 'tok');
  const first = FakeSocket.opened[0];
  first.open();

  first.drop(4005);
  jest.advanceTimersByTime(60_000);

  expect(FakeSocket.opened).toHaveLength(1);
  expect(ws.state).toBe('disconnected');
});
