/** Client → Server message types — mirrors Java C2SMessage sealed interface */

export type C2SMessage =
  | C2SSay
  | C2SGo
  | C2STake
  | C2SDrop
  | C2SUse
  | C2SExamine
  | C2SLook
  | C2SHintSelect
  | C2SReconnect
  | C2SCommand
  | C2SSetPreference
  | C2SMapRequest
  | C2SVoiceAudio
  | C2SEmote;

export interface C2SSay {
  type: 'say';
  id: string;
  roomId: string;
  text: string;
  voice?: boolean | null;
}

export interface C2SGo {
  type: 'go';
  id: string;
  roomId: string;
  direction: string;
}

export interface C2STake {
  type: 'take';
  id: string;
  roomId: string;
  objectName: string;
}

export interface C2SDrop {
  type: 'drop';
  id: string;
  roomId: string;
  objectName: string;
}

export interface C2SUse {
  type: 'use';
  id: string;
  roomId: string;
  objectName: string;
  target?: string | null;
}

export interface C2SExamine {
  type: 'examine';
  id: string;
  roomId: string;
  target: string;
}

export interface C2SLook {
  type: 'look';
  id: string;
  roomId: string;
}

export interface C2SHintSelect {
  type: 'hint_select';
  id: string;
  roomId: string;
  index: number;
}

export interface C2SReconnect {
  type: 'reconnect';
  id: string;
  roomId: string;
  lastSeenSeq: number;
}

export interface C2SCommand {
  type: 'command';
  id: string;
  command: string;
  args: string[];
  payload: Record<string, string>;
}

export interface C2SSetPreference {
  type: 'set_preference';
  id: string;
  key: string;
  value: string;
}

export interface C2SMapRequest {
  type: 'map_request';
  id: string;
  command: string;
  radius?: number;
  target?: string | null;
}

export interface C2SVoiceAudio {
  type: 'voice_audio';
  id: string;
  audioBase64: string;
  format: string;
}

export interface C2SEmote {
  type: 'emote';
  id: string;
  roomId: string;
  text: string;
}

/** Serialize a C2S message to JSON string. */
export function serializeC2S(msg: C2SMessage): string {
  return JSON.stringify(msg);
}

/** Generate a unique message ID. */
let msgCounter = 0;
export function newId(): string {
  return `msg-${Date.now()}-${++msgCounter}`;
}
