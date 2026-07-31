/**
 * Adapts NatsClient (web) or NativeNatsClient (native) to the BetweenClient
 * interface.
 *
 * Used by standalone mode to connect to the household Between network.
 *
 * ## Platform strategy
 *
 * - **Web (Expo Web / react-native-web)**: Uses nats.ws via dynamic import
 *   of NatsClient. The browser provides native WebSocket with full binary
 *   frame support.
 *
 * - **Android / iOS (native RN)**: Uses NativeNatsClient, which speaks the
 *   raw NATS text protocol over React Native's global WebSocket polyfill.
 *   This avoids nats.ws binary frame issues (binaryType='arraybuffer' is
 *   unreliable in the RN polyfill). Mirrors the KMP NatsBetweenClient
 *   approach.
 *
 * Platform detection uses `Platform.OS` from react-native. If the import
 * fails (e.g., in a test environment without react-native), the adapter
 * falls back to trying nats.ws first, then NativeNatsClient.
 */
import type { BetweenClient, BetweenMessageHandler } from './BetweenClient';
import { NativeNatsClient } from './NativeNatsClient';

type NatsClientType = import('../../web/NatsClient').NatsClient;

/**
 * Detect whether we're running on a native RN platform (iOS/Android)
 * vs web. Returns 'native' or 'web'. Falls back to 'web' if Platform
 * is unavailable (e.g., test environment).
 */
function detectPlatform(): 'native' | 'web' {
  try {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const { Platform } = require('react-native');
    if (Platform && Platform.OS && Platform.OS !== 'web') {
      return 'native';
    }
  } catch {
    // react-native not available (test environment or web bundle)
  }
  return 'web';
}

export class NatsBetweenAdapter implements BetweenClient {
  private natsClient: NatsClientType | null = null;
  private nativeClient: NativeNatsClient | null = null;
  private _connected = false;

  /** Visible for testing — override platform detection. */
  _forcePlatform: 'native' | 'web' | null = null;

  get isConnected(): boolean {
    return this._connected;
  }

  async connect(url: string, creds?: { user?: string; pass?: string }): Promise<void> {
    const platform = this._forcePlatform ?? detectPlatform();

    if (platform === 'native') {
      await this.connectNative(url, creds);
    } else {
      await this.connectWeb(url, creds);
    }
  }

  /**
   * Connect using NativeNatsClient (raw NATS text protocol).
   * Used on iOS/Android where nats.ws has binary frame issues.
   */
  private async connectNative(url: string, creds?: { user?: string; pass?: string }): Promise<void> {
    try {
      const client = new NativeNatsClient();
      this.nativeClient = client;
      await client.connect(url, creds);
      this._connected = true;
    } catch (e) {
      this._connected = false;
      this.nativeClient = null;
      throw new Error(
        `NATS native connection failed: ${
          e instanceof Error ? e.message : String(e)
        }`,
      );
    }
  }

  /**
   * Connect using nats.ws (NatsClient).
   * Used on web where the browser provides full WebSocket support.
   */
  private async connectWeb(url: string, creds?: { user?: string; pass?: string }): Promise<void> {
    try {
      // Dynamic import to avoid pulling nats.ws into native bundle
      const { NatsClient } = await import('../../web/NatsClient');
      this.natsClient = new NatsClient();
      await this.natsClient.connect(url, creds);
      this._connected = true;
    } catch (e) {
      this._connected = false;
      this.natsClient = null;
      throw new Error(
        `NATS connection failed (may not be available on this platform): ${
          e instanceof Error ? e.message : String(e)
        }`,
      );
    }
  }

  async disconnect(): Promise<void> {
    if (this.natsClient) {
      await this.natsClient.disconnect();
      this.natsClient = null;
    }
    if (this.nativeClient) {
      await this.nativeClient.disconnect();
      this.nativeClient = null;
    }
    this._connected = false;
  }

  publish(subject: string, data: Uint8Array): void {
    if (this.nativeClient) {
      this.nativeClient.publish(subject, data);
      return;
    }
    if (this.natsClient) {
      this.natsClient.publish(subject, data);
      return;
    }
    throw new Error('Not connected');
  }

  subscribe(subject: string, handler: BetweenMessageHandler): () => void {
    if (this.nativeClient) {
      return this.nativeClient.subscribe(subject, handler);
    }
    if (this.natsClient) {
      return this.natsClient.subscribe(subject, handler);
    }
    throw new Error('Not connected');
  }
}
