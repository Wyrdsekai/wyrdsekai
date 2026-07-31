/**
 * Abstraction for a connection to a household server.
 * Real implementation wraps WyrdWebSocket; tests use InMemoryServerConnection.
 */

import type { C2SMessage } from '../../protocol/c2s';
import type { S2CMessage } from '../../protocol/s2c';

export type S2CHandler = (msg: S2CMessage) => void;

export interface ServerConnection {
  readonly isConnected: boolean;

  /** Send a C2S command to the server. */
  send(message: C2SMessage): void;

  /** Register a handler for S2C messages from the server. Returns unsubscribe function. */
  onMessage(handler: S2CHandler): () => void;

  /** Available room IDs known to be on the server. */
  remoteRoomIds(): Set<string>;
}

/**
 * In-memory mock for testing transit coordination.
 */
export class InMemoryServerConnection implements ServerConnection {
  isConnected = false;

  readonly sent: C2SMessage[] = [];
  private handlers: S2CHandler[] = [];
  private _remoteRoomIds = new Set<string>();

  send(message: C2SMessage): void {
    this.sent.push(message);
  }

  onMessage(handler: S2CHandler): () => void {
    this.handlers.push(handler);
    return () => {
      this.handlers = this.handlers.filter(h => h !== handler);
    };
  }

  remoteRoomIds(): Set<string> {
    return new Set(this._remoteRoomIds);
  }

  /** Test helper: add remote room IDs. */
  addRemoteRooms(...roomIds: string[]): void {
    for (const id of roomIds) {
      this._remoteRoomIds.add(id);
    }
  }

  /** Test helper: simulate a message from the server. */
  receive(message: S2CMessage): void {
    for (const handler of this.handlers) {
      handler(message);
    }
  }
}
