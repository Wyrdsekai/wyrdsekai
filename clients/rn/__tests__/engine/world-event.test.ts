import type { WorldEvent, RoomCreated, Said, EntityEntered } from '../../src/engine/events/WorldEvent';

describe('WorldEvent', () => {
  it('creates a RoomCreated event', () => {
    const event: RoomCreated = {
      type: 'room_created', roomId: 'test', timestamp: Date.now(),
      name: 'Test Room', description: 'A test room.', zone: 'test-zone',
    };
    expect(event.type).toBe('room_created');
    expect(event.roomId).toBe('test');
    expect(event.name).toBe('Test Room');
  });

  it('creates a Said event', () => {
    const event: Said = {
      type: 'said', roomId: 'test', timestamp: Date.now(),
      entityId: 'p1', entityName: 'Alice', text: 'Hello!',
    };
    expect(event.type).toBe('said');
    expect(event.text).toBe('Hello!');
  });

  it('discriminates between event types', () => {
    const event: WorldEvent = {
      type: 'entity_entered', roomId: 'test', timestamp: Date.now(),
      entityId: 'p1', entityName: 'Alice', entityType: 'player', fromDirection: 'south',
    };
    if (event.type === 'entity_entered') {
      expect(event.entityName).toBe('Alice');
      expect(event.fromDirection).toBe('south');
    } else {
      fail('Expected entity_entered');
    }
  });

  it('all 15 event types are constructible', () => {
    const ts = Date.now();
    const events: WorldEvent[] = [
      { type: 'room_created', roomId: 'r', timestamp: ts, name: 'N', description: 'D', zone: 'Z' },
      { type: 'entity_entered', roomId: 'r', timestamp: ts, entityId: 'e', entityName: 'E', entityType: 'player', fromDirection: 'n' },
      { type: 'entity_left', roomId: 'r', timestamp: ts, entityId: 'e', entityName: 'E', direction: 'n' },
      { type: 'said', roomId: 'r', timestamp: ts, entityId: 'e', entityName: 'E', text: 'hi' },
      { type: 'object_taken', roomId: 'r', timestamp: ts, entityId: 'e', objectId: 'o', objectName: 'O' },
      { type: 'object_dropped', roomId: 'r', timestamp: ts, entityId: 'e', objectId: 'o', objectName: 'O', description: 'D', takeable: true },
      { type: 'object_used', roomId: 'r', timestamp: ts, entityId: 'e', objectId: 'o', objectName: 'O', target: null, result: null },
      { type: 'exit_opened', roomId: 'r', timestamp: ts, direction: 'n', targetRoom: 't', label: 'L' },
      { type: 'exit_closed', roomId: 'r', timestamp: ts, direction: 'n' },
      { type: 'description_changed', roomId: 'r', timestamp: ts, newDescription: 'New', reason: null },
      { type: 'hints_updated', roomId: 'r', timestamp: ts, hints: [] },
      { type: 'script_triggered', roomId: 'r', timestamp: ts, scriptName: 'S', trigger: 'T', context: {} },
      { type: 'object_added', roomId: 'r', timestamp: ts, objectId: 'o', objectName: 'O', description: 'D', takeable: false },
      { type: 'property_changed', roomId: 'r', timestamp: ts, key: 'k', oldValue: null, newValue: 'v' },
      { type: 'whispered', roomId: 'r', timestamp: ts, entityId: 'e1', entityName: 'E1', targetEntityId: 'e2', text: 'psst' },
    ];
    expect(events).toHaveLength(15);
    const types = new Set(events.map(e => e.type));
    expect(types.size).toBe(15);
  });
});
