/** Zustand store for on-device inference state. */

import { create } from 'zustand';

export type ActiveBackend = 'local' | 'remote' | 'server' | 'none';

interface InferenceState {
  /** Currently selected model from the catalog (null = none). */
  activeModelId: string | null;
  /** True while a model is being loaded into memory. */
  modelLoading: boolean;
  /** True while a completion request is in flight. */
  inferenceRunning: boolean;
  /** Which backend is currently capable of serving requests. */
  activeBackend: ActiveBackend;
  /** Download progress per model id (0-100 percent). */
  downloadProgress: Record<string, number>;

  setActiveModelId: (modelId: string | null) => void;
  setModelLoading: (loading: boolean) => void;
  setInferenceRunning: (running: boolean) => void;
  setActiveBackend: (backend: ActiveBackend) => void;
  updateDownloadProgress: (modelId: string, percent: number) => void;
  clearDownloadProgress: (modelId: string) => void;
}

export const useInferenceStore = create<InferenceState>((set) => ({
  activeModelId: null,
  modelLoading: false,
  inferenceRunning: false,
  activeBackend: 'none',
  downloadProgress: {},

  setActiveModelId: (modelId) => set({ activeModelId: modelId }),
  setModelLoading: (loading) => set({ modelLoading: loading }),
  setInferenceRunning: (running) => set({ inferenceRunning: running }),
  setActiveBackend: (backend) => set({ activeBackend: backend }),

  updateDownloadProgress: (modelId, percent) =>
    set((state) => ({
      downloadProgress: { ...state.downloadProgress, [modelId]: percent },
    })),

  clearDownloadProgress: (modelId) =>
    set((state) => {
      const { [modelId]: _, ...rest } = state.downloadProgress;
      return { downloadProgress: rest };
    }),
}));
