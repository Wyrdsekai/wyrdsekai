import {
  NativeNatsClient,
  backoffDelayMs,
  MAX_BACKOFF_MS,
  PING_INTERVAL_MS,
  type NativeNatsState,
} from '../../../src/engine/between/NativeNatsClient';

// --- Mock WebSocket ---

type WSEventHandler = ((event: any) => void) | null;

class MockWebSocket {
  static instances: MockWebSocket[] = [];

  url: string;
  sent: string[] = [];
  onopen: WSEventHandler = null;
  onmessage: WSEventHandler = null;
  onerror: WSEventHandler = null;
  onclose: WSEventHandler = null;
  readyState = 1; // OPEN
  closed = false;

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
    // Simulate async open — caller sets onopen, then we fire it
    setTimeout(() => {
      if (this.onopen && !this.closed) {
        this.onopen({ type: 'open' });
      }
    }, 0);
  }

  send(data: string): void {
    if (this.closed) throw new Error('WebSocket is closed');
    this.sent.push(data);
  }

  close(): void {
    this.closed = true;
    this.readyState = 3;
  }

  // Test helpers

  /** Simulate server sending data. */
  simulateMessage(data: string): void {
    if (this.onmessage) {
      this.onmessage({ data });
    }
  }

  /** Simulate server sending INFO (NATS handshake start). */
  simulateInfo(info: Record<string, unknown> = {}): void {
    const infoJson = JSON.stringify({
      server_id: 'test-server',
      version: '2.10.0',
      proto: 1,
      max_payload: 1048576,
      ...info,
    });
    this.simulateMessage(`INFO ${infoJson}\r\n`);
  }

  /** Simulate a MSG from the server. Length is the BYTE count, as on the real wire. */
  simulateMsg(subject: string, sid: number, payload: string): void {
    const byteLen = new TextEncoder().encode(payload).length;
    const msg = `MSG ${subject} ${sid} ${byteLen}\r\n${payload}\r\n`;
    this.simulateMessage(msg);
  }

  /** Simulate the server sending a BINARY frame (nats-server sends binary WS frames). */
  simulateBinary(bytes: Uint8Array): void {
    if (this.onmessage) {
      this.onmessage({ data: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) });
    }
  }

  /** Simulate PING from server. */
  simulatePing(): void {
    this.simulateMessage('PING\r\n');
  }

  /** Simulate connection close. */
  simulateClose(): void {
    this.closed = true;
    if (this.onclose) {
      this.onclose({ type: 'close' });
    }
  }

  /** Simulate connection error. */
  simulateError(): void {
    if (this.onerror) {
      this.onerror({ type: 'error' });
    }
  }
}

// Install mock WebSocket globally
beforeEach(() => {
  MockWebSocket.instances = [];
  (global as any).WebSocket = MockWebSocket;
  jest.useFakeTimers();
});

afterEach(() => {
  jest.useRealTimers();
  delete (global as any).WebSocket;
});

/** Helper: create client, connect, complete NATS handshake. */
async function connectClient(
  client?: NativeNatsClient,
  url = 'ws://localhost:4222',
): Promise<{ client: NativeNatsClient; ws: MockWebSocket }> {
  const c = client ?? new NativeNatsClient();
  const connectPromise = c.connect(url);

  // Advance timer to trigger WebSocket onopen
  await jest.advanceTimersByTimeAsync(1);

  // Server sends INFO
  const ws = MockWebSocket.instances[MockWebSocket.instances.length - 1];
  ws.simulateInfo();

  await connectPromise;
  return { client: c, ws };
}

// ============================================================
// Protocol message formatting
// ============================================================

