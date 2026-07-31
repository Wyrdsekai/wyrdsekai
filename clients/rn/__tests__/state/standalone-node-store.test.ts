import { useStandaloneNodeStore } from '../../src/state/standaloneNodeStore';
import type { RoomSnapshot } from '../../src/protocol/models';

describe('standaloneNodeStore', () => {
  beforeEach(() => {
    useStandaloneNodeStore.getState().reset();
  });

  it('starts in stopped state', () => {
    const state = useStandaloneNodeStore.getState();
    expect(state.nodeState).toBe('stopped');
    expect(state.nodeError).toBeNull();
    expect(state.proseStream).toEqual([]);
    expect(state.roomName).toBe('');
    expect(state.companionState).toBe('off');
  });

  it('sets node state', () => {
    useStandaloneNodeStore.getState().setNodeState('running');
    expect(useStandaloneNodeStore.getState().nodeState).toBe('running');
  });

  it('sets node error', () => {
    useStandaloneNodeStore.getState().setNodeError('something broke');
    expect(useStandaloneNodeStore.getState().nodeError).toBe('something broke');
  });

  it('applies room snapshot', () => {
    const snapshot: RoomSnapshot = {
      roomId: 'nexus',
      name: 'The Nexus',
      description: 'A crystalline chamber.',
      zone: 'foundation',
      exits: [{ direction: 'north', targetRoom: 'terminal', label: 'North' }],
      entities: [{ id: 'p1', name: 'Alice', type: 'player', description: '' }],
      objects: [{ id: 'obj-1', name: 'crystal', description: 'Shiny', takeable: false }],
      hints: [],
    };
    useStandaloneNodeStore.getState().applyRoomSnapshot(snapshot);

    const state = useStandaloneNodeStore.getState();
    expect(state.roomName).toBe('The Nexus');
    expect(state.roomDescription).toBe('A crystalline chamber.');
    expect(state.exits).toHaveLength(1);
    expect(state.exits[0].direction).toBe('north');
    expect(state.entities).toHaveLength(1);
    expect(state.objects).toHaveLength(1);
  });

  it('adds prose entries', () => {
    const { addProse } = useStandaloneNodeStore.getState();
    addProse({ speaker: 'narrator', text: 'Welcome' });
    addProse({ speaker: 'Wyrd', text: 'Hello there' });

    const stream = useStandaloneNodeStore.getState().proseStream;
    expect(stream).toHaveLength(2);
    expect(stream[0].speaker).toBe('narrator');
    expect(stream[1].text).toBe('Hello there');
  });

  it('clears prose', () => {
    useStandaloneNodeStore.getState().addProse({ speaker: 'test', text: 'hi' });
    expect(useStandaloneNodeStore.getState().proseStream).toHaveLength(1);

    useStandaloneNodeStore.getState().clearProse();
    expect(useStandaloneNodeStore.getState().proseStream).toEqual([]);
  });

  it('sets companion state', () => {
    useStandaloneNodeStore.getState().setCompanionState('thinking');
    expect(useStandaloneNodeStore.getState().companionState).toBe('thinking');
  });

  it('sets current tier', () => {
    useStandaloneNodeStore.getState().setCurrentTier('T2');
    expect(useStandaloneNodeStore.getState().currentTier).toBe('T2');
  });

  it('reset returns to initial state', () => {
    const store = useStandaloneNodeStore.getState();
    store.setNodeState('running');
    store.addProse({ speaker: 'test', text: 'data' });
    store.setCompanionState('idle');

    store.reset();
    const after = useStandaloneNodeStore.getState();
    expect(after.nodeState).toBe('stopped');
    expect(after.proseStream).toEqual([]);
    expect(after.companionState).toBe('off');
  });
});
