/**
 * NatsClient — NATS WebSocket client for Between participation.
 *
 * Connects to household NATS via WebSocket. Used by web ephemeral nodes
 * to participate in the Between network (subscribe to room events,
 * publish commands).
 *
 * Uses nats.ws (NATS WebSocket client library).
 */

export type NatsConnectionState = 'disconnected' | 'connecting' | 'connected' | 'error';
export type NatsMessageHandler = (subject: string, data: Uint8Array) => void;

/** Minimal NATS connection interface matching nats.ws shape. */
interface NatsConnection {
  subscribe(subject: string): AsyncIterable<{ subject: string; data: Uint8Array }>;
  publish(subject: string, data: Uint8Array): void;
  drain(): Promise<void>;
  close(): Promise<void>;
}

export class NatsClient {
  private connection: NatsConnection | null = null;
  private _state: NatsConnectionState = 'disconnected';
  private subscriptions: Array<{ subject: string; handler: NatsMessageHandler }> = [];
  private stateListeners: Array<(state: NatsConnectionState) => void> = [];

  get state(): NatsConnectionState {
    return this._state;
  }

  onStateChange(listener: (state: NatsConnectionState) => void): () => void {
    this.stateListeners.push(listener);
    return () => {
      this.stateListeners = this.stateListeners.filter(l => l !== listener);
    };
  }

  private setState(state: NatsConnectionState): void {
    this._state = state;
    for (const listener of this.stateListeners) {
      listener(state);
    }
  }

  /**
   * Connect to a NATS server via WebSocket.
   * @param url WebSocket URL, e.g. 'ws://198.51.100.100:9222'
   */
  async connect(url: string, creds?: { user?: string; pass?: string }): Promise<void> {
    if (this._state === 'connected') return;
    this.setState('connecting');

    try {
      // Dynamic import to avoid bundling nats.ws on non-web platforms
      const nats = await import('nats.ws' as string);
      // pass relay_phone creds when syncing over the relay.
      const opts = creds?.user
        ? { servers: url, user: creds.user, pass: creds.pass ?? '' }
        : { servers: url };
      this.connection = await nats.connect(opts) as unknown as NatsConnection;
      this.setState('connected');

      // Re-subscribe to any subjects registered before connection
      for (const sub of this.subscriptions) {
        this.startSubscription(sub.subject, sub.handler);
      }
    } catch (e) {
      this.setState('error');
      throw e;
    }
  }

  /**
   * Subscribe to a NATS subject.
   * If not yet connected, the subscription is queued and starts on connect.
   */
  subscribe(subject: string, handler: NatsMessageHandler): () => void {
    const sub = { subject, handler };
    this.subscriptions.push(sub);

    if (this.connection) {
      this.startSubscription(subject, handler);
    }

    return () => {
      this.subscriptions = this.subscriptions.filter(s => s !== sub);
    };
  }

  /** Publish a message to a NATS subject. */
  publish(subject: string, data: Uint8Array): void {
    if (!this.connection) {
      throw new Error('Not connected to NATS');
    }
    this.connection.publish(subject, data);
  }

  /** Publish a JSON message to a NATS subject. */
  publishJson(subject: string, message: unknown): void {
    const data = new TextEncoder().encode(JSON.stringify(message));
    this.publish(subject, data);
  }

  /** Gracefully disconnect from NATS. */
  async disconnect(): Promise<void> {
    if (this.connection) {
      try {
        await this.connection.drain();
      } catch {
        await this.connection.close();
      }
      this.connection = null;
    }
    this.setState('disconnected');
  }

  private async startSubscription(subject: string, handler: NatsMessageHandler): Promise<void> {
    if (!this.connection) return;

    try {
      const sub = this.connection.subscribe(subject);
      // Process messages asynchronously
      (async () => {
        for await (const msg of sub) {
          handler(msg.subject, msg.data);
        }
      })().catch(() => {
        // Subscription ended (disconnect or error)
      });
    } catch {
      // Failed to subscribe
    }
  }
}