describe('NativeNatsClient protocol formatting', () => {
  it('sends CONNECT command after receiving INFO', async () => {
    const { ws } = await connectClient();

    // First sent message should be CONNECT
    const connectMsg = ws.sent.find(s => s.startsWith('CONNECT '));
    expect(connectMsg).toBeDefined();
    expect(connectMsg).toContain('"verbose":false');
    expect(connectMsg).toContain('"pedantic":false');
    expect(connectMsg).toContain('"lang":"react-native"');
    expect(connectMsg!.endsWith('\r\n')).toBe(true);
  });

  it('formats PUB command correctly', async () => {
    const { client, ws } = await connectClient();

    const payload = 'hello world';
    const data = new TextEncoder().encode(payload);
    client.publish('test.subject', data);

    const pubMsg = ws.sent.find(s => s.startsWith('PUB '));
    expect(pubMsg).toBe(`PUB test.subject ${data.length}\r\n${payload}\r\n`);
  });

  it('formats SUB command correctly', async () => {
    const { client, ws } = await connectClient();

    client.subscribe('rooms.lobby', () => {});

    // SUB should be sent — find it after the CONNECT and any re-subs
    const subMsgs = ws.sent.filter(s => s.startsWith('SUB '));
    expect(subMsgs.length).toBeGreaterThanOrEqual(1);
    const sub = subMsgs[subMsgs.length - 1];
    expect(sub).toMatch(/^SUB rooms\.lobby \d+\r\n$/);
  });

  it('formats UNSUB command on unsubscribe', async () => {
    const { client, ws } = await connectClient();

    const unsub = client.subscribe('rooms.lobby', () => {});
    unsub();

    const unsubMsgs = ws.sent.filter(s => s.startsWith('UNSUB '));
    expect(unsubMsgs.length).toBe(1);
    expect(unsubMsgs[0]).toMatch(/^UNSUB \d+\r\n$/);
  });

  it('sends PONG in response to server PING', async () => {
    const { ws } = await connectClient();

    ws.simulatePing();

    const pongs = ws.sent.filter(s => s === 'PONG\r\n');
    expect(pongs.length).toBe(1);
  });

  it('PUB payload length is byte length not char length', async () => {
    const { client, ws } = await connectClient();

    // Multi-byte UTF-8 character
    const payload = '\u00e9'; // e-acute, 2 bytes in UTF-8
    const data = new TextEncoder().encode(payload);
    expect(data.length).toBe(2); // 2 bytes

    client.publish('test.utf8', data);

    const pubMsg = ws.sent.find(s => s.startsWith('PUB '));
    expect(pubMsg).toContain(' 2\r\n'); // byte length, not char length
  });
});

// ============================================================
// MSG parsing
// ============================================================

