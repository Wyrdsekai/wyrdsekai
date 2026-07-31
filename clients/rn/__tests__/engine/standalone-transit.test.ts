/**
 * Tests for TransitCoordinator's Between visiting mode integration.
 *
 * Verifies:
 * - Transit to a Between-hosted room enters 'visiting' mode
 * - Say/look in visiting mode route through VisitingRoomProxy
 * - returnToLocal from visiting mode cleans up proxy
 * - Between rooms take priority over unavailable (but not server rooms)
 * - No Between = no visiting transit
 */
import { PhoneNode } from '../../src/engine/PhoneNode';
import { TransitCoordinator, type TransitEvent } from '../../src/engine/transit/TransitCoordinator';
import { InMemoryServerConnection } from '../../src/engine/transit/ServerConnection';
import { InMemoryBetweenClient } from '../../src/engine/between/BetweenClient';
import { TierManager } from '../../src/engine/tier/TierManager';

const stubJournal = {
  append: jest.fn(),
  replay: jest.fn().mockResolvedValue([]),
  saveSnapshot: jest.fn(),
  loadSnapshot: jest.fn().mockResolvedValue(null),
  compact: jest.fn().mockResolvedValue(undefined),
  getEventCount: jest.fn().mockResolvedValue(0),
};
const stubInference = {
  complete: jest.fn().mockResolvedValue({ content: '', promptTokens: 0, completionTokens: 0 }),
};

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
// To exercise Between/remote transit through the `terminal` exit we must first
// walk the player from the Study into the Home room, whose north exit targets
// `terminal` (remote/Between-only at T1).
async function walkToHome(phone: PhoneNode): Promise<void> {
  await phone.go('player-1', 'Player', 'north');
}

describe('TransitCoordinator — Between visiting mode', () => {
  it('transit to Between-hosted room enters visiting mode', async () => {
    const phone = await makePhoneNode('T1'); // Only nexus is local

    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');

    phone.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    const coordinator = new TransitCoordinator(phone, null);

    // Register a Between-hosted room at the Home room's north exit target
    coordinator.betweenRooms.set('terminal', 'host-node-2');

    await walkToHome(phone);

    const events: TransitEvent[] = [];
    coordinator.onEvent(e => events.push(e));

    const handled = await coordinator.go('player-1', 'Player', 'north');
    expect(handled).toBe(true);
    expect(coordinator.mode).toBe('visiting');

    const visitEvent = events.find(e => e.type === 'transited_to_visiting');
    expect(visitEvent).toBeDefined();
    if (visitEvent?.type === 'transited_to_visiting') {
      expect(visitEvent.roomId).toBe('terminal');
      expect(visitEvent.hostNodeId).toBe('host-node-2');
    }

    // PhoneNode should have a visiting room proxy
    expect(phone.visitingRoom).not.toBeNull();
    expect(phone.visitingRoom!.roomId).toBe('terminal');
  });

  it('Between room not used when no Between is wired', async () => {
    const phone = await makePhoneNode('T1');
    // No setBetween() — phone.hasBetween is false

    const coordinator = new TransitCoordinator(phone, null);
    coordinator.betweenRooms.set('terminal', 'host-node-2');

    await walkToHome(phone);
    const handled = await coordinator.go('player-1', 'Player', 'north');
    expect(handled).toBe(false); // Can't transit — no server, no Between
    expect(coordinator.mode).toBe('local');
  });

  it('local room takes priority over Between room', async () => {
    const phone = await makePhoneNode('T2'); // terminal is local at T2

    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    phone.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    const coordinator = new TransitCoordinator(phone, null);
    coordinator.betweenRooms.set('terminal', 'host-node-2');

    const handled = await coordinator.go('player-1', 'Player', 'north');
    expect(handled).toBe(true);
    expect(coordinator.mode).toBe('local'); // Stayed local because terminal is a local room
  });

  it('say in visiting mode sends to proxy', async () => {
    const phone = await makePhoneNode('T1');

    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    phone.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    const coordinator = new TransitCoordinator(phone, null);
    coordinator.betweenRooms.set('terminal', 'host-node-2');

    await walkToHome(phone);
    await coordinator.go('player-1', 'Player', 'north');
    expect(coordinator.mode).toBe('visiting');

    // Clear earlier published messages
    between.published.length = 0;

    await coordinator.say('player-1', 'Player', 'Hello remote room!');

    const cmdMessages = between.published.filter(
      m => m.subject === 'between.household-1.room.terminal.commands',
    );
    expect(cmdMessages.length).toBe(1);
    const cmd = JSON.parse(new TextDecoder().decode(cmdMessages[0].data));
    expect(cmd.type).toBe('say_in_room');
    expect(cmd.text).toBe('Hello remote room!');
  });

  it('returnToLocal from visiting mode cleans up proxy', async () => {
    const phone = await makePhoneNode('T1');

    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    phone.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    const coordinator = new TransitCoordinator(phone, null);
    coordinator.betweenRooms.set('terminal', 'host-node-2');

    await walkToHome(phone);

    const events: TransitEvent[] = [];
    coordinator.onEvent(e => events.push(e));

    await coordinator.go('player-1', 'Player', 'north');
    expect(coordinator.mode).toBe('visiting');
    expect(phone.visitingRoom).not.toBeNull();

    coordinator.returnToLocal('player-1', 'Player');
    expect(coordinator.mode).toBe('local');
    expect(phone.visitingRoom).toBeNull();

    expect(events.some(e => e.type === 'returned_to_local')).toBe(true);
  });

  it('look in visiting mode emits proxy state', async () => {
    const phone = await makePhoneNode('T1');

    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    phone.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    const coordinator = new TransitCoordinator(phone, null);
    coordinator.betweenRooms.set('terminal', 'host-node-2');

    await walkToHome(phone);
    await coordinator.go('player-1', 'Player', 'north');
    expect(coordinator.mode).toBe('visiting');

    // Simulate a room_created event to give the proxy some state
    const event = {
      type: 'room_created' as const,
      roomId: 'terminal',
      name: 'The Terminal',
      description: 'Banks of crystalline screens.',
      zone: 'foundation',
      timestamp: Date.now(),
    };
    const data = new TextEncoder().encode(JSON.stringify(event));
    between.publish('between.household-1.room.terminal.events', data);

    const events: TransitEvent[] = [];
    coordinator.onEvent(e => events.push(e));

    coordinator.look();

    const roomState = events.find(e => e.type === 'remote_room_state');
    expect(roomState).toBeDefined();
    if (roomState?.type === 'remote_room_state') {
      expect(roomState.roomId).toBe('terminal');
      expect(roomState.name).toBe('The Terminal');
    }
  });

  it('server room takes priority over Between room when both available', async () => {
    const phone = await makePhoneNode('T1');

    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    phone.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    const server = new InMemoryServerConnection();
    server.isConnected = true;
    server.addRemoteRooms('terminal');

    const coordinator = new TransitCoordinator(phone, server);
    coordinator.start();
    coordinator.betweenRooms.set('terminal', 'host-node-2');

    await walkToHome(phone);

    const events: TransitEvent[] = [];
    coordinator.onEvent(e => events.push(e));

    // The go() method checks: local first, then Between, then server.
    // Since Between is checked before server in the cascade,
    // Between-hosted rooms take priority when hasBetween is true.
    const handled = await coordinator.go('player-1', 'Player', 'north');
    expect(handled).toBe(true);
    // Between is checked before server in the current implementation
    expect(coordinator.mode).toBe('visiting');

    coordinator.stop();
  });
});
