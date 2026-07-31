/**
 * standaloneNodeStore — Zustand store for standalone PhoneNode state.
 *
 * Mirrors sessionStore's room/prose state but fed by local PhoneNode events
 * instead of WebSocket S2C messages. Used by StandaloneRoomScreen.
 */
import { create } from 'zustand';
import type { RoomSnapshot, Exit, RoomObject, Entity, Hint } from '../protocol/models';
import type { PhoneNodeState } from '../engine/PhoneNode';
import type { Tier } from '../engine/tier/TierConfig';
import type { DialogueState } from '../engine/agent/CompanionEngine';
import type { ServerClient } from '../server/ServerClient';
import type { NatsServerClient } from '../server/NatsServerClient';

/**
 * Either the HTTP-based ServerClient or the NATS-based NatsServerClient.
 * Both expose the same `tell` / `searchLibrary` / `writeJournal` shapes
 * (return types unified through ServerClient.McpResult), so screens read
 * `serverClient.method(...)` and don't care which transport is live.
 *
 * Transport selection lives in StandaloneNodeContext (feature-flagged on
 * `WYRD_NATS_TRANSPORT` env).
 */
export type PhoneServerClient = ServerClient | NatsServerClient;

export interface StandaloneProseEntry {
  speaker: string;
  text: string;
}

/** Shape of the data persisted to AsyncStorage. */
export interface PersistedStandaloneState {
  roomId: string;
  roomName: string;
  roomDescription: string;
  exits: Exit[];
  currentTier: Tier;
  companionState: 'idle' | 'thinking' | 'off';
  proseStream: StandaloneProseEntry[];
  companionName?: string;
}

/** Maximum number of prose entries to persist. */
const MAX_PERSISTED_PROSE = 50;

/** AsyncStorage key for standalone node state. */
export const PERSIST_KEY = '@wyrd_standalone_state';

/** Minimal AsyncStorage interface for persistence. */
export interface AsyncStoragePersist {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
}

interface StandaloneNodeStoreState {
  /** PhoneNode lifecycle state. */
  nodeState: PhoneNodeState;
  /** Error message if nodeState is 'error'. */
  nodeError: string | null;

  /** Current room ID (used for persistence and restore). */
  roomId: string;
  /** Current room fields (extracted from RoomSnapshot). */
  roomName: string;
  roomDescription: string;
  exits: Exit[];
  entities: Entity[];
  objects: RoomObject[];
  hints: Hint[];

  /** Prose stream from PhoneNode events. */
  proseStream: StandaloneProseEntry[];

  /** Companion engine state. */
  companionState: 'idle' | 'thinking' | 'off';

  /** Companion dialogue state machine (idle/listening/thinking/speaking/error). */
  dialogueState: DialogueState;

  /** Current resource tier. */
  currentTier: Tier;

  /** User's chosen companion name (defaults to "Ma"). */
  companionName: string;

  /**
   * ServerClient for wyrdsekai-server REST calls (tell, library_search,
   * journal_write etc.). Null when the phone is in pure-local mode (no
   * server URL configured, or the URL didn't probe as a wyrdsekai server —
   * e.g., raw llama-server). Set by StandaloneNodeContext on mount.
   */
  serverClient: PhoneServerClient | null;

  // Actions
  setServerClient: (client: PhoneServerClient | null) => void;
  setNodeState: (state: PhoneNodeState) => void;
  setNodeError: (error: string | null) => void;
  applyRoomSnapshot: (snapshot: RoomSnapshot) => void;
  addProse: (entry: StandaloneProseEntry) => void;
  clearProse: () => void;
  setCompanionState: (state: 'idle' | 'thinking' | 'off') => void;
  setDialogueState: (state: DialogueState) => void;
  setCurrentTier: (tier: Tier) => void;
  setCompanionName: (name: string) => void;
  reset: () => void;

  // Persistence
  persistState: (storage: AsyncStoragePersist) => Promise<void>;
  restoreState: (storage: AsyncStoragePersist) => Promise<PersistedStandaloneState | null>;
}

const INITIAL_STATE = {
  nodeState: 'stopped' as PhoneNodeState,
  nodeError: null as string | null,
  roomId: '',
  roomName: '',
  roomDescription: '',
  exits: [] as Exit[],
  entities: [] as Entity[],
  objects: [] as RoomObject[],
  hints: [] as Hint[],
  proseStream: [] as StandaloneProseEntry[],
  companionState: 'off' as const,
  dialogueState: 'idle' as DialogueState,
  currentTier: 'T1' as Tier,
  companionName: 'Wyrd',
  serverClient: null as ServerClient | null,
};

export const useStandaloneNodeStore = create<StandaloneNodeStoreState>((set, get) => ({
  ...INITIAL_STATE,

  setServerClient: (client) => set({ serverClient: client }),
  setNodeState: (state) => set({ nodeState: state }),
  setNodeError: (error) => set({ nodeError: error }),

  applyRoomSnapshot: (snapshot) => set({
    roomId: snapshot.roomId,
    roomName: snapshot.name,
    roomDescription: snapshot.description,
    exits: snapshot.exits,
    entities: snapshot.entities,
    objects: snapshot.objects,
    hints: snapshot.hints ?? [],
  }),

  addProse: (entry) => set((state) => ({
    proseStream: [...state.proseStream, entry],
  })),

  clearProse: () => set({ proseStream: [] }),

  setCompanionState: (state) => set({ companionState: state }),
  setDialogueState: (state) => set({ dialogueState: state }),
  setCurrentTier: (tier) => set({ currentTier: tier }),
  setCompanionName: (name) => set({ companionName: name }),

  reset: () => set(INITIAL_STATE),

  persistState: async (storage: AsyncStoragePersist) => {
    const state = get();
    const persisted: PersistedStandaloneState = {
      roomId: state.roomId,
      roomName: state.roomName,
      roomDescription: state.roomDescription,
      exits: state.exits,
      currentTier: state.currentTier,
      companionState: state.companionState,
      proseStream: state.proseStream.slice(-MAX_PERSISTED_PROSE),
      companionName: state.companionName,
    };
    await storage.setItem(PERSIST_KEY, JSON.stringify(persisted));
  },

  restoreState: async (storage: AsyncStoragePersist) => {
    const raw = await storage.getItem(PERSIST_KEY);
    if (!raw) return null;
    try {
      const persisted: PersistedStandaloneState = JSON.parse(raw);
      // Apply restored state to the store immediately (prose visible before node starts)
      set({
        roomId: persisted.roomId,
        roomName: persisted.roomName,
        roomDescription: persisted.roomDescription,
        exits: persisted.exits,
        currentTier: persisted.currentTier,
        companionState: persisted.companionState,
        proseStream: persisted.proseStream,
        companionName: persisted.companionName ?? 'Wyrd',
      });
      return persisted;
    } catch {
      // Corrupted data — clear and return null
      await storage.removeItem(PERSIST_KEY);
      return null;
    }
  },
}));