describe('NativeNatsClient MSG parsing', () => {
  it('parses basic MSG and delivers to handler', async () => {
    const { client, ws } = await connectClient();

    const received: Array<{ subject: string; data: string }> = [];
    client.subscribe('room.events', (subject, data) => {
      received.push({ subject, data: new TextDecoder().decode(data) });
    });

    // Find the SID from the SUB command
    const subCmd = ws.sent.find(s => s.startsWith('SUB room.events'));
    const sid = parseInt(subCmd!.split(' ')[2], 10);

    ws.simulateMsg('room.events', sid, '{"type":"enter"}');

    expect(received.length).toBe(1);
    expect(received[0].subject).toBe('room.events');
    expect(received[0].data).toBe('{"type":"enter"}');
  });

  it('parses MSG with reply-to field', async () => {
    const { client, ws } = await connectClient();

    const received: string[] = [];
    client.subscribe('request.topic', (_, data) => {
      received.push(new TextDecoder().decode(data));
    });

    const subCmd = ws.sent.find(s => s.startsWith('SUB request.topic'));
    const sid = parseInt(subCmd!.split(' ')[2], 10);

    // MSG with reply-to: MSG subject sid reply-to length\r\npayload\r\n
    const payload = 'response-data';
    const msg = `MSG request.topic ${sid} _INBOX.abc123 ${payload.length}\r\n${payload}\r\n`;
    ws.simulateMessage(msg);

    expect(received.length).toBe(1);
    expect(received[0]).toBe('response-data');
  });

  it('handles partial frames (split across messages)', async () => {
    const { client, ws } = await connectClient();

    const received: string[] = [];
    client.subscribe('partial.test', (_, data) => {
      received.push(new TextDecoder().decode(data));
    });

    const subCmd = ws.sent.find(s => s.startsWith('SUB partial.test'));
    const sid = parseInt(subCmd!.split(' ')[2], 10);

    // Send the MSG header in one frame, payload in another
    ws.simulateMessage(`MSG partial.test ${sid} 5\r\n`);
    expect(received.length).toBe(0); // Payload not yet received

    ws.simulateMessage('hello\r\n');
    expect(received.length).toBe(1);
    expect(received[0]).toBe('hello');
  });

  it('frames by BYTE length: multi-byte payload followed by another MSG in the same frame', async () => {
    // The KMP NatsBetweenClient over-read bug (af163bf2), RN twin: the MSG
    // header length is BYTES; buffering decoded text and slicing by chars
    // spliced the next frame's "\r\nMSG …" onto payloads containing em-dash /
    // ellipsis / CJK, desyncing the stream and dropping room_state frames.
    const { client, ws } = await connectClient();

    const received: string[] = [];
    client.subscribe('utf8.test', (_, data) => {
      received.push(new TextDecoder().decode(data));
    });
    const subCmd = ws.sent.find(s => s.startsWith('SUB utf8.test'));
    const sid = parseInt(subCmd!.split(' ')[2], 10);

    const p1 = 'a—b…'; // em-dash + ellipsis: 4 chars, 8 bytes
    const p1Bytes = new TextEncoder().encode(p1).length;
    expect(p1Bytes).toBe(8);
    const combined =
      `MSG utf8.test ${sid} ${p1Bytes}\r\n${p1}\r\n` +
      `MSG utf8.test ${sid} 3\r\nccc\r\n`;
    ws.simulateBinary(new TextEncoder().encode(combined));

    expect(received).toEqual([p1, 'ccc']);
  });

  it('reassembles a multi-byte char split across two binary frames', async () => {
    const { client, ws } = await connectClient();

    const received: string[] = [];
    client.subscribe('split.test', (_, data) => {
      received.push(new TextDecoder().decode(data));
    });
    const subCmd = ws.sent.find(s => s.startsWith('SUB split.test'));
    const sid = parseInt(subCmd!.split(' ')[2], 10);

    const payload = 'xあy'; // hiragana A: 3 bytes in UTF-8
    const full = new TextEncoder().encode(
      `MSG split.test ${sid} 5\r\n${payload}\r\n`,
    );
    // Cut INSIDE the 3-byte char (header ends at first \r\n; payload starts
    // after; byte 1 of あ lands at payload offset 1). A per-frame string
    // decode would mangle both halves into U+FFFD.
    const cut = full.length - 4; // inside あ's bytes
    ws.simulateBinary(full.slice(0, cut));
    expect(received.length).toBe(0);
    ws.simulateBinary(full.slice(cut));

    expect(received).toEqual([payload]);
  });

  it('waits when a multi-byte payload is only partially buffered', async () => {
    const { client, ws } = await connectClient();

    const received: string[] = [];
    client.subscribe('wait.test', (_, data) => {
      received.push(new TextDecoder().decode(data));
    });
    const subCmd = ws.sent.find(s => s.startsWith('SUB wait.test'));
    const sid = parseInt(subCmd!.split(' ')[2], 10);

    const payload = '——'; // 2 chars, 6 bytes
    ws.simulateMessage(`MSG wait.test ${sid} 6\r\n`);
    ws.simulateBinary(new TextEncoder().encode(payload).slice(0, 4));
    expect(received.length).toBe(0); // 4 of 6 payload bytes — keep waiting
    ws.simulateBinary(new TextEncoder().encode('—\r\n').slice(1)); // last 2 bytes + CRLF
    expect(received).toEqual([payload]);
  });

  it('handles multiple MSGs in one frame', async () => {
    const { client, ws } = await connectClient();

    const received: string[] = [];
    client.subscribe('multi.test', (_, data) => {
      received.push(new TextDecoder().decode(data));
    });

    const subCmd = ws.sent.find(s => s.startsWith('SUB multi.test'));
    const sid = parseInt(subCmd!.split(' ')[2], 10);

    // Two complete messages in one frame
    const combined =
      `MSG multi.test ${sid} 3\r\naaa\r\n` +
      `MSG multi.test ${sid} 3\r\nbbb\r\n`;
    ws.simulateMessage(combined);

    expect(received.length).toBe(2);
    expect(received[0]).toBe('aaa');
    expect(received[1]).toBe('bbb');
  });

  it('ignores MSG with unknown SID', async () => {
    const { ws } = await connectClient();

    // MSG to a SID that nobody subscribed to
    ws.simulateMsg('unknown.subject', 9999, 'orphan-data');
    // Should not throw
  });

  it('ignores malformed MSG lines', async () => {
    const { ws } = await connectClient();

    // MSG with too few parts
    ws.simulateMessage('MSG only-subject\r\n');
    // MSG with non-numeric SID
    ws.simulateMessage('MSG subject abc 5\r\nhello\r\n');
    // MSG with non-numeric length
    ws.simulateMessage('MSG subject 1 xyz\r\n');
    // None should throw
  });

  it('handles empty payload MSG', async () => {
    const { client, ws } = await connectClient();

    const received: string[] = [];
    client.subscribe('empty.test', (_, data) => {
      received.push(new TextDecoder().decode(data));
    });

    const subCmd = ws.sent.find(s => s.startsWith('SUB empty.test'));
    const sid = parseInt(subCmd!.split(' ')[2], 10);

    // Empty payload: length=0
    ws.simulateMessage(`MSG empty.test ${sid} 0\r\n\r\n`);

    expect(received.length).toBe(1);
    expect(received[0]).toBe('');
  });

  it('handler exception does not crash receive loop', async () => {
    const { client, ws } = await connectClient();

    let callCount = 0;
    client.subscribe('crash.test', () => {
      callCount++;
      if (callCount === 1) throw new Error('handler crash');
    });

    const subCmd = ws.sent.find(s => s.startsWith('SUB crash.test'));
    const sid = parseInt(subCmd!.split(' ')[2], 10);

    // First message throws, second should still arrive
    ws.simulateMsg('crash.test', sid, 'first');
    ws.simulateMsg('crash.test', sid, 'second');

    expect(callCount).toBe(2);
  });
});

