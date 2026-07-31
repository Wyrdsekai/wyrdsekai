/**
 * StandaloneNodeStore persistence tests.
 *
 * Tests the persistState/restoreState round-trip, prose restoration,
 * room state restoration, and fresh start behavior.
 */

import { useStandaloneNodeStore, PERSIST_KEY } from '../../src/state/standaloneNodeStore';
import type { PersistedStandaloneState } from '../../src/state/standaloneNodeStore';
import type { RoomSnapshot } from '../../src/protocol/models';
import { createMockAsyncStorage } from '../helpers/mockAsyncStorage';

describe('standaloneNodeStore persistence', () => {
  const storage = createMockAsyncStorage();

  beforeEach(() => {
    useStandaloneNodeStore.getState().reset();
    storage.clear();
  });

  // ── persistState ──

  it('persists current state to AsyncStorage', async () => {
    const store = useStandaloneNodeStore.getState();
    store.applyRoomSnapshot(makeSnapshot('nexus', 'The Nexus', 'A hub.'));
    store.addProse({ speaker: 'narrator', text: 'Welcome' });
    store.addProse({ speaker: 'Wyrd', text: 'Hello' });
    store.setCurrentTier('T2');
    store.setCompanionState('idle');

    await useStandaloneNodeStore.getState().persistState(storage);

    const raw = await storage.getItem(PERSIST_KEY);
    expect(raw).not.toBeNull();

    const persisted: PersistedStandaloneState = JSON.parse(raw!);
    expect(persisted.roomId).toBe('nexus');
    expect(persisted.roomName).toBe('The Nexus');
    expect(persisted.currentTier).toBe('T2');
    expect(persisted.companionState).toBe('idle');
    expect(persisted.proseStream).toHaveLength(2);
    expect(persisted.proseStream[0].text).toBe('Welcome');
    expect(persisted.proseStream[1].text).toBe('Hello');
  });

  it('truncates prose to last 50 entries when persisting', async () => {
    const store = useStandaloneNodeStore.getState();
    // Add 70 prose entries
    for (let i = 0; i < 70; i++) {
      store.addProse({ speaker: 'test', text: `entry-${i}` });
    }
    expect(useStandaloneNodeStore.getState().proseStream).toHaveLength(70);

    await useStandaloneNodeStore.getState().persistState(storage);

    const raw = await storage.getItem(PERSIST_KEY);
    const persisted: PersistedStandaloneState = JSON.parse(raw!);
    expect(persisted.proseStream).toHaveLength(50);
    // Should keep the LAST 50 (entries 20-69)
    expect(persisted.proseStream[0].text).toBe('entry-20');
    expect(persisted.proseStream[49].text).toBe('entry-69');
  });

  it('persists exits from room snapshot', async () => {
    const store = useStandaloneNodeStore.getState();
    store.applyRoomSnapshot({
      roomId: 'nexus',
      name: 'The Nexus',
      description: 'A hub.',
      zone: 'foundation',
      exits: [
        { direction: 'north', targetRoom: 'terminal', label: 'North' },
        { direction: 'east', targetRoom: 'dream-chamber', label: 'East' },
      ],
      entities: [],
      objects: [],
      hints: [],
    });

    await useStandaloneNodeStore.getState().persistState(storage);

    const raw = await storage.getItem(PERSIST_KEY);
    const persisted: PersistedStandaloneState = JSON.parse(raw!);
    expect(persisted.exits).toHaveLength(2);
    expect(persisted.exits[0].direction).toBe('north');
    expect(persisted.exits[1].direction).toBe('east');
  });

  // ── restoreState ──

  it('restores state from AsyncStorage', async () => {
    // Persist some state
    const store = useStandaloneNodeStore.getState();
    store.applyRoomSnapshot(makeSnapshot('terminal', 'The Terminal', 'Screens.'));
    store.addProse({ speaker: 'narrator', text: 'You enter the terminal.' });
    store.setCurrentTier('T2');
    store.setCompanionState('thinking');

    await useStandaloneNodeStore.getState().persistState(storage);

    // Reset store to simulate fresh mount
    useStandaloneNodeStore.getState().reset();
    expect(useStandaloneNodeStore.getState().roomName).toBe('');

    // Restore
    const restored = await useStandaloneNodeStore.getState().restoreState(storage);

    expect(restored).not.toBeNull();
    expect(restored!.roomId).toBe('terminal');

    // Store should be updated immediately
    const state = useStandaloneNodeStore.getState();
    expect(state.roomId).toBe('terminal');
    expect(state.roomName).toBe('The Terminal');
    expect(state.roomDescription).toBe('Screens.');
    expect(state.currentTier).toBe('T2');
    expect(state.companionState).toBe('thinking');
    expect(state.proseStream).toHaveLength(1);
    expect(state.proseStream[0].text).toBe('You enter the terminal.');
  });

  it('restores prose entries in order', async () => {
    const store = useStandaloneNodeStore.getState();
    store.addProse({ speaker: 'narrator', text: 'First' });
    store.addProse({ speaker: 'Wyrd', text: 'Second' });
    store.addProse({ speaker: 'player', text: 'Third' });

    await useStandaloneNodeStore.getState().persistState(storage);
    useStandaloneNodeStore.getState().reset();

    await useStandaloneNodeStore.getState().restoreState(storage);

    const prose = useStandaloneNodeStore.getState().proseStream;
    expect(prose).toHaveLength(3);
    expect(prose[0]).toEqual({ speaker: 'narrator', text: 'First' });
    expect(prose[1]).toEqual({ speaker: 'Wyrd', text: 'Second' });
    expect(prose[2]).toEqual({ speaker: 'player', text: 'Third' });
  });

  it('returns null on fresh start (no saved state)', async () => {
    const restored = await useStandaloneNodeStore.getState().restoreState(storage);
    expect(restored).toBeNull();

    // Store should remain at initial values
    const state = useStandaloneNodeStore.getState();
    expect(state.roomName).toBe('');
    expect(state.proseStream).toEqual([]);
    expect(state.currentTier).toBe('T1');
  });

  it('handles corrupted JSON gracefully', async () => {
    await storage.setItem(PERSIST_KEY, 'not valid json{{{');

    const restored = await useStandaloneNodeStore.getState().restoreState(storage);
    expect(restored).toBeNull();

    // Should have cleared the corrupted data
    const raw = await storage.getItem(PERSIST_KEY);
    expect(raw).toBeNull();
  });

  // ── Round-trip ──

  it('persist then restore is identity', async () => {
    const store = useStandaloneNodeStore.getState();
    store.applyRoomSnapshot({
      roomId: 'dream-chamber',
      name: 'The Dream Chamber',
      description: 'Twilight room.',
      zone: 'kokoro',
      exits: [{ direction: 'west', targetRoom: 'nexus', label: 'Back' }],
      entities: [{ id: 'e1', name: 'Wyrd', type: 'agent', description: 'Companion' }],
      objects: [{ id: 'obj-1', name: 'dreambed', description: 'A bed', takeable: false }],
      hints: [],
    });
    store.addProse({ speaker: 'narrator', text: 'You rest.' });
    store.setCurrentTier('T3');
    store.setCompanionState('idle');

    await useStandaloneNodeStore.getState().persistState(storage);
    useStandaloneNodeStore.getState().reset();
    await useStandaloneNodeStore.getState().restoreState(storage);

    const state = useStandaloneNodeStore.getState();
    expect(state.roomId).toBe('dream-chamber');
    expect(state.roomName).toBe('The Dream Chamber');
    expect(state.roomDescription).toBe('Twilight room.');
    expect(state.exits).toHaveLength(1);
    expect(state.exits[0].direction).toBe('west');
    expect(state.currentTier).toBe('T3');
    expect(state.companionState).toBe('idle');
    expect(state.proseStream).toHaveLength(1);
  });

  it('new prose after restore appends to restored entries', async () => {
    const store = useStandaloneNodeStore.getState();
    store.addProse({ speaker: 'narrator', text: 'Before persist' });

    await useStandaloneNodeStore.getState().persistState(storage);
    useStandaloneNodeStore.getState().reset();
    await useStandaloneNodeStore.getState().restoreState(storage);

    // Add new prose after restore
    useStandaloneNodeStore.getState().addProse({ speaker: 'narrator', text: 'After restore' });

    const prose = useStandaloneNodeStore.getState().proseStream;
    expect(prose).toHaveLength(2);
    expect(prose[0].text).toBe('Before persist');
    expect(prose[1].text).toBe('After restore');
  });

  it('does not persist nodeState or nodeError (transient)', async () => {
    const store = useStandaloneNodeStore.getState();
    store.setNodeState('running');
    store.setNodeError('some error');

    await useStandaloneNodeStore.getState().persistState(storage);

    const raw = await storage.getItem(PERSIST_KEY);
    const persisted = JSON.parse(raw!);
    // nodeState and nodeError should NOT be in persisted data
    expect(persisted.nodeState).toBeUndefined();
    expect(persisted.nodeError).toBeUndefined();
  });
});

// ── Helpers ──

function makeSnapshot(roomId: string, name: string, description: string): RoomSnapshot {
  return {
    roomId,
    name,
    description,
    zone: 'foundation',
    exits: [],
    entities: [],
    objects: [],
    hints: [],
  };
}
