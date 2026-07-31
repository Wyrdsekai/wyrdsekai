/**
 * Commands that can be sent to a RoomEngine.
 * TypeScript port of KMP's RoomEngineCommand.kt — discriminated union.
 */

import type { Exit, RoomObject } from '../../protocol/models';

export type RoomEngineCommand =
  | CreateRoom
  | EnterRoom
  | LeaveRoom
  | SayInRoom
  | EmoteInRoom
  | TakeObject
  | DropObject
  | UseObject
  | SelectHint
  | SetProperty;

export interface CreateRoom {
  type: 'create_room';
  name: string;
  description: string;
  zone: string;
  exits?: Exit[];
  objects?: RoomObject[];
}

export interface EnterRoom {
  type: 'enter_room';
  entityId: string;
  entityName: string;
  entityType: string;
  fromDirection: string;
}

export interface LeaveRoom {
  type: 'leave_room';
  entityId: string;
  entityName: string;
  direction: string;
}

export interface SayInRoom {
  type: 'say_in_room';
  entityId: string;
  entityName: string;
  text: string;
}

export interface TakeObject {
  type: 'take_object';
  entityId: string;
  objectName: string;
}

export interface DropObject {
  type: 'drop_object';
  entityId: string;
  objectName: string;
  objectId: string;
  description: string;
  takeable: boolean;
}

export interface UseObject {
  type: 'use_object';
  entityId: string;
  objectName: string;
  target: string | null;
}

export interface EmoteInRoom {
  type: 'emote_in_room';
  entityId: string;
  entityName: string;
  text: string;
}

export interface SelectHint {
  type: 'select_hint';
  entityId: string;
  index: number;
}

export interface SetProperty {
  type: 'set_property';
  key: string;
  value: string;
}
