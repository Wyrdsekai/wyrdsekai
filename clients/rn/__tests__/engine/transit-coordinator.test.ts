import { InMemoryServerConnection } from '../../src/engine/transit/ServerConnection';
import { TransitCoordinator, type TransitEvent } from '../../src/engine/transit/TransitCoordinator';
import { PhoneNode, ROOM_DEFINITIONS } from '../../src/engine/PhoneNode';
import { TierManager } from '../../src/engine/tier/TierManager';
import type { S2CMessage } from '../../src/protocol/s2c';
import type { C2SGo, C2SSay, C2SLook } from '../../src/protocol/c2s';

// Minimal stubs for PhoneNode construction
const stubJournal = { append: jest.fn(), replay: jest.fn().mockResolvedValue([]), saveSnapshot: jest.fn(), loadSnapshot: jest.fn().mockResolvedValue(null), compact: jest.fn().mockResolvedValue(undefined), getEventCount: jest.fn().mockResolvedValue(0) };
const stubInference = { complete: jest.fn().mockResolvedValue({ content: '', promptTokens: 0, completionTokens: 0 }) };

function makeTierManager(tier: 'T0' | 'T1' | 'T2' | 'T3'): TierManager {
  const probe = {
    snapshot: () => ({
      availableMemoryMb: tier >= 'T2' ? 2000 : 500,
      totalMemoryMb: 4000,
      batteryPercent: 80,
      isCharging: false,
      thermalState: 'NOMINAL' as const,
      hasWifi: true,
    }),
  };
  const tm = new TierManager(probe);
  tm.forceTier(tier);
  return tm;
}

async function makePhoneNode(tier: 'T0' | 'T1' | 'T2' | 'T3'): Promise<PhoneNode> {
  const tm = makeTierManager(tier);
  const phone = new PhoneNode(stubJournal, null, stubInference, tm);
  await phone.startRoomsOnly();
  return phone;
}

// The phone now boots into the Study (only exit: north -> local Home room).
// To exercise remote/visiting transit we must first walk the player to the
// Home room, whose north exit targets `terminal` (remote-only at T1).
async function walkToHome(phone: PhoneNode): Promise<void> {
  await phone.go('player-1', 'Player', 'north');
}

