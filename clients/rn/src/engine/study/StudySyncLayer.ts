/**
 * Between sync layer for Study items.
 * TypeScript port of KMP's StudySyncLayer.kt.
 *
 * Follows advertisement → delta request → delta response protocol
 * from.
 */
import type { BetweenClient } from '../between/BetweenClient';
import type { StudyItem } from './StudyItem';
import type { StudyStore } from './StudyStore';
import { compare, type ClockMap } from './VectorClock';

export type SyncEvent =
  | { type: 'items_merged'; count: number }
  | { type: 'conflicts_detected'; count: number };

interface StudySyncMessage {
  type: 'study_state' | 'study_delta_request' | 'study_delta';
  deviceId: string;
  /** Whose Study this message is about. The server hosts many users, so every
   *  message must name its owner; peers ignore messages for a different user.
   *  Optional on the wire for back-compat with pre-userDid peers. */
  userDid?: string;
  /** Auth token proving the sender speaks for userDid (session or pairing token).
   *  The server peer DROPS unauthenticated messages. */
  token?: string;
  itemCount?: number;
  latestModified?: number;
  clockSummary?: ClockMap;
  items?: StudyItem[];
  conflicts?: number;
}

export class StudySyncLayer {
  private unsubState: (() => void) | null = null;
  private unsubSync: (() => void) | null = null;
  private listeners: ((event: SyncEvent) => void)[] = [];

  constructor(
    private readonly between: BetweenClient,
    private readonly store: StudyStore,
    private readonly deviceId: string,
    private readonly householdId: string,
    private readonly userDid: string,
    /** Session (mcp.login) or device pairing token proving we speak for userDid —
     * the server peer drops unauthenticated study messages. */
    private readonly authToken?: string | null,
  ) {}

  startListening(): void {
    // State advertisements from all peers
    this.unsubState = this.between.subscribe(
      this.stateSubject('*'),
      (_subject, data) => {
        try {
          const msg: StudySyncMessage = JSON.parse(new TextDecoder().decode(data));
          if (msg.deviceId !== this.deviceId) this.handlePeerMessage(msg);
        } catch { /* skip */ }
      },
    );

    // Directed sync messages to this device
    this.unsubSync = this.between.subscribe(
      this.syncSubject('*', this.deviceId),
      (_subject, data) => {
        try {
          const msg: StudySyncMessage = JSON.parse(new TextDecoder().decode(data));
          if (msg.deviceId !== this.deviceId) this.handlePeerMessage(msg);
        } catch { /* skip */ }
      },
    );
  }

  stopListening(): void {
    this.unsubState?.();
    this.unsubSync?.();
    this.unsubState = null;
    this.unsubSync = null;
  }

  onSyncEvent(callback: (event: SyncEvent) => void): void {
    this.listeners.push(callback);
  }

  async broadcastState(): Promise<void> {
    if (!this.between.isConnected) return;
    const count = await this.store.count(this.userDid);
    const recent = await this.store.recentJournal(this.userDid, 1);
    const latestTs = recent[0]?.timestamp ?? 0;
    const clockSummary = await this.buildClockSummary();

    const msg: StudySyncMessage = {
      type: 'study_state',
      deviceId: this.deviceId,
      userDid: this.userDid,
      token: this.authToken ?? undefined,
      itemCount: count,
      latestModified: latestTs,
      clockSummary,
    };
    try {
      this.between.publish(
        this.stateSubject(this.deviceId),
        new TextEncoder().encode(JSON.stringify(msg)),
      );
    } catch { /* non-fatal */ }
  }

  async requestDelta(peerDeviceId: string): Promise<void> {
    if (!this.between.isConnected) return;
    const clockSummary = await this.buildClockSummary();
    const msg: StudySyncMessage = {
      type: 'study_delta_request',
      deviceId: this.deviceId,
      userDid: this.userDid,
      token: this.authToken ?? undefined,
      clockSummary,
    };
    try {
      this.between.publish(
        this.syncSubject(this.deviceId, peerDeviceId),
        new TextEncoder().encode(JSON.stringify(msg)),
      );
    } catch { /* non-fatal */ }
  }

