/**
 * Manages presence state for the phone node within the household Between network.
 *
 * Publishes this node's presence to `between.{householdId}.presence.{nodeId}`
 * and subscribes to `between.{householdId}.presence.>` to track all agents.
 *
 */

import type { BetweenClient } from './BetweenClient';

export interface PresenceState {
  nodeId: string;
  status: string; // online, offline, sleeping, away
  tier?: string;
  timestamp: number;
}

export class PresenceManager {
  private presenceMap = new Map<string, PresenceState>();
  private unsubscribe: (() => void) | null = null;

  constructor(
    private readonly between: BetweenClient,
    private readonly nodeId: string,
    private readonly householdId: string,
  ) {}

  /** Start listening for presence announcements from all household agents. */
  startListening(): void {
    const subject = `between.${this.householdId}.presence.>`;
    this.unsubscribe = this.between.subscribe(subject, (_subject, data) => {
      try {
        const state = JSON.parse(new TextDecoder().decode(data)) as PresenceState;
        this.presenceMap.set(state.nodeId, state);
      } catch {
        // Malformed presence — skip
      }
    });
  }

  /** Stop listening for presence announcements. */
  stopListening(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  /**
   * Announce this node's presence status.
   *
   * @param status One of: "online", "offline", "sleeping", "away"
   */
  announce(status: string): void {
    const state: PresenceState = {
      nodeId: this.nodeId,
      status,
      timestamp: Date.now(),
    };
    this.presenceMap.set(this.nodeId, state);

    if (this.between.isConnected) {
      try {
        const data = new TextEncoder().encode(JSON.stringify(state));
        this.between.publish(`between.${this.householdId}.presence.${this.nodeId}`, data);
      } catch {
        // Publish failure is non-fatal
      }
    }
  }

  /** Get the current presence state for all known household agents. */
  getHouseholdPresence(): Map<string, PresenceState> {
    return new Map(this.presenceMap);
  }
}
