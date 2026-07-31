/**
 * Looks like a RoomEngine to PhoneNode but forwards everything over Between.
 *
 * Subscribes to `between.{householdId}.room.{roomId}.events` for incoming WorldEvents.
 * Publishes commands to `between.{householdId}.room.{roomId}.commands`.
 * Maintains local RoomState by applying received events.
 *
 * This enables room visiting (SSH model) — the phone companion can visit rooms
 * hosted on other nodes without running the room engine locally.
 *
 */

import type { BetweenClient } from './BetweenClient';
import type { WorldEvent } from '../events/WorldEvent';
import type { RoomEngineCommand } from '../room/RoomEngineCommand';
import { type RoomState, emptyRoomState, applyEvent } from '../room/RoomState';

export type RoomEventListener = (event: WorldEvent) => void;

export class VisitingRoomProxy {
  private _state: RoomState;
  private listeners: RoomEventListener[] = [];
  private unsubscribeEvents: (() => void) | null = null;

  constructor(
    readonly roomId: string,
    private readonly betweenClient: BetweenClient,
    private readonly householdId: string,
  ) {
    this._state = emptyRoomState(roomId);
  }

  get state(): RoomState {
    return this._state;
  }

  /** Subscribe to room events. Returns unsubscribe function. */
  onEvent(listener: RoomEventListener): () => void {
    this.listeners.push(listener);
    return () => {
      this.listeners = this.listeners.filter(l => l !== listener);
    };
  }

  /** Start receiving events from the remote room. */
  startListening(): void {
    const subject = `between.${this.householdId}.room.${this.roomId}.events`;
    this.unsubscribeEvents = this.betweenClient.subscribe(subject, (_subject, data) => {
      try {
        const event = JSON.parse(new TextDecoder().decode(data)) as WorldEvent;
        this._state = applyEvent(this._state, event);
        for (const listener of this.listeners) {
          listener(event);
        }
      } catch {
        // Malformed event — skip
      }
    });
  }

  /**
   * Send a command to the remote room engine.
   * The command is serialized to JSON and published to the room's command subject.
   */
  send(command: RoomEngineCommand): void {
    const data = new TextEncoder().encode(JSON.stringify(command));
    this.betweenClient.publish(
      `between.${this.householdId}.room.${this.roomId}.commands`,
      data,
    );
  }

  /** Stop receiving events and clean up. */
  shutdown(): void {
    this.unsubscribeEvents?.();
    this.unsubscribeEvents = null;
    this.listeners = [];
  }
}
