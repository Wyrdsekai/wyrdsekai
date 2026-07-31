/**
 * Event journal for room event sourcing.
 * TypeScript port of KMP's EventJournal.kt.
 */

import type { WorldEvent } from '../events/WorldEvent';

export interface EventJournal {
  append(roomId: string, event: WorldEvent): Promise<void>;
  replay(roomId: string): Promise<WorldEvent[]>;
  saveSnapshot(roomId: string, snapshotJson: string): Promise<void>;
  loadSnapshot(roomId: string): Promise<string | null>;

  /**
   * Compact events for a room, keeping only the most recent N events.
   * @param roomId  Room to compact.
   * @param keepLast  Number of events to retain (default 500).
   */
  compact(roomId: string, keepLast?: number): Promise<void>;

  /**
   * Return the number of persisted events for a room (diagnostics).
   */
  getEventCount(roomId: string): Promise<number>;
}
