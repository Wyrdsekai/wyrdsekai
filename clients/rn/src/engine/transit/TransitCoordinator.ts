/**
 * Coordinates between local PhoneNode rooms and remote household server rooms.
 *
 * When the player is in a local room, commands route to the PhoneNode engine.
 * When the player transits to a remote room (one that exists on the server but
 * not locally), commands are forwarded via the ServerConnection using typed
 * C2S messages.
 *
 * When Between is available, rooms on other household nodes can be visited
 * via VisitingRoomProxy (SSH model — events streamed over NATS, commands
 * forwarded). This is the 'visiting' transit mode.
 *
 * Transit triggers:
 * - Player moves to a room that's remote-only → enter remote mode
 * - Player moves to a Between-hosted room → enter visiting mode
 * - Player moves back to a local room → enter local mode
 * - Server sends S2CTransit → switch zones
 */

import type { PhoneNode } from '../PhoneNode';
import type { ServerConnection } from './ServerConnection';
import type { S2CMessage } from '../../protocol/s2c';
import { newId } from '../../protocol/c2s';

export type TransitMode = 'local' | 'remote' | 'visiting';

export type TransitEvent =
  | { type: 'transited_to_remote'; roomId: string }
  | { type: 'transited_to_visiting'; roomId: string; hostNodeId: string }
  | { type: 'returned_to_local'; roomId: string }
  | { type: 'server_transit'; targetZoneId: string; message: string }
  | { type: 'remote_prose'; speaker: string; text: string }
  | { type: 'remote_room_state'; roomId: string; name: string; description: string };

export type TransitEventListener = (event: TransitEvent) => void;

/**
 * Map of Between-hosted room IDs to the node hosting them.
 * Populated externally from household presence / discovery data.
 */
export type BetweenRoomRegistry = Map<string, string>;

export class TransitCoordinator {
  private _mode: TransitMode = 'local';
  private _remoteRoomId: string | null = null;
  private eventListeners: TransitEventListener[] = [];
  private serverUnsub: (() => void) | null = null;

  /**
   * Registry of rooms hosted on other household nodes (via Between).
   * Keys are room IDs, values are host node IDs.
   * Updated externally as presence data arrives.
   */
  readonly betweenRooms: BetweenRoomRegistry = new Map();

  constructor(
    private readonly phoneNode: PhoneNode,
    private readonly serverConnection: ServerConnection | null,
  ) {}

  get mode(): TransitMode {
    return this._mode;
  }

  get remoteRoomId(): string | null {
    return this._remoteRoomId;
  }

  get isRemote(): boolean {
    return this._mode === 'remote';
  }

  /** Start listening for server messages. */
  start(): void {
    this.serverUnsub = this.serverConnection?.onMessage(msg => {
      this.handleServerMessage(msg);
    }) ?? null;
  }

  /** Stop listening. */
  stop(): void {
    this.serverUnsub?.();
    this.serverUnsub = null;
  }

  onEvent(listener: TransitEventListener): () => void {
    this.eventListeners.push(listener);
    return () => {
      this.eventListeners = this.eventListeners.filter(l => l !== listener);
    };
  }

  private emit(event: TransitEvent): void {
    for (const listener of this.eventListeners) {
      listener(event);
    }
  }

  /**
   * Handle "go" — checks if the target room is local, visiting (Between),
   * or remote (server).
   * Returns true if handled, false if the direction is invalid.
   */
  async go(entityId: string, entityName: string, direction: string): Promise<boolean> {
    if (this._mode === 'local') {
      const currentRoom = this.phoneNode.currentRoom();
      if (!currentRoom) return false;

      const exit = currentRoom.state.exits[direction];
      if (!exit) return false;

      const targetRoomId = exit.targetRoom;

      // Is the target room local?
      if (this.phoneNode.activeRoomIds().has(targetRoomId)) {
        await this.phoneNode.go(entityId, entityName, direction);
        return true;
      }

      // Is the target room on another household node (via Between)?
      const hostNodeId = this.betweenRooms.get(targetRoomId);
      if (hostNodeId && this.phoneNode.hasBetween) {
        await this.transitToVisiting(entityId, entityName, targetRoomId, hostNodeId);
        return true;
      }

      // Is the target room remote (on server)?
      const conn = this.serverConnection;
      if (conn && conn.isConnected && conn.remoteRoomIds().has(targetRoomId)) {
        await this.transitToRemote(entityId, entityName, targetRoomId);
        return true;
      }

      // Room not available anywhere
      return false;
    } else if (this._mode === 'visiting') {
      // In visiting mode — send command via the VisitingRoomProxy
      const proxy = this.phoneNode.visitingRoom;
      if (!proxy) return false;
      proxy.send({
        type: 'leave_room',
        entityId,
        entityName,
        direction,
      });
      // Note: room transitions while visiting are handled by the remote host;
      // the proxy will receive the events. For returning to local, use returnToLocal().
      return true;
    } else {
      // In remote mode — send typed Go to server
      const conn = this.serverConnection;
      if (!conn) return false;
      conn.send({
        type: 'go',
        id: newId(),
        roomId: this._remoteRoomId ?? '',
        direction,
      });
      return true;
    }
  }

