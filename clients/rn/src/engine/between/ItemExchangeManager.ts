/**
 * Manages item exchange between companions via the Between network.
 *
 * Inbound items are quarantined (never go directly to inventory).
 * Quarantined items are reviewed during the Forge/sleep cycle.
 *
 * Subscribes to: `between.{householdId}.items.{myDid}.inbox`
 * Publishes to:  `between.{householdId}.items.{recipientDid}.inbox`
 *
 */

import type { BetweenClient } from './BetweenClient';

export interface ItemTransfer {
  fromDid: string;
  toDid: string;
  itemJson: unknown;
  message?: string;
  timestamp: number;
  signature?: string;
}

export class ItemExchangeManager {
  private quarantine: ItemTransfer[] = [];
  private unsubscribe: (() => void) | null = null;

  constructor(
    private readonly between: BetweenClient,
    private readonly myDid: string,
    private readonly householdId: string,
  ) {}

  /** Start listening for inbound items on this agent's inbox. */
  startListening(): void {
    const subject = `between.${this.householdId}.items.${this.myDid}.inbox`;
    this.unsubscribe = this.between.subscribe(subject, (_subject, data) => {
      try {
        const transfer = JSON.parse(new TextDecoder().decode(data)) as ItemTransfer;
        // All inbound items go to quarantine, never directly to inventory
        this.quarantine.push(transfer);
      } catch {
        // Malformed transfer — skip
      }
    });
  }

  /** Stop listening for inbound items. */
  stopListening(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  /**
   * Send an item to another agent.
   *
   * @param recipientDid The DID of the recipient agent
   * @param item The item to send
   * @param message Optional gift message
   */
  sendItem(recipientDid: string, item: unknown, message?: string): void {
    const transfer: ItemTransfer = {
      fromDid: this.myDid,
      toDid: recipientDid,
      itemJson: item,
      message,
      timestamp: Date.now(),
    };
    const data = new TextEncoder().encode(JSON.stringify(transfer));
    this.between.publish(`between.${this.householdId}.items.${recipientDid}.inbox`, data);
  }

  /** Get all quarantined inbound items (reviewed during Forge/sleep). */
  getQuarantinedItems(): ItemTransfer[] {
    return [...this.quarantine];
  }

  /** Clear quarantine after items have been reviewed by the Forge. */
  clearQuarantine(): void {
    this.quarantine = [];
  }

  /** Remove a specific item from quarantine (accepted or rejected by Forge). */
  removeFromQuarantine(transfer: ItemTransfer): void {
    const idx = this.quarantine.indexOf(transfer);
    if (idx >= 0) {
      this.quarantine.splice(idx, 1);
    }
  }
}
