/**
 * webNodeStore — Zustand store for web ephemeral node state.
 *
 * Tracks the lifecycle of the EphemeralNode, WebLLM model loading,
 * NATS connection, room state, and detected browser capabilities.
 */
import { create } from 'zustand';
import type { RoomSnapshot } from '../protocol/models';

export type WebNodeState = 'stopped' | 'starting' | 'running' | 'error';

interface WebNodeStoreState {
  /** Current lifecycle state of the ephemeral node. */
  nodeState: WebNodeState;
  /** Error message if nodeState is 'error', null otherwise. */
  nodeError: string | null;
  /** ID of the currently loaded WebLLM model, null if none. */
  webLLMModelId: string | null;
  /** Whether a WebLLM model is currently being downloaded/compiled. */
  webLLMLoading: boolean;
  /** Progress of the current WebLLM model load, null if not loading. */
  webLLMProgress: { text: string; progress: number } | null;
  /** Detected browser capabilities for ephemeral node features. */
  capabilities: {
    webgpu: boolean;
    webCrypto: boolean;
    indexedDB: boolean;
    serviceWorker: boolean;
  };
  /** Whether NATS is connected to the household Between network. */
  natsConnected: boolean;
  /** Current room snapshot from the local ephemeral node. */
  roomSnapshot: RoomSnapshot | null;
  /** Companion engine state. */
  companionState: 'idle' | 'thinking' | 'off';
  /** Service worker cache status. */
  swCacheStatus: 'unavailable' | 'installing' | 'active' | 'error';

  setNodeState: (state: WebNodeState) => void;
  setNodeError: (error: string | null) => void;
  setWebLLMModelId: (modelId: string | null) => void;
  setWebLLMLoading: (loading: boolean) => void;
  setWebLLMProgress: (progress: { text: string; progress: number } | null) => void;
  setCapabilities: (capabilities: {
    webgpu: boolean;
    webCrypto: boolean;
    indexedDB: boolean;
    serviceWorker: boolean;
  }) => void;
  setNatsConnected: (connected: boolean) => void;
  setRoomSnapshot: (snapshot: RoomSnapshot | null) => void;
  setCompanionState: (state: 'idle' | 'thinking' | 'off') => void;
  setSwCacheStatus: (status: 'unavailable' | 'installing' | 'active' | 'error') => void;
}

export const useWebNodeStore = create<WebNodeStoreState>((set) => ({
  nodeState: 'stopped',
  nodeError: null,
  webLLMModelId: null,
  webLLMLoading: false,
  webLLMProgress: null,
  capabilities: {
    webgpu: false,
    webCrypto: false,
    indexedDB: false,
    serviceWorker: false,
  },
  natsConnected: false,
  roomSnapshot: null,
  companionState: 'off',
  swCacheStatus: 'unavailable',

  setNodeState: (state) => set({ nodeState: state }),
  setNodeError: (error) => set({ nodeError: error }),
  setWebLLMModelId: (modelId) => set({ webLLMModelId: modelId }),
  setWebLLMLoading: (loading) => set({ webLLMLoading: loading }),
  setWebLLMProgress: (progress) => set({ webLLMProgress: progress }),
  setCapabilities: (capabilities) => set({ capabilities }),
  setNatsConnected: (connected) => set({ natsConnected: connected }),
  setRoomSnapshot: (snapshot) => set({ roomSnapshot: snapshot }),
  setCompanionState: (state) => set({ companionState: state }),
  setSwCacheStatus: (status) => set({ swCacheStatus: status }),
}));
