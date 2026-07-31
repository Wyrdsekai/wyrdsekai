import type { ServerConnection } from './ServerConnection';
import type { C2SMessage } from '../../protocol/c2s';
import type { S2CMessage } from '../../protocol/s2c';

/**
 * WebSocket-based ServerConnection for visiting rooms on the household server.
 * Connects to the Wyrdsekai server's /ws endpoint and proxies C2S/S2C messages.
 */
export class WebSocketServerConnection implements ServerConnection {
  private ws: WebSocket | null = null;
  private handlers: Array<(msg: S2CMessage) => void> = [];
  private _isConnected = false;
  private readonly wsUrl: string;
  private pingInterval: ReturnType<typeof setInterval> | null = null;

  constructor(wsUrl: string) {
    this.wsUrl = wsUrl;
  }

  get isConnected(): boolean {
    return this._isConnected;
  }

  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      try {
        this.ws = new WebSocket(this.wsUrl);

        this.ws.onopen = () => {
          this._isConnected = true;
          // Keepalive ping every 30s (server has 5min idle timeout)
          this.pingInterval = setInterval(() => {
            try { this.ws?.send('{"type":"ping"}'); } catch {}
          }, 30_000);
          resolve();
        };

        this.ws.onmessage = (event) => {
          try {
            const msg: S2CMessage = JSON.parse(event.data);
            for (const handler of this.handlers) {
              handler(msg);
            }
          } catch {
            // Malformed message — skip
          }
        };

        this.ws.onerror = () => {
          if (!this._isConnected) {
            reject(new Error('WebSocket connection failed'));
          }
        };

        this.ws.onclose = () => {
          this._isConnected = false;
        };

        // Timeout after 10 seconds
        setTimeout(() => {
          if (!this._isConnected) {
            this.ws?.close();
            reject(new Error('Connection timeout'));
          }
        }, 10000);
      } catch (e) {
        reject(e);
      }
    });
  }

  async send(message: C2SMessage): Promise<void> {
    if (this.ws && this._isConnected) {
      this.ws.send(JSON.stringify(message));
    }
  }

  onMessage(handler: (msg: S2CMessage) => void): () => void {
    this.handlers.push(handler);
    return () => {
      this.handlers = this.handlers.filter((h) => h !== handler);
    };
  }

  remoteRoomIds(): Set<string> {
    return new Set(); // Not used for visiting
  }

  disconnect(): void {
    if (this.pingInterval) {
      clearInterval(this.pingInterval);
      this.pingInterval = null;
    }
    this.ws?.close();
    this.ws = null;
    this._isConnected = false;
  }
}
