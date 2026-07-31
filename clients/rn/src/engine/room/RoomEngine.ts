/**
 * Core room engine — sequential command processing via async queue.
 * TypeScript port of KMP's RoomEngine.kt.
 *
 * A Promise chain gives the same sequential processing guarantee
 * as KMP's Channel or Pekko's actor mailbox.
 */

import type { WorldEvent } from '../events/WorldEvent';
import type { EventJournal } from '../persistence/EventJournal';
import type { RoomEngineCommand } from './RoomEngineCommand';
import type { RoomEngineResponse } from './RoomEngineResponse';
import { RoomState, emptyRoomState, applyEvent, toSnapshot } from './RoomState';

export type RoomEventListener = (event: WorldEvent) => void;

export class RoomEngine {
  private _state: RoomState;
  private listeners: RoomEventListener[] = [];
  private queue: Promise<void> = Promise.resolve();
  private recovered = false;

  constructor(
    readonly roomId: string,
    private readonly journal: EventJournal,
  ) {
    this._state = emptyRoomState(roomId);
    this.recoverAsync();
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

  /** Send a command and get a response. Sequential via Promise chain. */
  async send(cmd: RoomEngineCommand): Promise<RoomEngineResponse> {
    return new Promise<RoomEngineResponse>(resolve => {
      this.queue = this.queue.then(async () => {
        try {
          resolve(await this.handle(cmd));
        } catch (e) {
          resolve({
            type: 'rejected',
            code: 'internal_error',
            reason: e instanceof Error ? e.message : 'Unknown error',
          });
        }
      });
    });
  }

  private async recoverAsync(): Promise<void> {
    const events = await this.journal.replay(this.roomId);
    let s = emptyRoomState(this.roomId);
    for (const event of events) {
      s = applyEvent(s, event);
    }
    this._state = s;
    this.recovered = true;
  }

  private async handle(cmd: RoomEngineCommand): Promise<RoomEngineResponse> {
    const now = Date.now();
    const events: WorldEvent[] = [];

    switch (cmd.type) {
      case 'create_room': {
        events.push({
          type: 'room_created', roomId: this.roomId, timestamp: now,
          name: cmd.name, description: cmd.description, zone: cmd.zone,
        });
        for (const exit of cmd.exits ?? []) {
          events.push({
            type: 'exit_opened', roomId: this.roomId, timestamp: now,
            direction: exit.direction, targetRoom: exit.targetRoom, label: exit.label,
          });
        }
        for (const obj of cmd.objects ?? []) {
          events.push({
            type: 'object_added', roomId: this.roomId, timestamp: now,
            objectId: obj.id, objectName: obj.name,
            description: obj.description, takeable: obj.takeable,
          });
        }
        break;
      }

      case 'enter_room':
        events.push({
          type: 'entity_entered', roomId: this.roomId, timestamp: now,
          entityId: cmd.entityId, entityName: cmd.entityName,
          entityType: cmd.entityType, fromDirection: cmd.fromDirection,
        });
        break;

      case 'leave_room':
        events.push({
          type: 'entity_left', roomId: this.roomId, timestamp: now,
          entityId: cmd.entityId, entityName: cmd.entityName,
          direction: cmd.direction,
        });
        break;

      case 'say_in_room':
        events.push({
          type: 'said', roomId: this.roomId, timestamp: now,
          entityId: cmd.entityId, entityName: cmd.entityName, text: cmd.text,
        });
        break;

      case 'emote_in_room':
        events.push({
          type: 'emoted', roomId: this.roomId, timestamp: now,
          entityId: cmd.entityId, entityName: cmd.entityName, text: cmd.text,
        });
        break;

      case 'take_object': {
        const obj = Object.values(this._state.objects).find(
          o => o.name.toLowerCase() === cmd.objectName.toLowerCase(),
        );
        if (!obj) {
          return { type: 'rejected', code: 'not_found', reason: `No object named '${cmd.objectName}' here.` };
        }
        if (!obj.takeable) {
          return { type: 'rejected', code: 'not_takeable', reason: `You can't take the ${obj.name}.` };
        }
        events.push({
          type: 'object_taken', roomId: this.roomId, timestamp: now,
          entityId: cmd.entityId, objectId: obj.id, objectName: obj.name,
        });
        break;
      }

      case 'drop_object':
        events.push({
          type: 'object_dropped', roomId: this.roomId, timestamp: now,
          entityId: cmd.entityId, objectId: cmd.objectId,
          objectName: cmd.objectName, description: cmd.description,
          takeable: cmd.takeable,
        });
        break;

      case 'use_object': {
        const obj = Object.values(this._state.objects).find(
          o => o.name.toLowerCase() === cmd.objectName.toLowerCase(),
        );
        if (!obj) {
          return { type: 'rejected', code: 'not_found', reason: `No object named '${cmd.objectName}' here.` };
        }
        events.push({
          type: 'object_used', roomId: this.roomId, timestamp: now,
          entityId: cmd.entityId, objectId: obj.id, objectName: obj.name,
          target: cmd.target, result: null,
        });
        break;
      }

      case 'select_hint': {
        if (cmd.index < 0 || cmd.index >= this._state.hints.length) {
          return { type: 'rejected', code: 'invalid_index', reason: 'Invalid hint index.' };
        }
        // Hint selection doesn't generate an event — dispatched by the caller
        break;
      }

      case 'set_property':
        events.push({
          type: 'property_changed', roomId: this.roomId, timestamp: now,
          key: cmd.key, oldValue: this._state.properties[cmd.key] ?? null,
          newValue: cmd.value,
        });
        break;
    }

    // Persist and apply events
    for (const event of events) {
      await this.journal.append(this.roomId, event);
      this._state = applyEvent(this._state, event);
      for (const listener of this.listeners) {
        listener(event);
      }
    }

    return { type: 'ok', snapshot: toSnapshot(this._state) };
  }

  /** Convenience: set a room property without going through send(). */
  async setProperty(key: string, value: string): Promise<void> {
    await this.send({ type: 'set_property', key, value });
  }

  shutdown(): void {
    this.listeners = [];
  }
}
