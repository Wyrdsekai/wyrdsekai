/**
 * Sleep sync protocol — full manifest + fragment exchange during rest.
 *
 * and.
 */

import type { BetweenClient } from './BetweenClient';

export interface Tombstone {
  itemHash: string;
  reason: string;
  createdBy: string;
  timestamp: number;
}

export interface SoulItemRef {
  hash: string;
  category: string;
  significance: number;
  createdBy: string;
  timestamp: number;
}

export interface SleepSyncRequest {
  budDid: string;
  nodeId: string;
  manifestVersion: number;
  localItemHashes: string[];
  localTombstones: Tombstone[];
  lastSyncTimestamp: number;
  timestamp: number;
}

export interface SleepSyncResponse {
  budDid: string;
  newItems: SoulItemRef[];
  newTombstones: Tombstone[];
  manifestUpdated: boolean;
  itemsMerged: number;
  tombstonesApplied: number;
  timestamp: number;
}

export type SyncResponseCallback = (response: SleepSyncResponse) => void;

export class SleepSyncManager {
  private syncResponseCallback: SyncResponseCallback | null = null;
  private unsubscribe: (() => void) | null = null;

  constructor(
    private readonly between: BetweenClient,
    private readonly nodeId: string,
    private readonly familyId: string,
  ) {}

  /** Register a callback for sync responses. */
  onSyncResponse(callback: SyncResponseCallback): void {
    this.syncResponseCallback = callback;
  }

  /** Start listening for sync responses directed to this node. */
  startListening(): void {
    const subject = this.syncResponseSubject(this.nodeId);
    this.unsubscribe = this.between.subscribe(subject, (_subject, data) => {
      try {
        const response = JSON.parse(new TextDecoder().decode(data)) as SleepSyncResponse;
        this.syncResponseCallback?.(response);
      } catch {
        // Malformed response — skip
      }
    });
  }

  /** Stop listening. */
  stopListening(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  /** Initiate a sleep sync — send local state to the household server. */
  requestSync(request: SleepSyncRequest): void {
    const data = new TextEncoder().encode(JSON.stringify(request));
    this.between.publish(this.syncRequestSubject(this.nodeId), data);
  }

  /** Build a sync request from current state. */
  buildRequest(params: {
    budDid: string;
    manifestVersion: number;
    localItemHashes: string[];
    localTombstones: Tombstone[];
    lastSyncTimestamp: number;
  }): SleepSyncRequest {
    return {
      ...params,
      nodeId: this.nodeId,
      timestamp: Date.now(),
    };
  }

  private syncRequestSubject(src: string): string {
    return `between.household.${this.familyId}.${src}.server.soul.sync.request`;
  }

  private syncResponseSubject(dst: string): string {
    return `between.household.${this.familyId}.server.${dst}.soul.sync.response`;
  }
}
