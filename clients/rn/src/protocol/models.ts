/** Wire protocol model types — mirrors Java records in common/model/ */

export interface Exit {
  direction: string;
  targetRoom: string;
  label: string;
}

export interface Entity {
  id: string;
  name: string;
  type: 'player' | 'agent' | 'npc';
  description: string;
}

export interface RoomObject {
  id: string;
  name: string;
  description: string;
  takeable: boolean;
}

export interface Hint {
  label: string;
  intent: string;
  action: 'say' | 'go' | 'use' | 'take' | 'look' | 'command';
  labelKey?: string | null;
}

export interface RoomSnapshot {
  roomId: string;
  name: string;
  description: string;
  zone: string;
  exits: Exit[];
  entities: Entity[];
  objects: RoomObject[];
  hints: Hint[];
}

export interface Structured {
  name?: string | null;
  description?: string | null;
  exits?: Exit[] | null;
  entities?: Entity[] | null;
  objects?: RoomObject[] | null;
  hints?: Hint[] | null;
  properties?: Record<string, string> | null;
  zone?: string | null;
}

export interface ContentBlock {
  format: string;
  data: unknown;
  fallback: string;
}

// ── Topology (§N1) ──

export interface TopologySnapshot {
  centerRoomId: string;
  nodes: MapNode[];
  edges: MapEdge[];
}

export interface MapNode {
  roomId: string;
  name: string | null;
  zone: string;
  current: boolean;
  visited: boolean;
  hopsFromCenter: number;
}

export interface MapEdge {
  fromRoomId: string;
  toRoomId: string;
  direction: string;
  label: string;
  hasReturn: boolean;
}

export type PriorityLevel = 'critical' | 'normal' | 'ambient';

export function parsePriority(value?: string | null): PriorityLevel {
  switch (value?.toLowerCase()) {
    case 'critical': return 'critical';
    case 'ambient': return 'ambient';
    default: return 'normal';
  }
}
