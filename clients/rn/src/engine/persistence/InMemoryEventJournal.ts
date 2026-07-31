/**
 * In-memory EventJournal — no platform deps, useful for tests and mobile default.
 * TypeScript port of KMP's InMemoryEventJournal.kt.
 */

import type { WorldEvent } from '../events/WorldEvent';
import type { EventJournal } from './EventJournal';

export class InMemoryEventJournal implements EventJournal {
  private events = new Map<string, WorldEvent[]>();
  private snapshots = new Map<string, string>();

  async append(roomId: string, event: WorldEvent): Promise<void> {
    const list = this.events.get(roomId) ?? [];
    list.push(event);
    this.events.set(roomId, list);
  }

  async replay(roomId: string): Promise<WorldEvent[]> {
    return [...(this.events.get(roomId) ?? [])];
  }

  async saveSnapshot(roomId: string, snapshotJson: string): Promise<void> {
    this.snapshots.set(roomId, snapshotJson);
  }

  async loadSnapshot(roomId: string): Promise<string | null> {
    return this.snapshots.get(roomId) ?? null;
  }

  async compact(roomId: string, keepLast = 500): Promise<void> {
    const list = this.events.get(roomId);
    if (!list || list.length <= keepLast) return;
    this.events.set(roomId, list.slice(-keepLast));
  }

  async getEventCount(roomId: string): Promise<number> {
    return this.events.get(roomId)?.length ?? 0;
  }

  /** Synchronous event count — convenience for tests. */
  eventCount(roomId: string): number {
    return this.events.get(roomId)?.length ?? 0;
  }

  allEvents(roomId: string): WorldEvent[] {
    return [...(this.events.get(roomId) ?? [])];
  }

  clear(): void {
    this.events.clear();
    this.snapshots.clear();
  }
}
