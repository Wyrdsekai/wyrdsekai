import { InMemoryBetweenClient } from '../../src/engine/between/BetweenClient';
import { WarmHandoffManager, type WarmHandoffContext } from '../../src/engine/between/WarmHandoff';

describe('WarmHandoffManager', () => {
  it('sendHandoff publishes to Between', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new WarmHandoffManager(between, 'node-1', 'family-1');

    const context: WarmHandoffContext = {
      fromDid: 'bud-1',
      toDid: 'bud-2',
      activeRoomId: 'nexus',
      openConversationDids: ['bud-2'],
      recentTurns: [
        { role: 'user', content: 'Hello', timestamp: 1000 },
        { role: 'assistant', content: 'Hi there!', timestamp: 1001 },
      ],
      vitalitySnapshot: { energy: 0.8, confidence: 0.7 },
      currentTask: 'exploring',
      timestamp: 2000,
    };

    manager.sendHandoff(context, 'node-2');

    expect(between.published.length).toBe(1);
    const { subject } = between.published[0];
    expect(subject).toContain('family-1');
    expect(subject).toContain('node-1');
    expect(subject).toContain('node-2');
    expect(subject).toContain('soul.handoff');
  });

  it('receive handoff triggers callback', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new WarmHandoffManager(between, 'node-2', 'family-1');

    let received: WarmHandoffContext | null = null;
    manager.onHandoffReceived(ctx => { received = ctx; });
    manager.startListening();

    const context: WarmHandoffContext = {
      fromDid: 'bud-1',
      toDid: 'bud-2',
      activeRoomId: 'terminal',
      openConversationDids: [],
      recentTurns: [],
      vitalitySnapshot: {},
      currentTask: null,
      timestamp: 3000,
    };
    const data = new TextEncoder().encode(JSON.stringify(context));
    between.publish('between.household.family-1.node-1.node-2.soul.handoff', data);

    expect(received).not.toBeNull();
    expect(received!.activeRoomId).toBe('terminal');
    expect(received!.fromDid).toBe('bud-1');

    manager.stopListening();
  });

  it('handoff subject matches pattern', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new WarmHandoffManager(between, 'node-1', 'family-abc');

    const context: WarmHandoffContext = {
      fromDid: 'bud-1', toDid: 'bud-2',
      activeRoomId: 'nexus', openConversationDids: [],
      recentTurns: [], vitalitySnapshot: {},
      currentTask: null, timestamp: 100,
    };
    manager.sendHandoff(context, 'node-2');

    expect(between.published[0].subject).toBe(
      'between.household.family-abc.node-1.node-2.soul.handoff',
    );
  });
});
