import { InMemoryBetweenClient } from '../../../src/engine/between/BetweenClient';
import { PresenceManager, type PresenceState } from '../../../src/engine/between/PresenceManager';

describe('PresenceManager', () => {
  it('announce publishes to correct subject', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new PresenceManager(between, 'node-1', 'household-1');

    manager.announce('online');

    expect(between.published.length).toBe(1);
    expect(between.published[0].subject).toBe('between.household-1.presence.node-1');
  });

  it('announce updates local presence map', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new PresenceManager(between, 'node-1', 'household-1');

    manager.announce('online');

    const presence = manager.getHouseholdPresence();
    expect(presence.size).toBe(1);
    expect(presence.get('node-1')?.status).toBe('online');
  });

  it('subscribe receives presence from other agents', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new PresenceManager(between, 'node-1', 'household-1');
    manager.startListening();

    // Simulate another agent announcing presence
    const otherState: PresenceState = {
      nodeId: 'node-2',
      status: 'sleeping',
      timestamp: 1000,
    };
    const data = new TextEncoder().encode(JSON.stringify(otherState));
    between.publish('between.household-1.presence.node-2', data);

    const presence = manager.getHouseholdPresence();
    expect(presence.size).toBe(1);
    expect(presence.get('node-2')?.status).toBe('sleeping');

    manager.stopListening();
  });

  it('multiple agents tracked separately', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new PresenceManager(between, 'node-1', 'household-1');
    manager.startListening();

    // Announce own presence
    manager.announce('online');

    // Simulate two other agents
    for (let i = 2; i <= 3; i++) {
      const state: PresenceState = { nodeId: `node-${i}`, status: 'online', timestamp: 1000 };
      const data = new TextEncoder().encode(JSON.stringify(state));
      between.publish(`between.household-1.presence.node-${i}`, data);
    }

    const presence = manager.getHouseholdPresence();
    expect(presence.size).toBe(3);
    expect(presence.has('node-1')).toBe(true);
    expect(presence.has('node-2')).toBe(true);
    expect(presence.has('node-3')).toBe(true);

    manager.stopListening();
  });

  it('graceful when disconnected', async () => {
    const between = new InMemoryBetweenClient();
    // NOT connected
    const manager = new PresenceManager(between, 'node-1', 'household-1');

    // Should not throw
    manager.announce('online');

    // Still updates local map
    const presence = manager.getHouseholdPresence();
    expect(presence.size).toBe(1);
    // But nothing published
    expect(between.published.length).toBe(0);
  });

  it('presence state updated on repeat announce', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new PresenceManager(between, 'node-1', 'household-1');

    manager.announce('online');
    expect(manager.getHouseholdPresence().get('node-1')?.status).toBe('online');

    manager.announce('sleeping');
    expect(manager.getHouseholdPresence().get('node-1')?.status).toBe('sleeping');
  });
});
