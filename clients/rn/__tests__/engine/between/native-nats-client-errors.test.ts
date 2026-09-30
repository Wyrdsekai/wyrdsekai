/**
 * NativeNatsClient and the server's answers (the home bus, W2):
 * connect() resolves only when the server has taken the login (PONG after
 * CONNECT + PING); a refused login fails connect() and stops reconnecting; a
 * permissions violation fails the subscription that caused it and is reported;
 * the adapter's isConnected follows the live connection.
 */
import {
  HANDSHAKE_TIMEOUT_MS,
  NativeNatsClient,
  parseServerError,
  type NatsServerError,
} from '../../../src/engine/between/NativeNatsClient';
import { NatsBetweenAdapter } from '../../../src/engine/between/NatsBetweenAdapter';

class FakeSocket {
  static all: FakeSocket[] = [];
  sent: string[] = [];
  onopen: ((e?: unknown) => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onerror: ((e?: unknown) => void) | null = null;
  onclose: ((e?: unknown) => void) | null = null;
  readyState = 1;
  binaryType = 'arraybuffer';
  closed = false;
  constructor(readonly url: string) {
    FakeSocket.all.push(this);
  }
  send(d: string) { this.sent.push(d); }
  close() { this.closed = true; }
  server(line: string) { this.onmessage?.({ data: line }); }
  info() { this.server('INFO {"server_id":"x","proto":1}\r\n'); }
  drop() { this.closed = true; this.onclose?.({}); }
}

const last = () => FakeSocket.all[FakeSocket.all.length - 1];

beforeEach(() => {
  FakeSocket.all = [];
  (global as unknown as { WebSocket: unknown }).WebSocket = FakeSocket;
  jest.useFakeTimers();
});
afterEach(() => {
  jest.useRealTimers();
  delete (global as unknown as { WebSocket?: unknown }).WebSocket;
});

async function connected(c = new NativeNatsClient(), creds?: { user: string; pass: string }) {
  const p = c.connect('wss://home:27223', creds);
  last().info();
  last().server('PONG\r\n');
  await p;
  return { c, ws: last() };
}

describe('reading -ERR', () => {
  it('knows a refused login, a refused publish and a refused subscription', () => {
    expect(parseServerError("-ERR 'Authorization Violation'")).toEqual({ message: 'Authorization Violation', kind: 'login' });
    expect(parseServerError(`-ERR 'Permissions Violation for Publish to "between.z.presence.n"'`))
      .toEqual({ message: 'Permissions Violation for Publish to "between.z.presence.n"', kind: 'publish', subject: 'between.z.presence.n' });
    expect(parseServerError(`-ERR 'Permissions Violation for Subscription to "between.z.*.*.study.state" using queue "q"'`))
      .toMatchObject({ kind: 'subscribe', subject: 'between.z.*.*.study.state' });
    expect(parseServerError("-ERR 'Stale Connection'")).toMatchObject({ kind: 'other' });
  });
});

describe('the login', () => {
  it('connect() waits for the server to answer the PING after CONNECT', async () => {
    const c = new NativeNatsClient();
    let done = false;
    const p = c.connect('wss://home:27223', { user: 'phone-1', pass: 'pw' }).then(() => { done = true; });
    last().info();
    expect(last().sent.join('')).toMatch(/^CONNECT \{.*"user":"phone-1".*\}\r\nPING\r\n$/);
    await Promise.resolve();
    expect(done).toBe(false);
    expect(c.isConnected).toBe(false);
    last().server('PONG\r\n');
    await p;
    expect(c.isConnected).toBe(true);
  });

  it('a refused login fails connect() and is not retried', async () => {
    const c = new NativeNatsClient();
    c.autoReconnect = true;
    const p = c.connect('wss://home:27223', { user: 'phone-1', pass: 'wrong' });
    last().info();
    last().server("-ERR 'Authorization Violation'\r\n");
    await expect(p).rejects.toThrow('NATS refused the connection: Authorization Violation');
    expect(c.refused).toBe(true);
    expect(c.isConnected).toBe(false);
    expect(c.state).toBe('error');
    expect(last().closed).toBe(true);
    await jest.advanceTimersByTimeAsync(60_000);
    expect(FakeSocket.all).toHaveLength(1);
  });

  it('a server that never answers the handshake fails connect() in time', async () => {
    const c = new NativeNatsClient();
    const p = c.connect('wss://home:27223');
    last().info();
    const check = expect(p).rejects.toThrow('did not answer the connection handshake');
    await jest.advanceTimersByTimeAsync(HANDSHAKE_TIMEOUT_MS + 1);
    await check;
  });

  it('subscriptions are sent only after the login is accepted', async () => {
    const c = new NativeNatsClient();
    c.subscribe('between.z.*.phone-1.study.sync', () => {});
    const p = c.connect('wss://home:27223');
    last().info();
    expect(last().sent.some((l) => l.startsWith('SUB '))).toBe(false);
    last().server('PONG\r\n');
    await p;
    expect(last().sent.filter((l) => l.startsWith('SUB '))).toEqual(['SUB between.z.*.phone-1.study.sync 1\r\n']);
  });
});

describe('permissions violations', () => {
  it('fail the subscription that caused them, and nothing more', async () => {
    const { c, ws } = await connected();
    const errs: NatsServerError[] = [];
    c.onError((e) => errs.push(e));
    const refused = jest.fn();
    const got: string[] = [];
    c.subscribe('between.z.*.*.study.state', () => got.push('refused-sub'), refused);
    c.subscribe('between.z.*.phone-1.study.sync', (_s, d) => got.push(new TextDecoder().decode(d)));
    ws.server(`-ERR 'Permissions Violation for Subscription to "between.z.*.*.study.state"'\r\n`);
    expect(refused).toHaveBeenCalledWith({
      subject: 'between.z.*.*.study.state',
      message: 'Permissions Violation for Subscription to "between.z.*.*.study.state"',
    });
    expect(errs).toEqual([expect.objectContaining({ kind: 'subscribe', subject: 'between.z.*.*.study.state' })]);
    ws.server('MSG between.z.a.b.study.state 1 1\r\nx\r\n');
    ws.server('MSG between.z.srv.phone-1.study.sync 2 2\r\nok\r\n');
    expect(got).toEqual(['ok']);
    expect(c.isConnected).toBe(true);
  });

  it('a refused publish is reported with its subject', async () => {
    const { c, ws } = await connected();
    const errs: NatsServerError[] = [];
    c.onError((e) => errs.push(e));
    c.publish('between.z.presence.n', new TextEncoder().encode('{}'));
    ws.server(`-ERR 'Permissions Violation for Publish to "between.z.presence.n"'\r\n`);
    expect(errs).toEqual([{ message: 'Permissions Violation for Publish to "between.z.presence.n"', kind: 'publish', subject: 'between.z.presence.n' }]);
    expect(c.isConnected).toBe(true);
  });
});

describe('drops', () => {
  it("the adapter's isConnected goes false on a drop and back after the reconnect", async () => {
    const a = new NatsBetweenAdapter();
    a._forcePlatform = 'native';
    const p = a.connect('wss://home:27223', { user: 'phone-1', pass: 'pw' });
    last().info();
    last().server('PONG\r\n');
    await p;
    expect(a.isConnected).toBe(true);
    last().drop();
    expect(a.isConnected).toBe(false);
    await jest.advanceTimersByTimeAsync(1001);
    last().info();
    last().server('PONG\r\n');
    await jest.advanceTimersByTimeAsync(0);
    expect(a.isConnected).toBe(true);
    await a.disconnect();
  });

  it('keeps reconnecting with backoff while the home is away', async () => {
    const { c, ws } = await connected();
    c.autoReconnect = true;
    ws.drop();
    await jest.advanceTimersByTimeAsync(1001); // attempt 1
    last().onerror?.({});
    last().drop();
    await jest.advanceTimersByTimeAsync(2001); // attempt 2
    expect(FakeSocket.all).toHaveLength(3);
    last().info();
    last().server('PONG\r\n');
    await jest.advanceTimersByTimeAsync(0);
    expect(c.isConnected).toBe(true);
    await c.disconnect();
  });

  it('a login withdrawn while connected is not retried', async () => {
    const { c, ws } = await connected();
    c.autoReconnect = true;
    ws.server("-ERR 'Authorization Violation'\r\n");
    ws.drop();
    await jest.advanceTimersByTimeAsync(60_000);
    expect(FakeSocket.all).toHaveLength(1);
    expect(c.refused).toBe(true);
    expect(c.isConnected).toBe(false);
  });

  it('the adapter remembers a refused login after connect() failed', async () => {
    const a = new NatsBetweenAdapter();
    a._forcePlatform = 'native';
    const p = a.connect('wss://home:27223', { user: 'phone-1', pass: 'wrong' });
    last().info();
    last().server("-ERR 'Authorization Violation'\r\n");
    await expect(p).rejects.toThrow('refused the connection');
    expect(a.loginRefused).toBe(true);
    expect(a.isConnected).toBe(false);
  });
});