  /**
   * Handle "say" — routes to local, visiting, or remote.
   */
  async say(entityId: string, entityName: string, text: string): Promise<void> {
    if (this._mode === 'local') {
      await this.phoneNode.say(entityId, entityName, text);
    } else if (this._mode === 'visiting') {
      const proxy = this.phoneNode.visitingRoom;
      if (proxy) {
        proxy.send({ type: 'say_in_room', entityId, entityName, text });
      }
    } else {
      this.serverConnection?.send({
        type: 'say',
        id: newId(),
        roomId: this._remoteRoomId ?? '',
        text,
      });
    }
  }

  /**
   * Handle "look" — returns local snapshot, visiting proxy state, or sends Look to server.
   */
  look(): void {
    if (this._mode === 'local') {
      this.phoneNode.look();
    } else if (this._mode === 'visiting') {
      // Visiting mode — the proxy maintains local state from streamed events.
      // Emit the current proxy state as a room state event.
      const proxy = this.phoneNode.visitingRoom;
      if (proxy) {
        this.emit({
          type: 'remote_room_state',
          roomId: proxy.roomId,
          name: proxy.state.name,
          description: proxy.state.description,
        });
      }
    } else {
      this.serverConnection?.send({
        type: 'look',
        id: newId(),
        roomId: this._remoteRoomId ?? '',
      });
    }
  }

  /**
   * Explicitly return to a local room.
   */
  returnToLocal(entityId: string, entityName: string, localRoomId = 'home'): void {
    if (this._mode === 'local') return;

    if (this._mode === 'visiting') {
      this.phoneNode.leaveVisitingRoom();
    }

    this._mode = 'local';
    this._remoteRoomId = null;
    this.emit({ type: 'returned_to_local', roomId: localRoomId });
  }

  // ── Private ──────────────────────────────────────────────────────────

  private async transitToVisiting(entityId: string, entityName: string, targetRoomId: string, hostNodeId: string): Promise<void> {
    // Leave current local room
    const room = this.phoneNode.currentRoom();
    if (room) {
      await room.send({ type: 'leave_room', entityId, entityName, direction: 'transit' });
    }

    // Create visiting room proxy via PhoneNode
    this.phoneNode.visitRoom(targetRoomId, hostNodeId);

    this._mode = 'visiting';
    this._remoteRoomId = targetRoomId;
    this.emit({ type: 'transited_to_visiting', roomId: targetRoomId, hostNodeId });
  }

  private async transitToRemote(entityId: string, entityName: string, targetRoomId: string): Promise<void> {
    // Leave current local room
    const room = this.phoneNode.currentRoom();
    if (room) {
      await room.send({ type: 'leave_room', entityId, entityName, direction: 'transit' });
    }

    this._mode = 'remote';
    this._remoteRoomId = targetRoomId;
    this.emit({ type: 'transited_to_remote', roomId: targetRoomId });

    // Tell the server we want to look at this room
    this.serverConnection?.send({
      type: 'look',
      id: newId(),
      roomId: targetRoomId,
    });
  }

  private handleServerMessage(msg: S2CMessage): void {
    switch (msg.type) {
      case 'transit':
        this.emit({
          type: 'server_transit',
          targetZoneId: msg.targetZoneId,
          message: msg.message,
        });
        break;
      case 'prose':
        if (this._mode === 'remote') {
          this.emit({
            type: 'remote_prose',
            speaker: msg.speaker,
            text: msg.text,
          });
        }
        break;
      case 'room_state':
        if (this._mode === 'remote') {
          this._remoteRoomId = msg.room.roomId;
          this.emit({
            type: 'remote_room_state',
            roomId: msg.room.roomId,
            name: msg.room.name,
            description: msg.room.description,
          });
        }
        break;
    }
  }
}