  // ── Internal ───────────────────────────────────────────────────────

  private async handlePeerMessage(msg: StudySyncMessage): Promise<void> {
    // Scope by owner — a household can hold more than one user; ignore traffic
    // for anyone but us. (Absent userDid = a legacy peer; allow it.)
    if (msg.userDid && msg.userDid !== this.userDid) return;
    switch (msg.type) {
      case 'study_state': {
        const ourClock = await this.buildClockSummary();
        const theirClock = msg.clockSummary ?? {};
        const behind = Object.entries(theirClock).some(
          ([k, v]) => (ourClock[k] ?? 0) < v,
        );
        if (behind) await this.requestDelta(msg.deviceId);
        break;
      }
      case 'study_delta_request':
        await this.sendDelta(msg.deviceId, msg.clockSummary ?? {});
        break;
      case 'study_delta': {
        const merged = await this.mergeIncoming(msg.items ?? []);
        if (merged > 0) {
          for (const l of this.listeners) l({ type: 'items_merged', count: merged });
        }
        if ((msg.conflicts ?? 0) > 0) {
          for (const l of this.listeners) l({ type: 'conflicts_detected', count: msg.conflicts! });
        }
        break;
      }
    }
  }

  private async sendDelta(peerDeviceId: string, peerClock: ClockMap): Promise<void> {
    if (!this.between.isConnected) return;
    const all = await this.store.recentJournal(this.userDid, 1000);
    const delta = all.filter((item) => {
      const relation = compare(item.vectorClock ?? {}, peerClock);
      return relation === 'dominates' || relation === 'concurrent';
    });
    if (delta.length === 0) return;

    const msg: StudySyncMessage = {
      type: 'study_delta',
      deviceId: this.deviceId,
      userDid: this.userDid,
      token: this.authToken ?? undefined,
      items: delta,
    };
    try {
      this.between.publish(
        this.syncSubject(this.deviceId, peerDeviceId),
        new TextEncoder().encode(JSON.stringify(msg)),
      );
    } catch { /* non-fatal */ }
  }

  private async mergeIncoming(items: StudyItem[]): Promise<number> {
    let merged = 0;
    for (const remote of items) {
      const local = await this.store.getItem(remote.id);
      if (!local) {
        // #5 (2026-07-19) — a new item this device has never seen: persist it.
        // This branch used to count merged++ without ever storing, silently
        // dropping every synced-in entry. A tombstone we never had is a no-op.
        if (!remote.deleted) {
          await this.store.putItem(remote);
          merged++;
        }
        continue;
      }
      const relation = compare(remote.vectorClock ?? {}, local.vectorClock ?? {});
      switch (relation) {
        case 'dominates':
          if (remote.deleted) {
            await this.store.deleteItem(local.id);
          } else {
            await this.store.editItem(local.id, remote.content);
          }
          merged++;
          break;
        case 'concurrent':
          for (const l of this.listeners) l({ type: 'conflicts_detected', count: 1 });
          break;
        // dominated or equal: nothing to do
      }
    }
    return merged;
  }

  private async buildClockSummary(): Promise<ClockMap> {
    const all = await this.store.recentJournal(this.userDid, 1000);
    const summary: ClockMap = {};
    for (const item of all) {
      for (const [device, version] of Object.entries(item.vectorClock ?? {})) {
        summary[device] = Math.max(summary[device] ?? 0, version);
      }
    }
    return summary;
  }

  private stateSubject(src: string): string {
    return `between.${this.householdId}.${src}.*.study.state`;
  }

  private syncSubject(src: string, dst: string): string {
    return `between.${this.householdId}.${src}.${dst}.study.sync`;
  }
}
