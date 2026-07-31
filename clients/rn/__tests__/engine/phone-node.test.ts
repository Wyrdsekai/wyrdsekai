import { PhoneNode, PhoneNodeEvent } from '../../src/engine/PhoneNode';
import type { ModelRole } from '../../src/inference/InferenceRouter';
import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';
import { InMemoryVitalityStore } from '../../src/engine/persistence/InMemoryVitalityStore';
import { TierManager } from '../../src/engine/tier/TierManager';
import type { ChatMessage, ChatResponse } from '../../src/inference/types';

/** Mock inference client that echoes back the trigger. */
const mockInference = {
  async complete(_role: ModelRole, messages: ChatMessage[]): Promise<ChatResponse> {
    const lastUser = messages.filter(m => m.role === 'user').pop();
    return {
      content: `Echo: ${lastUser?.content ?? 'silence'}`,
      promptTokens: 10,
      completionTokens: 5,
    };
  },
};

/** T2 probe — gives enough resources for nexus + terminal + more */
const t2Probe = {
  snapshot: () => ({
    availableMemoryMb: 2500,
    totalMemoryMb: 4000,
    batteryPercent: 80,
    isCharging: false,
    thermalState: 'NOMINAL' as const,
    hasWifi: true,
  }),
};

describe('PhoneNode', () => {
  let node: PhoneNode;

  beforeEach(() => {
    // Use T2 TierManager so both nexus and terminal are booted
    node = new PhoneNode(
      new InMemoryEventJournal(),
      new InMemoryVitalityStore(),
      mockInference,
      new TierManager(t2Probe),
    );
  });

  afterEach(() => {
    node.stop();
  });

  it('starts and boots rooms', async () => {
    await node.start();
    expect(node.state).toBe('running');
    expect(node.nexusRoom).not.toBeNull();
    expect(node.terminalRoom).not.toBeNull();
    // The legacy "Nexus" room is now the "Home" room; nexusRoom is a back-compat
    // alias that returns the Home RoomEngine.
    expect(node.nexusRoom!.state.name).toBe('Home');
    expect(node.terminalRoom!.state.name).toBe('The Terminal');
  });

  it('say dispatches to current room', async () => {
    await node.start();
    await node.say('p1', 'Alice', 'Hello');
    // Verify the event was recorded
    const room = node.currentRoom();
    expect(room).not.toBeNull();
  });

  it('go changes room', async () => {
    await node.start();
    // The phone boots into the Study. Walk north into the Home room first;
    // Home's north exit leads to The Terminal.
    await node.go('p1', 'Alice', 'north');

    const events: PhoneNodeEvent[] = [];
    node.onEvent(e => events.push(e));

    await node.go('p1', 'Alice', 'north');
    expect(events.some(e => e.type === 'room_changed')).toBe(true);
    const roomChanged = events.find(e => e.type === 'room_changed');
    if (roomChanged?.type === 'room_changed') {
      expect(roomChanged.snapshot.name).toBe('The Terminal');
    }
  });

  it('look returns snapshot', async () => {
    await node.start();
    const snapshot = node.look();
    expect(snapshot).not.toBeNull();
    // Boot room is now The Study (the player's home base on the phone).
    expect(snapshot!.name).toBe('The Study');
  });

  it('stop cleans up', async () => {
    await node.start();
    node.stop();
    expect(node.state).toBe('stopped');
    expect(node.nexusRoom).toBeNull();
    expect(node.terminalRoom).toBeNull();
    expect(node.companion).toBeNull();
  });
});
