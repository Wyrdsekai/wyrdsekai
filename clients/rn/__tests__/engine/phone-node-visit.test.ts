/**
 * Tests for PhoneNode.visitRoom() — visiting rooms on other nodes via Between.
 *
 * Verifies:
 * - visitRoom() creates a VisitingRoomProxy
 * - visitRoom() returns null when no Between is wired
 * - Events from the proxy flow through to PhoneNode listeners
 * - leaveVisitingRoom() cleans up the proxy
 * - Visiting a second room cleans up the first
 */
import { PhoneNode, type PhoneNodeEvent } from '../../src/engine/PhoneNode';
import type { ModelRole } from '../../src/inference/InferenceRouter';
import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';
import { InMemoryVitalityStore } from '../../src/engine/persistence/InMemoryVitalityStore';
import { InMemoryBetweenClient } from '../../src/engine/between/BetweenClient';
import type { WorldEvent } from '../../src/engine/events/WorldEvent';
import type { ChatMessage, ChatResponse } from '../../src/inference/types';

const mockInference = {
  async complete(_role: ModelRole, messages: ChatMessage[]): Promise<ChatResponse> {
    return { content: 'Echo', promptTokens: 10, completionTokens: 5 };
  },
};

function simulateRemoteEvent(
  between: InMemoryBetweenClient,
  householdId: string,
  roomId: string,
  event: WorldEvent,
): void {
  const data = new TextEncoder().encode(JSON.stringify(event));
  between.publish(`between.${householdId}.room.${roomId}.events`, data);
}

describe('PhoneNode.visitRoom()', () => {
  let node: PhoneNode;
  let between: InMemoryBetweenClient;

  beforeEach(async () => {
    node = new PhoneNode(
      new InMemoryEventJournal(),
      new InMemoryVitalityStore(),
      mockInference,
    );
    await node.start();

    between = new InMemoryBetweenClient();
    await between.connect('ws://test');
  });

  afterEach(() => {
    node.stop();
  });

  it('returns null when no Between is wired', () => {
    const proxy = node.visitRoom('remote-room', 'host-node-1');
    expect(proxy).toBeNull();
    expect(node.visitingRoom).toBeNull();
  });

  it('creates VisitingRoomProxy when Between is wired', () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    const proxy = node.visitRoom('remote-room', 'host-node-1');
    expect(proxy).not.toBeNull();
    expect(proxy!.roomId).toBe('remote-room');
    expect(node.visitingRoom).toBe(proxy);
  });

  it('proxy receives remote room events via Between', () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    const proxy = node.visitRoom('guild-hall', 'host-node-2');
    expect(proxy).not.toBeNull();

    // Simulate a room_created event from the remote room
    simulateRemoteEvent(between, 'household-1', 'guild-hall', {
      type: 'room_created',
      roomId: 'guild-hall',
      name: 'The Guild Hall',
      description: 'A grand hall with banners.',
      zone: 'community',
      timestamp: Date.now(),
    });

    expect(proxy!.state.name).toBe('The Guild Hall');
    expect(proxy!.state.description).toBe('A grand hall with banners.');
  });

  it('proxy events flow through to PhoneNode listeners', () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    const events: PhoneNodeEvent[] = [];
    node.onEvent(e => events.push(e));

    node.visitRoom('guild-hall', 'host-node-2');

    // Simulate a 'said' event from the remote room
    simulateRemoteEvent(between, 'household-1', 'guild-hall', {
      type: 'said',
      roomId: 'guild-hall',
      entityId: 'npc-1',
      entityName: 'Guild Master',
      text: 'Welcome, adventurer!',
      timestamp: Date.now(),
    });

    const proseEvents = events.filter(e => e.type === 'prose');
    expect(proseEvents.length).toBe(1);
    expect(proseEvents[0].type === 'prose' && proseEvents[0].speaker).toBe('Guild Master');
    expect(proseEvents[0].type === 'prose' && proseEvents[0].text).toBe('Welcome, adventurer!');
  });

  it('proxy forwards commands to remote room', () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    // Clear earlier published messages (presence etc.)
    between.published.length = 0;

    const proxy = node.visitRoom('guild-hall', 'host-node-2');
    proxy!.send({ type: 'say_in_room', entityId: 'p1', entityName: 'Alice', text: 'Hello guild!' });

    const cmdMessages = between.published.filter(
      m => m.subject === 'between.household-1.room.guild-hall.commands',
    );
    expect(cmdMessages.length).toBe(1);
    const cmd = JSON.parse(new TextDecoder().decode(cmdMessages[0].data));
    expect(cmd.type).toBe('say_in_room');
    expect(cmd.text).toBe('Hello guild!');
  });

  it('leaveVisitingRoom cleans up proxy', () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    node.visitRoom('guild-hall', 'host-node-2');
    expect(node.visitingRoom).not.toBeNull();

    const events: PhoneNodeEvent[] = [];
    node.onEvent(e => events.push(e));

    node.leaveVisitingRoom();
    expect(node.visitingRoom).toBeNull();

    // Should emit room_changed back to local room
    const roomChanged = events.find(e => e.type === 'room_changed');
    expect(roomChanged).toBeDefined();
  });

  it('visiting a second room cleans up the first', () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    const proxy1 = node.visitRoom('room-a', 'host-1');
    expect(node.visitingRoom).toBe(proxy1);

    const proxy2 = node.visitRoom('room-b', 'host-2');
    expect(node.visitingRoom).toBe(proxy2);
    expect(proxy2!.roomId).toBe('room-b');
  });

  it('entity_entered event from proxy emits narrator prose', () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    const events: PhoneNodeEvent[] = [];
    node.onEvent(e => events.push(e));

    node.visitRoom('guild-hall', 'host-node-2');

    simulateRemoteEvent(between, 'household-1', 'guild-hall', {
      type: 'entity_entered',
      roomId: 'guild-hall',
      entityId: 'npc-2',
      entityName: 'Merchant',
      entityType: 'npc',
      fromDirection: 'north',
      timestamp: Date.now(),
    });

    const proseEvents = events.filter(e => e.type === 'prose');
    expect(proseEvents.length).toBe(1);
    expect(proseEvents[0].type === 'prose' && proseEvents[0].text).toContain('Merchant enters from the north');
  });
});
