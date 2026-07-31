/**
 * AsyncStorage-backed event journal for React Native (Android/iOS).
 * Persists room events and snapshots across app restarts.
 *
 * Uses @react-native-async-storage/async-storage (standard RN persistence).
 * Falls back gracefully if AsyncStorage is unavailable (e.g., web).
 *
 * Auto-compacts after every 100 appends per room to prevent unbounded growth.
 */

import type { WorldEvent } from '../events/WorldEvent';
import type { EventJournal } from './EventJournal';

const EVENT_PREFIX = '@wyrd_events:';
const SNAPSHOT_PREFIX = '@wyrd_snapshot:';

/** Default number of events to keep during compaction. */
const DEFAULT_KEEP_LAST = 500;

/** Number of appends between auto-compaction checks. */
const AUTO_COMPACT_THRESHOLD = 100;

/** Minimal AsyncStorage interface — avoids hard import dependency. */
interface AsyncStorageLike {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
}

export class AsyncStorageEventJournal implements EventJournal {
  private appendCounts = new Map<string, number>();

  constructor(private readonly storage: AsyncStorageLike) {}

  async append(roomId: string, event: WorldEvent): Promise<void> {
    const key = EVENT_PREFIX + roomId;
    try {
      const existing = await this.storage.getItem(key);
      const events: WorldEvent[] = existing ? JSON.parse(existing) : [];
      events.push(event);
      await this.storage.setItem(key, JSON.stringify(events));

      // Track appends for auto-compaction
      const count = (this.appendCounts.get(roomId) ?? 0) + 1;
      this.appendCounts.set(roomId, count);
      if (count >= AUTO_COMPACT_THRESHOLD) {
        this.appendCounts.set(roomId, 0);
        await this.compact(roomId);
      }
    } catch {
      // Storage failure is non-fatal — events stay in memory via InMemoryEventJournal
    }
  }

  async replay(roomId: string): Promise<WorldEvent[]> {
    const key = EVENT_PREFIX + roomId;
    try {
      const data = await this.storage.getItem(key);
      if (!data) return [];
      return JSON.parse(data) as WorldEvent[];
    } catch {
      return [];
    }
  }

  async saveSnapshot(roomId: string, snapshotJson: string): Promise<void> {
    try {
      await this.storage.setItem(SNAPSHOT_PREFIX + roomId, snapshotJson);
    } catch {
      // Non-fatal
    }
  }

  async loadSnapshot(roomId: string): Promise<string | null> {
    try {
      return await this.storage.getItem(SNAPSHOT_PREFIX + roomId);
    } catch {
      return null;
    }
  }

  async compact(roomId: string, keepLast = DEFAULT_KEEP_LAST): Promise<void> {
    const key = EVENT_PREFIX + roomId;
    try {
      const data = await this.storage.getItem(key);
      if (!data) return;
      const events: WorldEvent[] = JSON.parse(data);
      if (events.length <= keepLast) return;
      const trimmed = events.slice(-keepLast);
      await this.storage.setItem(key, JSON.stringify(trimmed));
    } catch {
      // Non-fatal
    }
  }

  async getEventCount(roomId: string): Promise<number> {
    const key = EVENT_PREFIX + roomId;
    try {
      const data = await this.storage.getItem(key);
      if (!data) return 0;
      return (JSON.parse(data) as WorldEvent[]).length;
    } catch {
      return 0;
    }
  }
}
