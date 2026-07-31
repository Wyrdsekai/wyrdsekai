/**
 * Headline sync client for soul buds (§95).
 *
 * Buds continuously exchange lightweight "headline" summaries (~200B)
 * so family members stay loosely coordinated without full sync.
 * On phones, this runs over the Between (NATS) or degrades to polling.
 *
 * Headlines are ephemeral — only the latest per bud is retained.
 */

export interface Headline {
  budDid: string;
  summary: string;
  vitalitySnapshot: Record<string, number>;
  itemCount: number;
  timestamp: number;
}

export type HeadlineCallback = (headline: Headline) => void;

export class HeadlineSyncClient {
  private headlines = new Map<string, Headline>();
  private listeners: HeadlineCallback[] = [];

  /**
   * Post a headline from this bud. Stores locally and notifies listeners.
   * In production, this would also publish to the Between (NATS topic).
   */
  postHeadline(headline: Headline): void {
    this.headlines.set(headline.budDid, headline);
    for (const listener of this.listeners) {
      listener(headline);
    }
  }

  /**
   * Receive a headline from a remote bud (e.g., via Between/NATS subscription).
   * Stores and notifies listeners.
   */
  receiveHeadline(headline: Headline): void {
    this.headlines.set(headline.budDid, headline);
    for (const listener of this.listeners) {
      listener(headline);
    }
  }

  /**
   * Get the latest headlines from all known buds, sorted by timestamp (newest first).
   */
  latestHeadlines(): Headline[] {
    return [...this.headlines.values()].sort((a, b) => b.timestamp - a.timestamp);
  }

  /**
   * Get the latest headline for a specific bud DID.
   */
  headlineFor(budDid: string): Headline | null {
    return this.headlines.get(budDid) ?? null;
  }

  /**
   * Subscribe to incoming headlines. Returns unsubscribe function.
   */
  onHeadlineReceived(callback: HeadlineCallback): () => void {
    this.listeners.push(callback);
    return () => {
      this.listeners = this.listeners.filter(l => l !== callback);
    };
  }

  /**
   * Clear all stored headlines.
   */
  clear(): void {
    this.headlines.clear();
  }
}