describe('TransitCoordinator', () => {
  it('starts in local mode', () => {
    const phone = new PhoneNode(stubJournal, null, stubInference, null);
    const coordinator = new TransitCoordinator(phone, null);

    expect(coordinator.mode).toBe('local');
    expect(coordinator.remoteRoomId).toBeNull();
    expect(coordinator.isRemote).toBe(false);
  });

  it('local go routes to PhoneNode', async () => {
    const phone = await makePhoneNode('T2');
    const coordinator = new TransitCoordinator(phone, null);

    // Go from nexus to terminal (both local at T2)
    const handled = await coordinator.go('player-1', 'Player', 'north');
    expect(handled).toBe(true);
    expect(coordinator.mode).toBe('local');
  });

  it('remote go transits to server', async () => {
    const phone = await makePhoneNode('T1'); // Only nexus is local

    const server = new InMemoryServerConnection();
    server.isConnected = true;
    server.addRemoteRooms('terminal');

    const coordinator = new TransitCoordinator(phone, server);
    coordinator.start();

    await walkToHome(phone);

    const events: TransitEvent[] = [];
    coordinator.onEvent(e => events.push(e));

    const handled = await coordinator.go('player-1', 'Player', 'north');
    expect(handled).toBe(true);
    expect(coordinator.mode).toBe('remote');
    expect(coordinator.remoteRoomId).toBe('terminal');
    expect(coordinator.isRemote).toBe(true);

    // Should have emitted transit event
    expect(events.length).toBe(1);
    expect(events[0].type).toBe('transited_to_remote');
    expect((events[0] as any).roomId).toBe('terminal');

    // Should have sent a Look command to the server
    expect(server.sent.some(m => m.type === 'look')).toBe(true);

    coordinator.stop();
  });

  it('say in remote mode sends to server', async () => {
    const phone = await makePhoneNode('T1');

    const server = new InMemoryServerConnection();
    server.isConnected = true;
    server.addRemoteRooms('terminal');

    const coordinator = new TransitCoordinator(phone, server);
    coordinator.start();

    await walkToHome(phone);
    await coordinator.go('player-1', 'Player', 'north');
    await coordinator.say('player-1', 'Player', 'Hello server!');

    const sayMessages = server.sent.filter(m => m.type === 'say') as C2SSay[];
    expect(sayMessages.length).toBe(1);
    expect(sayMessages[0].text).toBe('Hello server!');
    expect(sayMessages[0].roomId).toBe('terminal');

    coordinator.stop();
  });

  it('go in remote mode sends to server', async () => {
    const phone = await makePhoneNode('T1');

    const server = new InMemoryServerConnection();
    server.isConnected = true;
    server.addRemoteRooms('terminal');

    const coordinator = new TransitCoordinator(phone, server);
    coordinator.start();

    await walkToHome(phone);
    await coordinator.go('player-1', 'Player', 'north');
    await coordinator.go('player-1', 'Player', 'east');

    const goMessages = server.sent.filter(m => m.type === 'go') as C2SGo[];
    expect(goMessages.length).toBe(1);
    expect(goMessages[0].direction).toBe('east');
    expect(goMessages[0].roomId).toBe('terminal');

    coordinator.stop();
  });

  it('look in remote mode sends to server', async () => {
    const phone = await makePhoneNode('T1');

    const server = new InMemoryServerConnection();
    server.isConnected = true;
    server.addRemoteRooms('terminal');

    const coordinator = new TransitCoordinator(phone, server);
    coordinator.start();

    await walkToHome(phone);
    await coordinator.go('player-1', 'Player', 'north');
    server.sent.length = 0;

    coordinator.look();

    const lookMessages = server.sent.filter(m => m.type === 'look') as C2SLook[];
    expect(lookMessages.length).toBe(1);
    expect(lookMessages[0].roomId).toBe('terminal');

    coordinator.stop();
  });

  it('return to local switches mode', async () => {
    const phone = await makePhoneNode('T1');

    const server = new InMemoryServerConnection();
    server.isConnected = true;
    server.addRemoteRooms('terminal');

    const coordinator = new TransitCoordinator(phone, server);
    coordinator.start();

    await walkToHome(phone);

    const events: TransitEvent[] = [];
    coordinator.onEvent(e => events.push(e));

    await coordinator.go('player-1', 'Player', 'north');
    expect(coordinator.isRemote).toBe(true);

    coordinator.returnToLocal('player-1', 'Player');
    expect(coordinator.isRemote).toBe(false);
    expect(coordinator.mode).toBe('local');
    expect(coordinator.remoteRoomId).toBeNull();

    expect(events.some(e => e.type === 'returned_to_local')).toBe(true);

    coordinator.stop();
  });

  it('server prose forwarded in remote mode', async () => {
    const phone = await makePhoneNode('T1');

    const server = new InMemoryServerConnection();
    server.isConnected = true;
    server.addRemoteRooms('terminal');

    const coordinator = new TransitCoordinator(phone, server);
    coordinator.start();

    await walkToHome(phone);

    const events: TransitEvent[] = [];
    coordinator.onEvent(e => events.push(e));

    await coordinator.go('player-1', 'Player', 'north');

    server.receive({
      type: 'prose', seq: 1,
      speaker: 'narrator', text: 'You enter the guild hall.',
      hints: [], structured: null, priority: 'normal',
    });

    const proseEvents = events.filter(e => e.type === 'remote_prose');
    expect(proseEvents.length).toBe(1);
    expect((proseEvents[0] as any).speaker).toBe('narrator');
    expect((proseEvents[0] as any).text).toBe('You enter the guild hall.');

    coordinator.stop();
  });

  it('server prose ignored in local mode', () => {
    const phone = new PhoneNode(stubJournal, null, stubInference, null);
    const server = new InMemoryServerConnection();
    server.isConnected = true;

    const coordinator = new TransitCoordinator(phone, server);
    coordinator.start();

    const events: TransitEvent[] = [];
    coordinator.onEvent(e => events.push(e));

    server.receive({
      type: 'prose', seq: 1,
      speaker: 'narrator', text: 'This should be ignored.',
      hints: [], structured: null, priority: 'normal',
    });

    const proseEvents = events.filter(e => e.type === 'remote_prose');
    expect(proseEvents.length).toBe(0);

    coordinator.stop();
  });

  it('server room state updates remote room ID', async () => {
    const phone = await makePhoneNode('T1');

    const server = new InMemoryServerConnection();
    server.isConnected = true;
    server.addRemoteRooms('terminal');

    const coordinator = new TransitCoordinator(phone, server);
    coordinator.start();

    await walkToHome(phone);
    await coordinator.go('player-1', 'Player', 'north');
    expect(coordinator.remoteRoomId).toBe('terminal');

    const events: TransitEvent[] = [];
    coordinator.onEvent(e => events.push(e));

    server.receive({
      type: 'room_state', seq: 2,
      room: {
        roomId: 'guild-hall', name: 'The Guild Hall',
        description: 'A grand hall with banners.', zone: 'community',
        exits: [], entities: [], objects: [], hints: [],
      },
      inventory: null,
    });

    expect(coordinator.remoteRoomId).toBe('guild-hall');

    const roomEvents = events.filter(e => e.type === 'remote_room_state');
    expect(roomEvents.length).toBe(1);
    expect((roomEvents[0] as any).roomId).toBe('guild-hall');
    expect((roomEvents[0] as any).name).toBe('The Guild Hall');

    coordinator.stop();
  });

  it('server transit emits event', () => {
    const phone = new PhoneNode(stubJournal, null, stubInference, null);
    const server = new InMemoryServerConnection();
    server.isConnected = true;

    const coordinator = new TransitCoordinator(phone, server);
    coordinator.start();

    const events: TransitEvent[] = [];
    coordinator.onEvent(e => events.push(e));

    server.receive({
      type: 'transit', seq: 1,
      targetZoneId: 'community',
      message: 'You feel the world shift...',
    });

    const transitEvents = events.filter(e => e.type === 'server_transit');
    expect(transitEvents.length).toBe(1);
    expect((transitEvents[0] as any).targetZoneId).toBe('community');
    expect((transitEvents[0] as any).message).toBe('You feel the world shift...');

    coordinator.stop();
  });

  it('go with no server connection returns false', async () => {
    const phone = await makePhoneNode('T1');
    const coordinator = new TransitCoordinator(phone, null);

    // Walk to Home, then go north toward `terminal` which is remote-only at T1.
    // With no server connection the remote transit cannot be satisfied.
    await walkToHome(phone);
    const handled = await coordinator.go('player-1', 'Player', 'north');
    expect(handled).toBe(false);
    expect(coordinator.mode).toBe('local');
  });

  it('go with disconnected server returns false', async () => {
    const phone = await makePhoneNode('T1');

    const server = new InMemoryServerConnection();
    server.isConnected = false;
    server.addRemoteRooms('terminal');

    const coordinator = new TransitCoordinator(phone, server);

    await walkToHome(phone);
    const handled = await coordinator.go('player-1', 'Player', 'north');
    expect(handled).toBe(false);
    expect(coordinator.mode).toBe('local');
  });

  it('unsubscribe event listener', () => {
    const phone = new PhoneNode(stubJournal, null, stubInference, null);
    const server = new InMemoryServerConnection();
    server.isConnected = true;

    const coordinator = new TransitCoordinator(phone, server);
    coordinator.start();

    const events: TransitEvent[] = [];
    const unsub = coordinator.onEvent(e => events.push(e));

    server.receive({ type: 'transit', seq: 1, targetZoneId: 'z1', message: 'msg1' });
    expect(events.length).toBe(1);

    unsub();

    server.receive({ type: 'transit', seq: 2, targetZoneId: 'z2', message: 'msg2' });
    expect(events.length).toBe(1); // No new events after unsub

    coordinator.stop();
  });
});
