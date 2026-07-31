import { PhoneNode, PhoneNodeEvent, ROOM_DEFINITIONS } from '../../src/engine/PhoneNode';
import type { ModelRole } from '../../src/inference/InferenceRouter';
import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';
import type { ResourceProbe, ResourceSnapshot, Tier } from '../../src/engine/tier/TierConfig';
import { TIER_ORDER } from '../../src/engine/tier/TierConfig';
import { TierManager } from '../../src/engine/tier/TierManager';
import type { ChatMessage, ChatResponse } from '../../src/inference/types';

const mockInference = {
  async complete(_role: ModelRole, _messages: ChatMessage[]): Promise<ChatResponse> {
    return { content: 'echo', promptTokens: 10, completionTokens: 5 };
  },
};

function makeProbe(overrides: Partial<ResourceSnapshot> = {}): ResourceProbe {
  return {
    snapshot: () => ({
      availableMemoryMb: 2000,
      totalMemoryMb: 4000,
      batteryPercent: 80,
      isCharging: false,
      thermalState: 'NOMINAL' as const,
      hasWifi: true,
      ...overrides,
    }),
  };
}

function makeNode(tierManager: TierManager | null = null): PhoneNode {
  return new PhoneNode(
    new InMemoryEventJournal(),
    null,
    mockInference,
    tierManager,
  );
}

describe('PhoneNode tier — room definitions', () => {
  it('roomsForTier are cumulative', () => {
    const node = makeNode();
    // T0/T1 now boot the Study (entry room) plus the Home room.
    expect(node.roomsForTier('T0')).toEqual(['study', 'home']);
    expect(node.roomsForTier('T1')).toEqual(['study', 'home']);
    expect(node.roomsForTier('T2')).toContain('home');
    expect(node.roomsForTier('T2')).toContain('terminal');
    expect(node.roomsForTier('T2').length).toBeGreaterThan(node.roomsForTier('T1').length);
    expect(node.roomsForTier('T3').length).toBeGreaterThan(node.roomsForTier('T2').length);
  });

  it('all room definitions exist', () => {
    const node = makeNode();
    const allRoomIds = new Set(TIER_ORDER.flatMap(t => node.roomsForTier(t)));
    for (const roomId of allRoomIds) {
      expect(ROOM_DEFINITIONS[roomId]).toBeDefined();
    }
  });

  it('room definitions have exits and descriptions', () => {
    for (const [roomId, def] of Object.entries(ROOM_DEFINITIONS)) {
      expect(def.name.length).toBeGreaterThan(0);
      expect(def.description.length).toBeGreaterThan(0);
      expect(def.zone.length).toBeGreaterThan(0);
      expect(def.exits.length).toBeGreaterThan(0);
    }
  });
});

describe('PhoneNode tier — boot', () => {
  it('boot at T1 creates study + home only', async () => {
    const probe = makeProbe({ availableMemoryMb: 1500, hasWifi: false });
    const tierManager = new TierManager(probe);
    const node = makeNode(tierManager);

    await node.startRoomsOnly();

    expect(node.state).toBe('running');
    expect(node.currentTier).toBe('T1');
    expect(node.activeRoomIds().has('home')).toBe(true);
    expect(node.activeRoomIds().has('terminal')).toBe(false);

    node.stop();
  });

  it('boot at T2 creates multiple rooms', async () => {
    const probe = makeProbe({ availableMemoryMb: 2500 });
    const tierManager = new TierManager(probe);
    const node = makeNode(tierManager);

    await node.startRoomsOnly();

    expect(node.currentTier).toBe('T2');
    expect(node.activeRoomIds().has('home')).toBe(true);
    expect(node.activeRoomIds().has('terminal')).toBe(true);
    expect(node.activeRoomIds().has('dream-chamber')).toBe(true);
    expect(node.activeRoomIds().has('mailroom')).toBe(true);

    node.stop();
  });

  it('boot without tier manager defaults to T1', async () => {
    const node = makeNode(null);

    await node.startRoomsOnly();

    expect(node.state).toBe('running');
    expect(node.currentTier).toBe('T1');
    expect(node.activeRoomIds().has('home')).toBe(true);

    node.stop();
  });
});

describe('PhoneNode tier — transitions', () => {
  it('promotion activates new rooms', async () => {
    const probe = makeProbe({ availableMemoryMb: 1500, hasWifi: false });
    const tierManager = new TierManager(probe);
    const node = makeNode(tierManager);

    await node.startRoomsOnly();
    expect(node.currentTier).toBe('T1');
    // T1 boots two rooms now: the Study and Home.
    expect(node.activeRoomIds().size).toBe(2);

    // Promote to T2
    tierManager.forceTier('T2');

    // Tier transition handler is synchronous (listener callback)
    // but bootRoom is async — wait for rooms to boot
    await delay(500);

    expect(node.currentTier).toBe('T2');
    expect(node.activeRoomIds().has('terminal')).toBe(true);
    expect(node.activeRoomIds().has('dream-chamber')).toBe(true);

    node.stop();
  });

  it('demotion passivates rooms', async () => {
    const probe = makeProbe({ availableMemoryMb: 2500 });
    const tierManager = new TierManager(probe);
    const node = makeNode(tierManager);

    await node.startRoomsOnly();
    expect(node.currentTier).toBe('T2');
    expect(node.activeRoomIds().size).toBeGreaterThanOrEqual(4);

    // Demote to T1
    tierManager.forceTier('T1');

    expect(node.currentTier).toBe('T1');
    expect(setEquals(node.activeRoomIds(), new Set(['study', 'home']))).toBe(true);
    expect(node.passivatedRoomIds().has('terminal')).toBe(true);
    expect(node.passivatedRoomIds().has('dream-chamber')).toBe(true);

    node.stop();
  });

  it('reactivation after promotion', async () => {
    const probe = makeProbe({ availableMemoryMb: 2500 });
    const tierManager = new TierManager(probe);
    const node = makeNode(tierManager);

    await node.startRoomsOnly();

    // Demote then re-promote
    tierManager.forceTier('T1');
    expect(node.passivatedRoomIds().has('terminal')).toBe(true);

    tierManager.forceTier('T2');
    await delay(500);

    expect(node.activeRoomIds().has('terminal')).toBe(true);
    expect(node.passivatedRoomIds().has('terminal')).toBe(false);

    node.stop();
  });

  it('tier changed event emitted', async () => {
    const probe = makeProbe({ availableMemoryMb: 1500, hasWifi: false });
    const tierManager = new TierManager(probe);
    const node = makeNode(tierManager);

    const events: PhoneNodeEvent[] = [];
    node.onEvent(e => events.push(e));

    await node.startRoomsOnly();

    tierManager.forceTier('T3');
    await delay(500);

    const tierEvents = events.filter(e => e.type === 'tier_changed');
    expect(tierEvents.length).toBeGreaterThan(0);
    const first = tierEvents[0];
    if (first.type === 'tier_changed') {
      expect(first.from).toBe('T1');
      expect(first.to).toBe('T3');
    }

    node.stop();
  });
});

// Helpers

function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function setEquals<T>(a: Set<T>, b: Set<T>): boolean {
  if (a.size !== b.size) return false;
  for (const item of a) {
    if (!b.has(item)) return false;
  }
  return true;
}
