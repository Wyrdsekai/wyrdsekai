import { InMemoryBetweenClient } from '../../../src/engine/between/BetweenClient';
import { VisitingRoomProxy } from '../../../src/engine/between/VisitingRoomProxy';
import type { WorldEvent } from '../../../src/engine/events/WorldEvent';

describe('VisitingRoomProxy', () => {
  it('subscribes to correct room events subject', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const proxy = new VisitingRoomProxy('terminal', between, 'household-1');
    proxy.startListening();

    // Simulate a remote room event
    const event: WorldEvent = {
      type: 'said',
      roomId: 'terminal',
      timestamp: Date.now(),
      entityId: 'npc-1',
      entityName: 'Guide',
      text: 'Welcome to the Terminal.',
    };
    const data = new TextEncoder().encode(JSON.stringify(event));
    between.publish('between.household-1.room.terminal.events', data);

    // State should exist (Said doesn't change state, but verify no crash)
    expect(proxy.state.roomId).toBe('terminal');

    proxy.shutdown();
  });

  it('received events update state', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const proxy = new VisitingRoomProxy('terminal', between, 'household-1');
    proxy.startListening();

    // Send a RoomCreated event
    const event: WorldEvent = {
      type: 'room_created',
      roomId: 'terminal',
      timestamp: Date.now(),
      name: 'The Terminal',
      description: 'Banks of crystalline screens line the walls.',
      zone: 'foundation',
    };
    const data = new TextEncoder().encode(JSON.stringify(event));
    between.publish('between.household-1.room.terminal.events', data);

    expect(proxy.state.name).toBe('The Terminal');
    expect(proxy.state.zone).toBe('foundation');

    proxy.shutdown();
  });

  it('received events emit notifications', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const proxy = new VisitingRoomProxy('terminal', between, 'household-1');
    proxy.startListening();

    const received: WorldEvent[] = [];
    proxy.onEvent(event => received.push(event));

    const event: WorldEvent = {
      type: 'said',
      roomId: 'terminal',
      timestamp: Date.now(),
      entityId: 'npc-1',
      entityName: 'Guide',
      text: 'Welcome!',
    };
    const data = new TextEncoder().encode(JSON.stringify(event));
    between.publish('between.household-1.room.terminal.events', data);

    expect(received.length).toBe(1);
    expect(received[0].type).toBe('said');
    expect((received[0] as any).text).toBe('Welcome!');

    proxy.shutdown();
  });

  it('send command publishes to correct subject', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const proxy = new VisitingRoomProxy('terminal', between, 'household-1');

    proxy.send({
      type: 'say_in_room',
      entityId: 'player-1',
      entityName: 'Traveler',
      text: 'Hello, Terminal!',
    });

    expect(between.published.length).toBe(1);
    expect(between.published[0].subject).toBe(
      'between.household-1.room.terminal.commands',
    );

    // Verify command payload
    const payload = JSON.parse(new TextDecoder().decode(between.published[0].data));
    expect(payload.entityName).toBe('Traveler');
    expect(payload.text).toBe('Hello, Terminal!');

    proxy.shutdown();
  });

  it('entity entered updates state', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const proxy = new VisitingRoomProxy('nexus', between, 'household-1');
    proxy.startListening();

    const event: WorldEvent = {
      type: 'entity_entered',
      roomId: 'nexus',
      timestamp: Date.now(),
      entityId: 'companion-1',
      entityName: 'Wyrd',
      entityType: 'companion',
      fromDirection: 'north',
    };
    const data = new TextEncoder().encode(JSON.stringify(event));
    between.publish('between.household-1.room.nexus.events', data);

    expect(proxy.state.entities['companion-1']).toBeDefined();
    expect(proxy.state.entities['companion-1'].name).toBe('Wyrd');

    proxy.shutdown();
  });
});