// ============================================================
// Connection lifecycle
// ============================================================

describe('NativeNatsClient connection lifecycle', () => {
  it('isConnected is false before connect', () => {
    const client = new NativeNatsClient();
    expect(client.isConnected).toBe(false);
  });

  it('isConnected is true after successful connect', async () => {
    const { client } = await connectClient();
    expect(client.isConnected).toBe(true);
    expect(client.state).toBe('connected');
  });

  it('isConnected is false after disconnect', async () => {
    const { client } = await connectClient();
    await client.disconnect();
    expect(client.isConnected).toBe(false);
    expect(client.state).toBe('disconnected');
  });

  it('connect rejects on WebSocket error', async () => {
    const client = new NativeNatsClient();
    const connectPromise = client.connect('ws://bad-host:4222');

    await jest.advanceTimersByTimeAsync(1);

    const ws = MockWebSocket.instances[MockWebSocket.instances.length - 1];
    ws.simulateError();

    await expect(connectPromise).rejects.toThrow('WebSocket connection error');
    expect(client.isConnected).toBe(false);
    expect(client.state).toBe('error');
  });

  it('connect rejects on WebSocket close before handshake', async () => {
    const client = new NativeNatsClient();
    const connectPromise = client.connect('ws://closing-host:4222');

    await jest.advanceTimersByTimeAsync(1);

    const ws = MockWebSocket.instances[MockWebSocket.instances.length - 1];
    ws.simulateClose();

    await expect(connectPromise).rejects.toThrow('closed before NATS handshake');
    expect(client.isConnected).toBe(false);
  });

  it('state transitions through connecting -> connected', async () => {
    const client = new NativeNatsClient();
    const states: NativeNatsState[] = [];
    client.onStateChange(s => states.push(s));

    await connectClient(client);

    expect(states).toEqual(['connecting', 'connected']);
  });

  it('disconnect is safe to call multiple times', async () => {
    const { client } = await connectClient();
    await client.disconnect();
    await client.disconnect(); // Should not throw
    expect(client.isConnected).toBe(false);
  });

  it('disconnect during connect rejects the connect promise', async () => {
    const client = new NativeNatsClient();
    const connectPromise = client.connect('ws://slow-host:4222');

    await jest.advanceTimersByTimeAsync(1);

    // Disconnect before INFO arrives
    await client.disconnect();

    await expect(connectPromise).rejects.toThrow('disconnect() called during connect');
  });

  it('publish is silent when not connected', () => {
    const client = new NativeNatsClient();
    // Should not throw
    client.publish('test', new TextEncoder().encode('data'));
  });

  it('subscribe before connect queues subscription', async () => {
    const client = new NativeNatsClient();
    const received: string[] = [];
    client.subscribe('pre.connect', (_, data) => {
      received.push(new TextDecoder().decode(data));
    });

    // Now connect — the subscription should be re-sent
    const { ws } = await connectClient(client);

    const subMsgs = ws.sent.filter(s => s.startsWith('SUB pre.connect'));
    expect(subMsgs.length).toBe(1);

    // Messages should be delivered
    const sid = parseInt(subMsgs[0].split(' ')[2], 10);
    ws.simulateMsg('pre.connect', sid, 'late-data');
    expect(received.length).toBe(1);
    expect(received[0]).toBe('late-data');
  });

  it('state listener unsubscribe works', async () => {
    const client = new NativeNatsClient();
    const states: NativeNatsState[] = [];
    const unsub = client.onStateChange(s => states.push(s));

    unsub(); // Remove listener

    await connectClient(client);
    // Listener should not have been called after removal
    expect(states.length).toBe(0);
  });
});

