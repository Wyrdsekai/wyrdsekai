/** Session state — Zustand store */

import { create } from 'zustand';
import { ContentBlock, Hint, PriorityLevel, RoomObject, RoomSnapshot, Exit, Entity, parsePriority } from '../protocol/models';
import { S2CMessage } from '../protocol/s2c';
import { newId } from '../protocol/c2s';
import { WyrdWebSocket, ConnectionState } from '../network/websocket';

export interface ProseEntry {
  speaker: string;
  text: string;
  priority: PriorityLevel;
  isAiGenerated?: boolean;
  hints?: Hint[];
  blocks?: ContentBlock[];
}

interface SessionState {
  // Connection
  connectionState: ConnectionState;
  serverUrl: string;
  token: string | null;

  // Room
  roomId: string;
  roomName: string;
  roomDescription: string;
  exits: Exit[];
  entities: Entity[];
  objects: RoomObject[];
  hints: Hint[];
  inventory: RoomObject[];

  // Prose
  proseStream: ProseEntry[];
  streamingText: Record<string, string>; // source -> accumulated text

  // Actions
  setServerUrl: (url: string) => void;
  setConnectionState: (state: ConnectionState) => void;
  setToken: (token: string | null) => void;
  handleMessage: (msg: S2CMessage) => void;
  addProse: (entry: ProseEntry) => void;
  clearProse: () => void;
}

export const useSessionStore = create<SessionState>((set, get) => ({
  connectionState: 'disconnected',
  serverUrl: 'localhost:7070',
  token: null,
  roomId: '',
  roomName: '?',
  roomDescription: '',
  exits: [],
  entities: [],
  objects: [],
  hints: [],
  inventory: [],
  proseStream: [],
  streamingText: {},

  setServerUrl: (url) => set({ serverUrl: url }),
  setConnectionState: (state) => set({ connectionState: state }),
  setToken: (token) => set({ token }),
  clearProse: () => set({ proseStream: [] }),

  addProse: (entry) => set((state) => ({
    proseStream: [...state.proseStream, entry],
  })),

  handleMessage: (msg) => {
    const { addProse } = get();

    switch (msg.type) {
      case 'room_state': {
        const room = msg.room;
        const prevRoomId = get().roomId;
        set({
          roomId: room.roomId,
          roomName: room.name,
          roomDescription: room.description,
          exits: room.exits,
          entities: room.entities,
          objects: room.objects,
          hints: room.hints,
          ...(msg.inventory != null ? { inventory: msg.inventory } : {}),
        });
        // Skip duplicate room description on reconnect to same room
        if (room.roomId !== prevRoomId) {
          addProse({
            speaker: 'narrator',
            text: `${room.name}\n${room.description}`,
            priority: 'normal',
          });
        }
        break;
      }

      case 'prose': {
        addProse({
          speaker: msg.speaker,
          text: msg.text,
          priority: parsePriority(msg.priority),
          isAiGenerated: msg.isAiGenerated,
          hints: msg.hints,
          blocks: msg.blocks,
        });
        if (msg.hints.length > 0) {
          set({ hints: msg.hints });
        }
        break;
      }

      case 'agent_action': {
        addProse({
          speaker: msg.agentName,
          text: `* ${msg.agentName} ${msg.description}`,
          priority: 'normal',
        });
        break;
      }

      case 'state_change': {
        addProse({
          speaker: 'narrator',
          text: `~ ${msg.description}`,
          priority: 'normal',
          blocks: msg.blocks,
        });
        break;
      }

      case 'error': {
        addProse({
          speaker: 'system',
          text: `Error [${msg.code}]: ${msg.message}`,
          priority: 'critical',
        });
        break;
      }

      case 'notification': {
        addProse({
          speaker: 'system',
          text: `[${msg.title}] ${msg.message}`,
          priority: 'normal',
        });
        break;
      }

      case 'token_stream': {
        set((state) => {
          const current = state.streamingText[msg.source] ?? '';
          const updated = current + msg.token;

          if (msg.done) {
            // Finalize: add to prose stream, remove from streaming
            const { [msg.source]: _, ...rest } = state.streamingText;
            return {
              streamingText: rest,
              proseStream: [...state.proseStream, {
                speaker: msg.source,
                text: updated,
                priority: 'normal' as PriorityLevel,
                isAiGenerated: true,
              }],
            };
          }

          return {
            streamingText: { ...state.streamingText, [msg.source]: updated },
          };
        });
        break;
      }

      case 'replay_done': {
        addProse({
          speaker: 'system',
          text: `[Reconnected — replayed ${msg.count} messages]`,
          priority: 'normal',
        });
        break;
      }

      case 'transit': {
        addProse({
          speaker: 'system',
          text: `[Transit] ${msg.message}`,
          priority: 'critical',
        });
        break;
      }
    }
  },
}));
