/**
 * Tests for PhoneNode server room visiting via WebSocket.
 *
 * Verifies:
 * - Home room has "out" exit pointing to server:nexus
 * - go("out") with no server connection emits an error
 * - go("out") with a connected server enters server-visiting mode
 * - say() in server-visiting mode routes to the server
 * - go("back") returns to local home room
 * - Server room_state messages are forwarded as room_changed events
 * - Server prose messages are forwarded as prose events
 * - Server room_state messages include injected "back" exit
 */
import { PhoneNode, ROOM_DEFINITIONS, type PhoneNodeEvent } from '../../src/engine/PhoneNode';
import type { ModelRole } from '../../src/inference/InferenceRouter';
import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';
import { InMemoryVitalityStore } from '../../src/engine/persistence/InMemoryVitalityStore';
import { InMemoryServerConnection } from '../../src/engine/transit/ServerConnection';
import type { ChatMessage, ChatResponse } from '../../src/inference/types';
import type { S2CMessage } from '../../src/protocol/s2c';

const mockInference = {
  async complete(_role: ModelRole, messages: ChatMessage[]): Promise<ChatResponse> {
    return { content: 'Echo', promptTokens: 10, completionTokens: 5 };
  },
};

describe('PhoneNode server room visiting', () => {
  let node: PhoneNode;

  beforeEach(async () => {
    node = new PhoneNode(
      new InMemoryEventJournal(),
      new InMemoryVitalityStore(),
      mockInference,
    );
    await node.start();
    // The phone now boots into the Study; the "out" exit to the household
    // server lives on the Home room (north of the Study). Walk the player
    // into Home so the server-visit cases below have an "out" exit to use.
    await node.go('player', 'You', 'north');
  });

  afterEach(() => {
    node.stop();
  });

  it('home room definition has "out" exit to server:nexus', () => {
    const homeDef = ROOM_DEFINITIONS['home'];
    const outExit = homeDef.exits.find(e => e.direction === 'out');
    expect(outExit).toBeDefined();
    expect(outExit!.targetRoom).toBe('server:nexus');
    expect(outExit!.label).toBe('Step outside to the household');
  });

  it('go("out") with no server connection emits error', async () => {
    const events: PhoneNodeEvent[] = [];
    node.onEvent(e => events.push(e));

    await node.go('player', 'You', 'out');

    const error = events.find(e => e.type === 'error');
    expect(error).toBeDefined();
    expect(error!.type === 'error' && error!.code).toBe('no_server');
  });

  it('go("out") with connected server enters server-visiting mode', async () => {
    const conn = new InMemoryServerConnection();
    conn.isConnected = true;
    node.serverConnection = conn;

    const events: PhoneNodeEvent[] = [];
    node.onEvent(e => events.push(e));

    await node.go('player', 'You', 'out');

    // Should be visiting server room
    expect(node.visitingServerRoom).toBe('nexus');

    // Should have sent a Look C2S to the server
    const lookMsg = conn.sent.find(m => m.type === 'look');
    expect(lookMsg).toBeDefined();
    expect(lookMsg!.type === 'look' && (lookMsg as any).roomId).toBe('nexus');

    // Should have emitted server_room_entered
    const entered = events.find(e => e.type === 'server_room_entered');
    expect(entered).toBeDefined();
    expect(entered!.type === 'server_room_entered' && entered!.roomId).toBe('nexus');

    // Should have emitted narrator prose
    const prose = events.find(
      e => e.type === 'prose' && e.text.includes('step outside'),
    );
    expect(prose).toBeDefined();
  });

  it('say() in server mode routes to server', async () => {
    const conn = new InMemoryServerConnection();
    conn.isConnected = true;
    node.serverConnection = conn;

    await node.go('player', 'You', 'out');
    conn.sent.length = 0;

    await node.say('player', 'You', 'Hello world!');

    const sayMsg = conn.sent.find(m => m.type === 'say');
    expect(sayMsg).toBeDefined();
    expect(sayMsg!.type === 'say' && (sayMsg as any).text).toBe('Hello world!');
    expect(sayMsg!.type === 'say' && (sayMsg as any).roomId).toBe('nexus');
  });

  it('go("back") returns to local home room', async () => {
    const conn = new InMemoryServerConnection();
    conn.isConnected = true;
    node.serverConnection = conn;

    await node.go('player', 'You', 'out');
    expect(node.visitingServerRoom).toBe('nexus');

    const events: PhoneNodeEvent[] = [];
    node.onEvent(e => events.push(e));

    await node.go('player', 'You', 'back');

    // Should no longer be visiting
    expect(node.visitingServerRoom).toBeNull();

    // Should have emitted room_changed for the Study (the player's home base —
    // returnFromServerRoom now re-enters the Study, not the legacy Home/Nexus).
    const roomChanged = events.find(e => e.type === 'room_changed');
    expect(roomChanged).toBeDefined();
    expect(
      roomChanged!.type === 'room_changed' && roomChanged!.snapshot.roomId,
    ).toBe('study');

    // Should have emitted server_room_left
    const left = events.find(e => e.type === 'server_room_left');
    expect(left).toBeDefined();
    expect(left!.type === 'server_room_left' && left!.roomId).toBe('nexus');
  });

  it('server room_state forwarded as room_changed with injected back exit', async () => {
    const conn = new InMemoryServerConnection();
    conn.isConnected = true;
    node.serverConnection = conn;

    await node.go('player', 'You', 'out');

    const events: PhoneNodeEvent[] = [];
    node.onEvent(e => events.push(e));

    // Simulate server sending room_state
    conn.receive({
      type: 'room_state',
      seq: 1,
      room: {
        roomId: 'nexus',
        name: 'The Nexus',
        description: 'The central hub of the household.',
        zone: 'foundation',
        exits: [
          { direction: 'north', targetRoom: 'terminal', label: 'To Terminal' },
        ],
        entities: [],
        objects: [],
        hints: [],
      },
      inventory: null,
    } satisfies S2CMessage);

    const roomChanged = events.find(e => e.type === 'room_changed');
    expect(roomChanged).toBeDefined();
    expect(
      roomChanged!.type === 'room_changed' && roomChanged!.snapshot.name,
    ).toBe('The Nexus');

    // Should have injected "back" exit
    const exits =
      roomChanged!.type === 'room_changed' ? roomChanged!.snapshot.exits : [];
    const backExit = exits.find(e => e.direction === 'back');
    expect(backExit).toBeDefined();
    expect(backExit!.targetRoom).toBe('home');
    expect(backExit!.label).toBe('Return to your phone');
  });

  it('server prose forwarded as prose event', async () => {
    const conn = new InMemoryServerConnection();
    conn.isConnected = true;
    node.serverConnection = conn;

    await node.go('player', 'You', 'out');

    const events: PhoneNodeEvent[] = [];
    node.onEvent(e => events.push(e));

    // Simulate server sending prose
    conn.receive({
      type: 'prose',
      seq: 2,
      speaker: 'Guide',
      text: 'Welcome to the Nexus!',
      hints: [],
      structured: null,
      priority: 'normal',
    } satisfies S2CMessage);

    const prose = events.find(
      e => e.type === 'prose' && e.text === 'Welcome to the Nexus!',
    );
    expect(prose).toBeDefined();
    expect(prose!.type === 'prose' && prose!.speaker).toBe('Guide');
  });

  it('go in server mode forwards Go to server', async () => {
    const conn = new InMemoryServerConnection();
    conn.isConnected = true;
    node.serverConnection = conn;

    await node.go('player', 'You', 'out');
    conn.sent.length = 0;

    await node.go('player', 'You', 'north');

    const goMsg = conn.sent.find(m => m.type === 'go');
    expect(goMsg).toBeDefined();
    expect(goMsg!.type === 'go' && (goMsg as any).direction).toBe('north');
    expect(goMsg!.type === 'go' && (goMsg as any).roomId).toBe('nexus');
  });

  it('stop() cleans up server visit state', async () => {
    const conn = new InMemoryServerConnection();
    conn.isConnected = true;
    node.serverConnection = conn;

    await node.go('player', 'You', 'out');
    expect(node.visitingServerRoom).toBe('nexus');

    node.stop();

    expect(node.visitingServerRoom).toBeNull();
    expect(node.state).toBe('stopped');
  });

  it('go("home") also returns to local home room', async () => {
    const conn = new InMemoryServerConnection();
    conn.isConnected = true;
    node.serverConnection = conn;

    await node.go('player', 'You', 'out');
    expect(node.visitingServerRoom).toBe('nexus');

    await node.go('player', 'You', 'home');

    expect(node.visitingServerRoom).toBeNull();
  });
});
