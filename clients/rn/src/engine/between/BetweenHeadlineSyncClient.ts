/**
 * HeadlineSyncClient backed by the Between network (NATS).
 * Posts headlines to NATS and receives headlines from siblings.
 *
 * Subject: "between.household.{familyId}.{nodeId}.soul.headlines"
 *
 * Falls back to local-only mode if NATS is not connected.
 *
 * and.
 */

import type { Headline, HeadlineCallback } from '../soul/HeadlineSyncClient';
import type { BetweenClient } from './BetweenClient';

export class BetweenHeadlineSyncClient {
  private headlines = new Map<string, Headline>();
  private listeners: HeadlineCallback[] = [];
  private unsubscribe: (() => void) | null = null;

  constructor(
    private readonly between: BetweenClient,
    private readonly nodeId: string,
    private readonly familyId: string,
  ) {}

  /** Start listening for headlines from the Between network. */
  startListening(): void {
    const subject = this.headlineSubject('*');
    this.unsubscribe = this.between.subscribe(subject, (_subject, data) => {
      try {
        const headline = JSON.parse(new TextDecoder().decode(data)) as Headline;
        // Don't echo our own headlines back
        if (headline.budDid !== this.nodeId) {
          this.receiveHeadline(headline);
        }
      } catch {
        // Malformed headline — skip
      }
    });
  }

  /** Stop listening for headlines. */
  stopListening(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  /** Post a headline from this bud. */
  postHeadline(headline: Headline): void {
    this.headlines.set(headline.budDid, headline);

    // Publish to Between if connected
    if (this.between.isConnected) {
      try {
        const data = new TextEncoder().encode(JSON.stringify(headline));
        this.between.publish(this.headlineSubject(this.nodeId), data);
      } catch {
        // Publish failure is non-fatal
      }
    }

    // Notify local listeners
    for (const listener of this.listeners) {
      listener(headline);
    }
  }

  /** Get latest headlines sorted by timestamp (newest first). */
  latestHeadlines(): Headline[] {
    return [...this.headlines.values()].sort((a, b) => b.timestamp - a.timestamp);
  }

  /** Subscribe to incoming headlines. Returns unsubscribe function. */
  onHeadlineReceived(callback: HeadlineCallback): () => void {
    this.listeners.push(callback);
    return () => {
      this.listeners = this.listeners.filter(l => l !== callback);
    };
  }

  private receiveHeadline(headline: Headline): void {
    this.headlines.set(headline.budDid, headline);
    for (const listener of this.listeners) {
      listener(headline);
    }
  }

  private headlineSubject(src: string): string {
    return `between.household.${this.familyId}.${src}.soul.headlines`;
  }
}