// ============================================================
// Reconnection
// ============================================================

describe('NativeNatsClient reconnection', () => {
  it('auto-reconnects after connection drop', async () => {
    const { client, ws } = await connectClient();
    client.autoReconnect = true;

    // Simulate connection drop
    ws.simulateClose();
    expect(client.isConnected).toBe(false);
    expect(client.state).toBe('reconnecting');

    // Advance past first backoff (1s)
    await jest.advanceTimersByTimeAsync(1001);

    // A new WebSocket should have been created
    const newWs = MockWebSocket.instances[MockWebSocket.instances.length - 1];
    expect(newWs).not.toBe(ws);

    // Advance timer for new WS onopen
    await jest.advanceTimersByTimeAsync(1);

    // Complete handshake on new connection
    newWs.simulateInfo();

    // Need to let the promise chain resolve
    await jest.advanceTimersByTimeAsync(0);

    expect(client.isConnected).toBe(true);
  });

  it('does not auto-reconnect after explicit disconnect', async () => {
    const { client, ws } = await connectClient();
    client.autoReconnect = true;

    await client.disconnect();

    // Advance timers well past any backoff
    await jest.advanceTimersByTimeAsync(30_000);

    // No new WebSocket should have been created (only the original one)
    expect(MockWebSocket.instances.length).toBe(1);
  });

  it('re-subscribes all subjects after reconnect', async () => {
    const { client, ws } = await connectClient();
    client.autoReconnect = true;

    client.subscribe('room.a', () => {});
    client.subscribe('room.b', () => {});

    // Drop and reconnect
    ws.simulateClose();
    await jest.advanceTimersByTimeAsync(1001);

    const newWs = MockWebSocket.instances[MockWebSocket.instances.length - 1];
    await jest.advanceTimersByTimeAsync(1);
    newWs.simulateInfo();
    await jest.advanceTimersByTimeAsync(0);

    // Check new WebSocket received SUB commands for both subjects
    const subCmds = newWs.sent.filter(s => s.startsWith('SUB '));
    const subjects = subCmds.map(s => s.split(' ')[1]);
    expect(subjects).toContain('room.a');
    expect(subjects).toContain('room.b');
  });
});

// ============================================================
// Backoff calculation
// ============================================================

describe('backoffDelayMs', () => {
  it('returns 1s for attempt 0', () => {
    expect(backoffDelayMs(0)).toBe(1000);
  });

  it('returns 2s for attempt 1', () => {
    expect(backoffDelayMs(1)).toBe(2000);
  });

  it('returns 4s for attempt 2', () => {
    expect(backoffDelayMs(2)).toBe(4000);
  });

  it('returns 8s for attempt 3', () => {
    expect(backoffDelayMs(3)).toBe(8000);
  });

  it('returns 16s for attempt 4', () => {
    expect(backoffDelayMs(4)).toBe(16000);
  });

  it('caps at MAX_BACKOFF_MS for large attempts', () => {
    expect(backoffDelayMs(10)).toBe(MAX_BACKOFF_MS);
    expect(backoffDelayMs(100)).toBe(MAX_BACKOFF_MS);
  });
});

// ============================================================
// Ping keepalive
// ============================================================

