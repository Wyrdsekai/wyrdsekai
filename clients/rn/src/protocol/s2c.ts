/** Server → Client message types — mirrors Java S2CMessage sealed interface */

import { ContentBlock, Hint, RoomObject, RoomSnapshot, Structured, TopologySnapshot } from './models';

export type S2CMessage =
  | S2CRoomState
  | S2CProse
  | S2CAgentAction
  | S2CStateChange
  | S2CReplayDone
  | S2CError
  | S2CNotification
  | S2CTransit
  | S2CTokenStream
  | S2CTopologyChanged
  | S2CMapData
  | S2CZoneResponse
  | S2CVoiceAudio;

interface S2CBase {
  seq: number;
}

export interface S2CRoomState extends S2CBase {
  type: 'room_state';
  room: RoomSnapshot;
  inventory: RoomObject[] | null;
}

export interface S2CProse extends S2CBase {
  type: 'prose';
  speaker: string;
  text: string;
  hints: Hint[];
  structured: Structured | null;
  priority: string;
  lang?: string | null;
  isAiGenerated?: boolean;
  blocks?: ContentBlock[];
  voice?: boolean | null;
  style?: string | null;
}

export interface S2CAgentAction extends S2CBase {
  type: 'agent_action';
  agentName: string;
  action: string;
  description: string;
}

export interface S2CStateChange extends S2CBase {
  type: 'state_change';
  description: string;
  structured: Structured | null;
  blocks?: ContentBlock[];
}

export interface S2CReplayDone extends S2CBase {
  type: 'replay_done';
  fromSeq: number;
  toSeq: number;
  count: number;
}

export interface S2CError extends S2CBase {
  type: 'error';
  code: string;
  message: string;
  requestId?: string | null;
}

export interface S2CNotification extends S2CBase {
  type: 'notification';
  level: string;
  title: string;
  message: string;
}

export interface S2CTransit extends S2CBase {
  type: 'transit';
  targetZoneId: string;
  targetUrl?: string | null;
  transitToken?: string | null;
  message: string;
}

export interface S2CTokenStream extends S2CBase {
  type: 'token_stream';
  source: string;
  token: string;
  done: boolean;
  context?: string | null;
}

export interface S2CTopologyChanged extends S2CBase {
  type: 'topology_changed';
  changeType: string;
  roomId: string;
  direction?: string | null;
  targetRoomId?: string | null;
  description: string;
}

export interface S2CMapData extends S2CBase {
  type: 'map_data';
  command: string;
  textMap: string;
  topology?: TopologySnapshot | null;
  path?: string[] | null;
}

export interface S2CZoneResponse extends S2CBase {
  type: 'zone_response';
  requestId: string;
  namespace: string;
  text: string;
  data?: unknown | null;
  blocks?: ContentBlock[];
}

export interface S2CVoiceAudio extends S2CBase {
  type: 'voice_audio';
  audioBase64: string;
  format: string;
  speaker: string;
}

/** Parse an S2C message from JSON. Returns null for unknown types. */
export function parseS2CMessage(json: string): S2CMessage | null {
  try {
    const obj = JSON.parse(json);
    if (!obj || typeof obj.type !== 'string' || typeof obj.seq !== 'number') {
      return null;
    }
    return obj as S2CMessage;
  } catch {
    return null;
  }
}
