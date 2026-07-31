import { AsyncStorageEventJournal } from '../../../src/engine/persistence/AsyncStorageEventJournal';
import { InMemoryEventJournal } from '../../../src/engine/persistence/InMemoryEventJournal';
import type { WorldEvent } from '../../../src/engine/events/WorldEvent';

/** Minimal in-memory AsyncStorage mock. */
function createMockStorage() {
  const data = new Map<string, string>();
  return {
    getItem: async (key: string) => data.get(key) ?? null,
    setItem: async (key: string, value: string) => { data.set(key, value); },
    removeItem: async (key: string) => { data.delete(key); },
    _data: data,
  };
}

function makeSaidEvent(roomId: string, text: string, timestamp = Date.now()): WorldEvent {
  return {
    type: 'said',
    roomId,
    timestamp,
    entityId: 'p1',
    entityName: 'Alice',
    text,
  };
}

describe('AsyncStorageEventJournal compaction', () => {
  it('compact() reduces event count', async () => {
    const storage = createMockStorage();
    const journal = new AsyncStorageEventJournal(storage);
    const roomId = 'test-room';

    // Append 20 events
    for (let i = 0; i < 20; i++) {
      await journal.append(roomId, makeSaidEvent(roomId, `msg ${i}`, 1000 + i));
    }
    expect(await journal.getEventCount(roomId)).toBe(20);

    // Compact to keep only 5
    await journal.compact(roomId, 5);
    expect(await journal.getEventCount(roomId)).toBe(5);
  });

  it('compaction keeps the most recent events', async () => {
    const storage = createMockStorage();
    const journal = new AsyncStorageEventJournal(storage);
    const roomId = 'test-room';

    for (let i = 0; i < 10; i++) {
      await journal.append(roomId, makeSaidEvent(roomId, `msg ${i}`, 1000 + i));
    }

    await journal.compact(roomId, 3);
    const events = await journal.replay(roomId);
    expect(events).toHaveLength(3);
    // Should be the last 3 events (msg 7, msg 8, msg 9)
    expect((events[0] as any).text).toBe('msg 7');
    expect((events[1] as any).text).toBe('msg 8');
    expect((events[2] as any).text).toBe('msg 9');
  });

  it('compact() is a no-op when under threshold', async () => {
    const storage = createMockStorage();
    const journal = new AsyncStorageEventJournal(storage);
    const roomId = 'test-room';

    for (let i = 0; i < 5; i++) {
      await journal.append(roomId, makeSaidEvent(roomId, `msg ${i}`));
    }

    await journal.compact(roomId, 500);
    expect(await journal.getEventCount(roomId)).toBe(5);
  });

  it('auto-compaction triggers after 100 appends', async () => {
    const storage = createMockStorage();
    const journal = new AsyncStorageEventJournal(storage);
    const roomId = 'test-room';

    // Append 100 events — should trigger auto-compaction at the 100th
    for (let i = 0; i < 100; i++) {
      await journal.append(roomId, makeSaidEvent(roomId, `msg ${i}`, 1000 + i));
    }

    // After auto-compaction with default keepLast=500,
    // 100 events < 500, so no actual trimming yet
    const count = await journal.getEventCount(roomId);
    expect(count).toBe(100);

    // Now seed a room with 600 events total to test actual trimming
    // Append 500 more (total 600). At append 200 the auto-compact fires again.
    for (let i = 100; i < 600; i++) {
      await journal.append(roomId, makeSaidEvent(roomId, `msg ${i}`, 1000 + i));
    }

    // After auto-compaction at various thresholds, should be <= 500
    const finalCount = await journal.getEventCount(roomId);
    expect(finalCount).toBeLessThanOrEqual(500);
    expect(finalCount).toBeGreaterThan(0);
  });

  it('getEventCount returns 0 for unknown room', async () => {
    const storage = createMockStorage();
    const journal = new AsyncStorageEventJournal(storage);
    expect(await journal.getEventCount('nonexistent')).toBe(0);
  });
});

describe('InMemoryEventJournal compaction', () => {
  it('compact() reduces event count', async () => {
    const journal = new InMemoryEventJournal();
    const roomId = 'test-room';

    for (let i = 0; i < 20; i++) {
      await journal.append(roomId, makeSaidEvent(roomId, `msg ${i}`, 1000 + i));
    }
    expect(await journal.getEventCount(roomId)).toBe(20);

    await journal.compact(roomId, 5);
    expect(await journal.getEventCount(roomId)).toBe(5);
  });

  it('compaction keeps the most recent events', async () => {
    const journal = new InMemoryEventJournal();
    const roomId = 'test-room';

    for (let i = 0; i < 10; i++) {
      await journal.append(roomId, makeSaidEvent(roomId, `msg ${i}`, 1000 + i));
    }

    await journal.compact(roomId, 3);
    const events = await journal.replay(roomId);
    expect(events).toHaveLength(3);
    expect((events[0] as any).text).toBe('msg 7');
    expect((events[2] as any).text).toBe('msg 9');
  });

  it('getEventCount returns 0 for unknown room', async () => {
    const journal = new InMemoryEventJournal();
    expect(await journal.getEventCount('nonexistent')).toBe(0);
  });
});