describe('NativeNatsClient ping keepalive', () => {
  it('sends PING at configured interval', async () => {
    const { ws } = await connectClient();

    // Advance past one ping interval
    jest.advanceTimersByTime(PING_INTERVAL_MS + 1);

    const pings = ws.sent.filter(s => s === 'PING\r\n');
    expect(pings.length).toBeGreaterThanOrEqual(1);
  });

  it('stops pinging after disconnect', async () => {
    const { client, ws } = await connectClient();

    await client.disconnect();
    ws.sent.length = 0; // Clear sent buffer

    // Advance past multiple ping intervals
    jest.advanceTimersByTime(PING_INTERVAL_MS * 3);

    const pings = ws.sent.filter(s => s === 'PING\r\n');
    expect(pings.length).toBe(0);
  });
});

// ============================================================
// Server protocol messages
// ============================================================

describe('NativeNatsClient server message handling', () => {
  it('handles +OK silently', async () => {
    const { ws } = await connectClient();
    ws.simulateMessage('+OK\r\n');
    // Should not throw
  });

  it('handles -ERR silently', async () => {
    const { ws } = await connectClient();
    ws.simulateMessage("-ERR 'Authorization Violation'\r\n");
    // Should not throw or disconnect
  });

  it('handles PONG silently', async () => {
    const { ws } = await connectClient();
    ws.simulateMessage('PONG\r\n');
    // Should not throw
  });

  it('handles subsequent INFO messages silently', async () => {
    const { ws } = await connectClient();
    ws.simulateInfo({ server_id: 'new-server' });
    // Should not throw or re-handshake
  });

  it('handles unknown protocol lines silently', async () => {
    const { ws } = await connectClient();
    ws.simulateMessage('SOME_FUTURE_COMMAND arg1 arg2\r\n');
    // Should not throw
  });
});

// ============================================================
// NatsBetweenAdapter platform switching
// ============================================================

describe('NatsBetweenAdapter platform switching', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const { NatsBetweenAdapter } = require('../../../src/engine/between/NatsBetweenAdapter');

  it('uses NativeNatsClient when forced to native', async () => {
    const adapter = new NatsBetweenAdapter();
    adapter._forcePlatform = 'native';

    // Connect — this will use NativeNatsClient which creates a WebSocket
    const connectPromise = adapter.connect('ws://localhost:4222');

    await jest.advanceTimersByTimeAsync(1);

    const ws = MockWebSocket.instances[MockWebSocket.instances.length - 1];
    ws.simulateInfo();

    await connectPromise;

    expect(adapter.isConnected).toBe(true);
  });

  it('native adapter publish and subscribe work', async () => {
    const adapter = new NatsBetweenAdapter();
    adapter._forcePlatform = 'native';

    const connectPromise = adapter.connect('ws://localhost:4222');
    await jest.advanceTimersByTimeAsync(1);
    const ws = MockWebSocket.instances[MockWebSocket.instances.length - 1];
    ws.simulateInfo();
    await connectPromise;

    // Subscribe
    const received: string[] = [];
    adapter.subscribe('test.topic', (_: string, data: Uint8Array) => {
      received.push(new TextDecoder().decode(data));
    });

    // Publish
    adapter.publish('test.topic', new TextEncoder().encode('hello'));
    const pubMsg = ws.sent.find((s: string) => s.startsWith('PUB '));
    expect(pubMsg).toBeDefined();

    // Simulate receiving a message
    const subCmd = ws.sent.find((s: string) => s.startsWith('SUB test.topic'));
    const sid = parseInt(subCmd!.split(' ')[2], 10);
    ws.simulateMsg('test.topic', sid, 'world');
    expect(received).toEqual(['world']);
  });

  it('native adapter disconnect works', async () => {
    const adapter = new NatsBetweenAdapter();
    adapter._forcePlatform = 'native';

    const connectPromise = adapter.connect('ws://localhost:4222');
    await jest.advanceTimersByTimeAsync(1);
    const ws = MockWebSocket.instances[MockWebSocket.instances.length - 1];
    ws.simulateInfo();
    await connectPromise;

    await adapter.disconnect();
    expect(adapter.isConnected).toBe(false);
  });

  it('throws Not connected when publish without connection', () => {
    const adapter = new NatsBetweenAdapter();
    expect(() => adapter.publish('test', new Uint8Array())).toThrow('Not connected');
  });

  it('throws Not connected when subscribe without connection', () => {
    const adapter = new NatsBetweenAdapter();
    expect(() => adapter.subscribe('test', () => {})).toThrow('Not connected');
  });
});
