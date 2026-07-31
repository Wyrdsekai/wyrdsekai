/**
 * Domain events that occur within rooms.
 * TypeScript port of KMP's WorldEvent.kt — 16 event types as discriminated union.
 * Each event has type, roomId, and timestamp fields.
 */

import type { Hint } from '../../protocol/models';

export type WorldEvent =
  | RoomCreated
  | EntityEntered
  | EntityLeft
  | Said
  | Emoted
  | ObjectTaken
  | ObjectDropped
  | ObjectUsed
  | ExitOpened
  | ExitClosed
  | DescriptionChanged
  | HintsUpdated
  | ScriptTriggered
  | ObjectAdded
  | PropertyChanged
  | Whispered
  | VitalitySuggested;

export interface RoomCreated {
  type: 'room_created';
  roomId: string;
  timestamp: number;
  name: string;
  description: string;
  zone: string;
}

export interface EntityEntered {
  type: 'entity_entered';
  roomId: string;
  timestamp: number;
  entityId: string;
  entityName: string;
  entityType: string;
  fromDirection: string;
}

export interface EntityLeft {
  type: 'entity_left';
  roomId: string;
  timestamp: number;
  entityId: string;
  entityName: string;
  direction: string;
}

export interface Said {
  type: 'said';
  roomId: string;
  timestamp: number;
  entityId: string;
  entityName: string;
  text: string;
}

export interface ObjectTaken {
  type: 'object_taken';
  roomId: string;
  timestamp: number;
  entityId: string;
  objectId: string;
  objectName: string;
}

export interface ObjectDropped {
  type: 'object_dropped';
  roomId: string;
  timestamp: number;
  entityId: string;
  objectId: string;
  objectName: string;
  description: string;
  takeable: boolean;
}

export interface ObjectUsed {
  type: 'object_used';
  roomId: string;
  timestamp: number;
  entityId: string;
  objectId: string;
  objectName: string;
  target: string | null;
  result: string | null;
}

export interface ExitOpened {
  type: 'exit_opened';
  roomId: string;
  timestamp: number;
  direction: string;
  targetRoom: string;
  label: string;
}

export interface ExitClosed {
  type: 'exit_closed';
  roomId: string;
  timestamp: number;
  direction: string;
}

export interface DescriptionChanged {
  type: 'description_changed';
  roomId: string;
  timestamp: number;
  newDescription: string;
  reason: string | null;
}

export interface HintsUpdated {
  type: 'hints_updated';
  roomId: string;
  timestamp: number;
  hints: Hint[];
}

export interface ScriptTriggered {
  type: 'script_triggered';
  roomId: string;
  timestamp: number;
  scriptName: string;
  trigger: string;
  context: Record<string, string>;
}

export interface ObjectAdded {
  type: 'object_added';
  roomId: string;
  timestamp: number;
  objectId: string;
  objectName: string;
  description: string;
  takeable: boolean;
}

export interface PropertyChanged {
  type: 'property_changed';
  roomId: string;
  timestamp: number;
  key: string;
  oldValue: string | null;
  newValue: string | null;
}

export interface Whispered {
  type: 'whispered';
  roomId: string;
  timestamp: number;
  entityId: string;
  entityName: string;
  targetEntityId: string;
  text: string;
}

export interface Emoted {
  type: 'emoted';
  roomId: string;
  timestamp: number;
  entityId: string;
  entityName: string;
  text: string;
}

export interface VitalitySuggested {
  type: 'vitality_suggested';
  roomId: string;
  timestamp: number;
  entityId: string;
  tank: string;
  delta: number;
  reason: string;
}