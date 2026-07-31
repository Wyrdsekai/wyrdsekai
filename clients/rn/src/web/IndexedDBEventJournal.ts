/**
 * IndexedDB-backed event journal for web ephemeral nodes.
 * Persists room events and snapshots across browser sessions.
 * Falls back gracefully if IndexedDB is unavailable.
 */

import type { WorldEvent } from '../engine/events/WorldEvent';
import type { EventJournal } from '../engine/persistence/EventJournal';

const DB_NAME = 'wyrdsekai_engine';
const DB_VERSION = 1;
const EVENTS_STORE = 'events';
const SNAPSHOTS_STORE = 'snapshots';

export class IndexedDBEventJournal implements EventJournal {
  private db: IDBDatabase | null = null;

  async init(): Promise<void> {
    if (this.db) return;
    if (typeof indexedDB === 'undefined') return;

    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);

      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(EVENTS_STORE)) {
          db.createObjectStore(EVENTS_STORE);
        }
        if (!db.objectStoreNames.contains(SNAPSHOTS_STORE)) {
          db.createObjectStore(SNAPSHOTS_STORE);
        }
      };

      request.onsuccess = () => {
        this.db = request.result;
        resolve();
      };

      request.onerror = () => {
        console.warn('[IndexedDBEventJournal] IndexedDB not available');
        resolve();
      };
    });
  }

  async append(roomId: string, event: WorldEvent): Promise<void> {
    if (!this.db) return;
    try {
      const events = await this.getEvents(roomId);
      events.push(event);
      await this.putValue(EVENTS_STORE, roomId, events);
    } catch {
      // Non-fatal
    }
  }

  async replay(roomId: string): Promise<WorldEvent[]> {
    if (!this.db) return [];
    try {
      return await this.getEvents(roomId);
    } catch {
      return [];
    }
  }

  async saveSnapshot(roomId: string, snapshotJson: string): Promise<void> {
    if (!this.db) return;
    try {
      await this.putValue(SNAPSHOTS_STORE, roomId, snapshotJson);
    } catch {
      // Non-fatal
    }
  }

  async loadSnapshot(roomId: string): Promise<string | null> {
    if (!this.db) return null;
    try {
      return await this.getValue<string>(SNAPSHOTS_STORE, roomId);
    } catch {
      return null;
    }
  }

  async compact(roomId: string, keepLast: number = 500): Promise<void> {
    if (!this.db) return;
    try {
      const events = await this.getEvents(roomId);
      if (events.length > keepLast) {
        const trimmed = events.slice(events.length - keepLast);
        await this.putValue(EVENTS_STORE, roomId, trimmed);
      }
    } catch {
      // Non-fatal
    }
  }

  async getEventCount(roomId: string): Promise<number> {
    if (!this.db) return 0;
    try {
      const events = await this.getEvents(roomId);
      return events.length;
    } catch {
      return 0;
    }
  }

  private async getEvents(roomId: string): Promise<WorldEvent[]> {
    const data = await this.getValue<WorldEvent[]>(EVENTS_STORE, roomId);
    return data ?? [];
  }

  private getValue<T>(storeName: string, key: string): Promise<T | null> {
    return new Promise(resolve => {
      try {
        const tx = this.db!.transaction(storeName, 'readonly');
        const store = tx.objectStore(storeName);
        const request = store.get(key);
        request.onsuccess = () => resolve(request.result ?? null);
        request.onerror = () => resolve(null);
      } catch {
        resolve(null);
      }
    });
  }

  private putValue(storeName: string, key: string, value: unknown): Promise<void> {
    return new Promise(resolve => {
      try {
        const tx = this.db!.transaction(storeName, 'readwrite');
        const store = tx.objectStore(storeName);
        store.put(value, key);
        tx.oncomplete = () => resolve();
        tx.onerror = () => resolve();
      } catch {
        resolve();
      }
    });
  }
}
