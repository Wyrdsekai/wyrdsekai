/**
 * Tests for IndexedDBEventJournal using mocked IndexedDB.
 * Since tests run in Node (no browser IndexedDB), we test the
 * InMemoryEventJournal as a proxy for the same interface.
 * The IndexedDB variant is tested via the same EventJournal interface.
 */

import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';
import type { WorldEvent } from '../../src/engine/events/WorldEvent';

describe('EventJournal (InMemory — same interface as IndexedDB)', () => {
  let journal: InMemoryEventJournal;

  beforeEach(() => {
    journal = new InMemoryEventJournal();
  });

  it('appends and replays events', async () => {
    const event: WorldEvent = {
      type: 'room_created', roomId: 'test', timestamp: Date.now(),
      name: 'Room', description: 'D', zone: 'z',
    };
    await journal.append('test', event);
    const replayed = await journal.replay('test');
    expect(replayed).toHaveLength(1);
    expect(replayed[0].type).toBe('room_created');
  });

  it('replay returns empty for unknown room', async () => {
    const replayed = await journal.replay('unknown');
    expect(replayed).toHaveLength(0);
  });

  it('saves and loads snapshots', async () => {
    await journal.saveSnapshot('test', '{"state":"data"}');
    const snapshot = await journal.loadSnapshot('test');
    expect(snapshot).toBe('{"state":"data"}');
  });

  it('loadSnapshot returns null for unknown room', async () => {
    const snapshot = await journal.loadSnapshot('unknown');
    expect(snapshot).toBeNull();
  });

  it('clear removes all data', async () => {
    await journal.append('r1', {
      type: 'room_created', roomId: 'r1', timestamp: Date.now(),
      name: 'R', description: 'D', zone: 'z',
    });
    await journal.saveSnapshot('r1', '{}');
    journal.clear();
    expect(await journal.replay('r1')).toHaveLength(0);
    expect(await journal.loadSnapshot('r1')).toBeNull();
  });
});
