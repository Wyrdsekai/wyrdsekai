/**
 * Abstract transport for the Between network.
 * Implementations connect to NATS (via NatsClient) or other transports.
 *
 */

export type BetweenMessageHandler = (subject: string, data: Uint8Array) => void;

/** The server refused a subscription (a permissions violation); it is gone. */
export interface BetweenSubscribeError {
  subject: string;
  message: string;
}

export interface BetweenClient {
  readonly isConnected: boolean;
  connect(url: string): Promise<void>;
  disconnect(): Promise<void>;
  publish(subject: string, data: Uint8Array): void;
  /** `onError`: called if the server refuses the subscription (clients that can tell). */
  subscribe(
    subject: string,
    handler: BetweenMessageHandler,
    onError?: (err: BetweenSubscribeError) => void,
  ): () => void;
}

/**
 * In-memory Between client for testing.
 * Messages published are delivered synchronously to local subscribers.
 */
export class InMemoryBetweenClient implements BetweenClient {
  private _connected = false;
  private subscriptions: Array<{ subject: string; handler: BetweenMessageHandler }> = [];

  /** All messages published (for test assertions). */
  published: Array<{ subject: string; data: Uint8Array }> = [];

  get isConnected(): boolean {
    return this._connected;
  }

  async connect(_url: string): Promise<void> {
    this._connected = true;
  }

  async disconnect(): Promise<void> {
    this._connected = false;
  }

  publish(subject: string, data: Uint8Array): void {
    this.published.push({ subject, data });
    for (const sub of this.subscriptions) {
      if (this.subjectMatches(sub.subject, subject)) {
        sub.handler(subject, data);
      }
    }
  }

  subscribe(subject: string, handler: BetweenMessageHandler): () => void {
    const entry = { subject, handler };
    this.subscriptions.push(entry);
    return () => {
      this.subscriptions = this.subscriptions.filter(s => s !== entry);
    };
  }

  /** Simple wildcard matching: * matches one token. */
  private subjectMatches(pattern: string, subject: string): boolean {
    const pParts = pattern.split('.');
    const sParts = subject.split('.');
    for (let i = 0; i < pParts.length; i++) {
      if (pParts[i] === '>') return true;
      if (i >= sParts.length) return false;
      if (pParts[i] !== '*' && pParts[i] !== sParts[i]) return false;
    }
    return pParts.length === sParts.length;
  }
}
