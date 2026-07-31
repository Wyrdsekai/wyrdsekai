/**
 * Three-tier conversation buffer for room memory management.
 * TypeScript port of KMP's RoomMemoryPolicy.kt.
 *
 * Hot: last N messages (full detail, high priority for prompts)
 * Warm: next M messages (available but lower priority)
 * Compacted: oldest messages, summarized or dropped
 */

import type { Said } from '../events/WorldEvent';

export class RoomMemoryPolicy {
  private hotEvents: Said[] = [];
  private warmEvents: Said[] = [];

  constructor(
    private readonly hotSize: number = 10,
    private readonly warmSize: number = 20,
  ) {}

  add(event: Said): void {
    this.hotEvents.push(event);
    // Cascade: hot overflows to warm, warm overflows to compacted (dropped)
    while (this.hotEvents.length > this.hotSize) {
      const overflow = this.hotEvents.shift()!;
      this.warmEvents.push(overflow);
    }
    while (this.warmEvents.length > this.warmSize) {
      this.warmEvents.shift(); // compacted = dropped for phone node
    }
  }

  /** Recent messages for prompt assembly. */
  getHotEvents(): Said[] {
    return [...this.hotEvents];
  }

  /** Older messages available for expanded context. */
  getWarmEvents(): Said[] {
    return [...this.warmEvents];
  }

  /** All retained messages (hot + warm). */
  getAllEvents(): Said[] {
    return [...this.warmEvents, ...this.hotEvents];
  }

  clear(): void {
    this.hotEvents = [];
    this.warmEvents = [];
  }

  static default(): RoomMemoryPolicy {
    return new RoomMemoryPolicy();
  }
}
