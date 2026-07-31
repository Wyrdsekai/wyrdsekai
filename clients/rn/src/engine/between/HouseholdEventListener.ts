/**
 * Subscribes to household-wide events on `between.{householdId}.events`.
 *
 * These are low-volume, significant events:
 * - New agent arrived in the household
 * - Agent departed or went dormant
 * - Steward announcements
 * - Safety alerts
 * - Configuration changes
 *
 */

import type { BetweenClient } from './BetweenClient';

export type HouseholdEvent =
  | AgentArrived
  | AgentDeparted
  | StewardAnnouncement
  | SafetyAlert
  | ConfigChanged;

export interface AgentArrived {
  type: 'agent_arrived';
  agentDid: string;
  agentName: string;
  timestamp: number;
}

export interface AgentDeparted {
  type: 'agent_departed';
  agentDid: string;
  agentName: string;
  reason?: string;
  timestamp: number;
}

export interface StewardAnnouncement {
  type: 'steward_announcement';
  stewardDid: string;
  message: string;
  timestamp: number;
}

export interface SafetyAlert {
  type: 'safety_alert';
  severity: string;
  message: string;
  sourceDid?: string;
  timestamp: number;
}

export interface ConfigChanged {
  type: 'config_changed';
  key: string;
  oldValue?: string;
  newValue?: string;
  timestamp: number;
}

export type HouseholdEventCallback = (event: HouseholdEvent) => void;

export class HouseholdEventListener {
  private unsubscribe: (() => void) | null = null;

  constructor(
    private readonly between: BetweenClient,
    private readonly householdId: string,
    private readonly onEvent: HouseholdEventCallback,
  ) {}

  /** Start listening for household events. */
  startListening(): void {
    const subject = `between.${this.householdId}.events`;
    this.unsubscribe = this.between.subscribe(subject, (_subject, data) => {
      try {
        const event = JSON.parse(new TextDecoder().decode(data)) as HouseholdEvent;
        this.onEvent(event);
      } catch {
        // Malformed household event — skip
      }
    });
  }

  /** Stop listening for household events. */
  stopListening(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
  }
}
