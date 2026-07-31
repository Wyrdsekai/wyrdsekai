/**
 * Mutable room state derived from event stream via apply().
 * TypeScript port of KMP's RoomState.kt.
 */

import type { Exit, Entity, RoomObject, Hint, RoomSnapshot } from '../../protocol/models';
import type { WorldEvent } from '../events/WorldEvent';

export interface RoomState {
  roomId: string;
  name: string;
  description: string;
  zone: string;
  exits: Record<string, Exit>;
  entities: Record<string, Entity>;
  objects: Record<string, RoomObject>;
  hints: Hint[];
  properties: Record<string, string>;
}

export function emptyRoomState(roomId: string): RoomState {
  return {
    roomId,
    name: '',
    description: '',
    zone: '',
    exits: {},
    entities: {},
    objects: {},
    hints: [],
    properties: {},
  };
}

/** Pure function: apply an event to produce new state. */
export function applyEvent(state: RoomState, event: WorldEvent): RoomState {
  switch (event.type) {
    case 'room_created':
      return { ...state, name: event.name, description: event.description, zone: event.zone };

    case 'entity_entered':
      return {
        ...state,
        entities: {
          ...state.entities,
          [event.entityId]: {
            id: event.entityId,
            name: event.entityName,
            type: event.entityType as Entity['type'],
            description: '',
          },
        },
      };

    case 'entity_left': {
      const { [event.entityId]: _, ...rest } = state.entities;
      return { ...state, entities: rest };
    }

    case 'object_taken': {
      const { [event.objectId]: _, ...rest } = state.objects;
      return { ...state, objects: rest };
    }

    case 'object_dropped':
      return {
        ...state,
        objects: {
          ...state.objects,
          [event.objectId]: {
            id: event.objectId,
            name: event.objectName,
            description: event.description,
            takeable: event.takeable,
          },
        },
      };

    case 'object_added':
      return {
        ...state,
        objects: {
          ...state.objects,
          [event.objectId]: {
            id: event.objectId,
            name: event.objectName,
            description: event.description,
            takeable: event.takeable,
          },
        },
      };

    case 'exit_opened':
      return {
        ...state,
        exits: {
          ...state.exits,
          [event.direction]: {
            direction: event.direction,
            targetRoom: event.targetRoom,
            label: event.label,
          },
        },
      };

    case 'exit_closed': {
      const { [event.direction]: _, ...rest } = state.exits;
      return { ...state, exits: rest };
    }

    case 'description_changed':
      return { ...state, description: event.newDescription };

    case 'hints_updated':
      return { ...state, hints: event.hints };

    case 'property_changed': {
      if (event.newValue == null) {
        const { [event.key]: _, ...rest } = state.properties;
        return { ...state, properties: rest };
      }
      return { ...state, properties: { ...state.properties, [event.key]: event.newValue } };
    }

    // Events that don't change room state
    case 'said':
    case 'emoted':
    case 'object_used':
    case 'script_triggered':
    case 'whispered':
    case 'vitality_suggested':
      return state;
  }
}

/** Convert to protocol snapshot for wire transmission. */
export function toSnapshot(state: RoomState): RoomSnapshot {
  return {
    roomId: state.roomId,
    name: state.name,
    description: state.description,
    zone: state.zone,
    exits: Object.values(state.exits),
    entities: Object.values(state.entities),
    objects: Object.values(state.objects),
    hints: state.hints,
  };
}
