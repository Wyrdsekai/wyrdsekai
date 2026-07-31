import { RoomEngine } from '../../src/engine/room/RoomEngine';
import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';

describe('RoomEngine', () => {
  let journal: InMemoryEventJournal;
  let engine: RoomEngine;

  beforeEach(() => {
    journal = new InMemoryEventJournal();
    engine = new RoomEngine('test', journal);
  });

  afterEach(() => {
    engine.shutdown();
  });

  it('creates a room and sets state', async () => {
    const result = await engine.send({
      type: 'create_room', name: 'Test Room', description: 'A test room.', zone: 'test',
    });
    expect(result.type).toBe('ok');
    expect(engine.state.name).toBe('Test Room');
    expect(engine.state.description).toBe('A test room.');
    expect(journal.eventCount('test')).toBeGreaterThan(0);
  });

  it('enter and leave room', async () => {
    await engine.send({ type: 'create_room', name: 'Room', description: 'D', zone: 'z' });
    await engine.send({
      type: 'enter_room', entityId: 'p1', entityName: 'Alice', entityType: 'player', fromDirection: 'south',
    });
    expect(engine.state.entities['p1']).toBeDefined();

    await engine.send({ type: 'leave_room', entityId: 'p1', entityName: 'Alice', direction: 'north' });
    expect(engine.state.entities['p1']).toBeUndefined();
  });

  it('rejects taking non-existent object', async () => {
    await engine.send({ type: 'create_room', name: 'Room', description: 'D', zone: 'z' });
    const result = await engine.send({ type: 'take_object', entityId: 'p1', objectName: 'ghost' });
    expect(result.type).toBe('rejected');
    if (result.type === 'rejected') {
      expect(result.code).toBe('not_found');
    }
  });

  it('rejects taking untakeable object', async () => {
    await engine.send({
      type: 'create_room', name: 'Room', description: 'D', zone: 'z',
      objects: [{ id: 'o1', name: 'pedestal', description: 'A stone pedestal.', takeable: false }],
    });
    const result = await engine.send({ type: 'take_object', entityId: 'p1', objectName: 'pedestal' });
    expect(result.type).toBe('rejected');
    if (result.type === 'rejected') {
      expect(result.code).toBe('not_takeable');
    }
  });

  it('takes a takeable object', async () => {
    await engine.send({
      type: 'create_room', name: 'Room', description: 'D', zone: 'z',
      objects: [{ id: 'o1', name: 'sword', description: 'A blade.', takeable: true }],
    });
    const result = await engine.send({ type: 'take_object', entityId: 'p1', objectName: 'sword' });
    expect(result.type).toBe('ok');
    expect(Object.keys(engine.state.objects)).toHaveLength(0);
  });

  it('say in room generates said event', async () => {
    await engine.send({ type: 'create_room', name: 'Room', description: 'D', zone: 'z' });
    await engine.send({ type: 'say_in_room', entityId: 'p1', entityName: 'Alice', text: 'Hello' });
    const events = journal.allEvents('test');
    expect(events.some(e => e.type === 'said')).toBe(true);
  });

  it('events are persisted to journal', async () => {
    await engine.send({ type: 'create_room', name: 'Room', description: 'D', zone: 'z' });
    await engine.send({
      type: 'enter_room', entityId: 'p1', entityName: 'Alice', entityType: 'player', fromDirection: 'south',
    });
    await engine.send({ type: 'say_in_room', entityId: 'p1', entityName: 'Alice', text: 'Hello' });

    const replayed = journal.allEvents('test');
    expect(replayed[0].type).toBe('room_created');
    expect(replayed[1].type).toBe('entity_entered');
    expect(replayed[2].type).toBe('said');
  });

  it('emits events to listeners', async () => {
    const events: any[] = [];
    engine.onEvent(e => events.push(e));

    await engine.send({ type: 'create_room', name: 'Room', description: 'D', zone: 'z' });
    await engine.send({ type: 'say_in_room', entityId: 'p1', entityName: 'Alice', text: 'Hello' });

    expect(events.some(e => e.type === 'room_created')).toBe(true);
    expect(events.some(e => e.type === 'said')).toBe(true);
  });
});
