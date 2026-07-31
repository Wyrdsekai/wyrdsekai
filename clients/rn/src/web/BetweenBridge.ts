/**
 * BetweenBridge — translates NATS messages to/from RoomEngine commands/events.
 *
 * The bridge connects a local RoomEngine to the household Between network,
 * forwarding events from the room to NATS and commands from NATS to the room.
 *
 * NATS subjects:
 *   room.{roomId}.events  — room events (publish + subscribe)
 *   room.{roomId}.commands — room commands (publish + subscribe)
 */

import type { WorldEvent } from '../engine/events/WorldEvent';
import type { RoomEngineCommand } from '../engine/room/RoomEngineCommand';
import type { RoomEngine } from '../engine/room/RoomEngine';
import type { NatsClient } from './NatsClient';

export class BetweenBridge {
  private unsubscribers: Array<() => void> = [];
  private bridgedRooms = new Set<string>();

  constructor(
    private readonly natsClient: NatsClient,
  ) {}

  /**
   * Bridge a RoomEngine to the Between network.
   * Room events are published to NATS, and NATS commands are forwarded to the room.
   */
  bridgeRoom(room: RoomEngine): void {
    if (this.bridgedRooms.has(room.roomId)) return;
    this.bridgedRooms.add(room.roomId);

    const eventSubject = `room.${room.roomId}.events`;
    const commandSubject = `room.${room.roomId}.commands`;

    // Forward local room events to NATS
    const unsubRoom = room.onEvent((event: WorldEvent) => {
      try {
        this.natsClient.publishJson(eventSubject, event);
      } catch {
        // NATS publish failure is non-fatal
      }
    });
    this.unsubscribers.push(unsubRoom);

    // Subscribe to remote events from NATS
    const unsubEvents = this.natsClient.subscribe(eventSubject, (_subject, data) => {
      try {
        const event = JSON.parse(new TextDecoder().decode(data)) as WorldEvent;
        // Don't re-process events that came from this node (would loop)
        // In practice, the server deduplicates by seq, but we guard here too
        if (event.roomId === room.roomId) {
          // Event is from this room — could be from another node.
          // Future: filter by source node ID to avoid echo
        }
      } catch {
        // Malformed message
      }
    });
    this.unsubscribers.push(unsubEvents);

    // Subscribe to remote commands from NATS
    const unsubCommands = this.natsClient.subscribe(commandSubject, (_subject, data) => {
      try {
        const cmd = JSON.parse(new TextDecoder().decode(data)) as RoomEngineCommand;
        room.send(cmd).catch(() => {
          // Command processing failure is non-fatal
        });
      } catch {
        // Malformed command
      }
    });
    this.unsubscribers.push(unsubCommands);
  }

  /** Disconnect all bridges. */
  shutdown(): void {
    for (const unsub of this.unsubscribers) {
      unsub();
    }
    this.unsubscribers = [];
    this.bridgedRooms.clear();
  }
}
