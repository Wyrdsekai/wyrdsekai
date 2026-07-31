/**
 * Warm handoff protocol for device switching (~2s transfer).
 *
 * and.
 */

import type { BetweenClient } from './BetweenClient';

export interface ConversationTurn {
  role: string;
  content: string;
  timestamp: number;
}

export interface WarmHandoffContext {
  fromDid: string;
  toDid: string;
  activeRoomId: string;
  openConversationDids: string[];
  recentTurns: ConversationTurn[];
  vitalitySnapshot: Record<string, number>;
  currentTask: string | null;
  timestamp: number;
}

export type HandoffCallback = (context: WarmHandoffContext) => void;

export class WarmHandoffManager {
  private handoffCallback: HandoffCallback | null = null;
  private unsubscribe: (() => void) | null = null;

  constructor(
    private readonly between: BetweenClient,
    private readonly nodeId: string,
    private readonly familyId: string,
  ) {}

  /** Register a callback for incoming warm handoff. */
  onHandoffReceived(callback: HandoffCallback): void {
    this.handoffCallback = callback;
  }

  /** Start listening for incoming handoffs directed to this node. */
  startListening(): void {
    const subject = this.handoffSubject('*', this.nodeId);
    this.unsubscribe = this.between.subscribe(subject, (_subject, data) => {
      try {
        const context = JSON.parse(new TextDecoder().decode(data)) as WarmHandoffContext;
        this.handoffCallback?.(context);
      } catch {
        // Malformed handoff — skip
      }
    });
  }

  /** Stop listening. */
  stopListening(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  /** Initiate a warm handoff to a target node. */
  sendHandoff(context: WarmHandoffContext, targetNodeId: string): void {
    const data = new TextEncoder().encode(JSON.stringify(context));
    this.between.publish(this.handoffSubject(this.nodeId, targetNodeId), data);
  }

  private handoffSubject(src: string, dst: string): string {
    return `between.household.${this.familyId}.${src}.${dst}.soul.handoff`;
  }
}
