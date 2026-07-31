import { emptyRoomState, applyEvent, toSnapshot, RoomState } from '../../src/engine/room/RoomState';
import type { WorldEvent } from '../../src/engine/events/WorldEvent';

describe('RoomState', () => {
  const ts = Date.now();

  it('empty state has no entities, exits, or objects', () => {
    const state = emptyRoomState('test');
    expect(state.roomId).toBe('test');
    expect(state.name).toBe('');
    expect(Object.keys(state.entities)).toHaveLength(0);
    expect(Object.keys(state.exits)).toHaveLength(0);
    expect(Object.keys(state.objects)).toHaveLength(0);
  });

  it('applies room_created', () => {
    const state = applyEvent(emptyRoomState('test'), {
      type: 'room_created', roomId: 'test', timestamp: ts,
      name: 'The Nexus', description: 'A hub.', zone: 'foundation',
    });
    expect(state.name).toBe('The Nexus');
    expect(state.description).toBe('A hub.');
    expect(state.zone).toBe('foundation');
  });

  it('applies entity_entered and entity_left', () => {
    let state = emptyRoomState('test');
    state = applyEvent(state, {
      type: 'entity_entered', roomId: 'test', timestamp: ts,
      entityId: 'p1', entityName: 'Alice', entityType: 'player', fromDirection: 'south',
    });
    expect(state.entities['p1']).toBeDefined();
    expect(state.entities['p1'].name).toBe('Alice');

    state = applyEvent(state, {
      type: 'entity_left', roomId: 'test', timestamp: ts,
      entityId: 'p1', entityName: 'Alice', direction: 'north',
    });
    expect(state.entities['p1']).toBeUndefined();
  });

  it('applies object_added and object_taken', () => {
    let state = emptyRoomState('test');
    state = applyEvent(state, {
      type: 'object_added', roomId: 'test', timestamp: ts,
      objectId: 'o1', objectName: 'sword', description: 'A blade.', takeable: true,
    });
    expect(state.objects['o1']).toBeDefined();
    expect(state.objects['o1'].name).toBe('sword');
    expect(state.objects['o1'].takeable).toBe(true);

    state = applyEvent(state, {
      type: 'object_taken', roomId: 'test', timestamp: ts,
      entityId: 'p1', objectId: 'o1', objectName: 'sword',
    });
    expect(state.objects['o1']).toBeUndefined();
  });

  it('applies exit_opened and exit_closed', () => {
    let state = emptyRoomState('test');
    state = applyEvent(state, {
      type: 'exit_opened', roomId: 'test', timestamp: ts,
      direction: 'north', targetRoom: 'terminal', label: 'To Terminal',
    });
    expect(state.exits['north']).toBeDefined();
    expect(state.exits['north'].targetRoom).toBe('terminal');

    state = applyEvent(state, {
      type: 'exit_closed', roomId: 'test', timestamp: ts, direction: 'north',
    });
    expect(state.exits['north']).toBeUndefined();
  });

  it('applies description_changed', () => {
    let state = applyEvent(emptyRoomState('test'), {
      type: 'room_created', roomId: 'test', timestamp: ts,
      name: 'Room', description: 'Old desc.', zone: 'z',
    });
    state = applyEvent(state, {
      type: 'description_changed', roomId: 'test', timestamp: ts,
      newDescription: 'New desc.', reason: 'script',
    });
    expect(state.description).toBe('New desc.');
  });

  it('applies hints_updated', () => {
    let state = emptyRoomState('test');
    state = applyEvent(state, {
      type: 'hints_updated', roomId: 'test', timestamp: ts,
      hints: [{ label: 'Say hi', intent: 'greet', action: 'say' }],
    });
    expect(state.hints).toHaveLength(1);
    expect(state.hints[0].label).toBe('Say hi');
  });

  it('applies property_changed (set and remove)', () => {
    let state = emptyRoomState('test');
    state = applyEvent(state, {
      type: 'property_changed', roomId: 'test', timestamp: ts,
      key: 'mood', oldValue: null, newValue: 'happy',
    });
    expect(state.properties['mood']).toBe('happy');

    state = applyEvent(state, {
      type: 'property_changed', roomId: 'test', timestamp: ts,
      key: 'mood', oldValue: 'happy', newValue: null,
    });
    expect(state.properties['mood']).toBeUndefined();
  });

  it('said event does not change state', () => {
    const state = emptyRoomState('test');
    const after = applyEvent(state, {
      type: 'said', roomId: 'test', timestamp: ts,
      entityId: 'p1', entityName: 'Alice', text: 'Hello',
    });
    expect(after).toBe(state);
  });

  it('toSnapshot produces correct wire format', () => {
    let state = emptyRoomState('test');
    state = applyEvent(state, {
      type: 'room_created', roomId: 'test', timestamp: ts,
      name: 'Room', description: 'Desc.', zone: 'z',
    });
    state = applyEvent(state, {
      type: 'entity_entered', roomId: 'test', timestamp: ts,
      entityId: 'p1', entityName: 'Alice', entityType: 'player', fromDirection: 'south',
    });
    const snapshot = toSnapshot(state);
    expect(snapshot.roomId).toBe('test');
    expect(snapshot.name).toBe('Room');
    expect(snapshot.entities).toHaveLength(1);
    expect(snapshot.entities[0].name).toBe('Alice');
  });
});
