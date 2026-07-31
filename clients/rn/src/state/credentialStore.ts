/**
 * Credential persistence — Zustand store.
 * In-memory store with a clear API that can be swapped for
 * react-native-secure-storage or other secure backends later.
 */

import { create } from 'zustand';

export interface CredentialState {
  savedServerUrl: string | null;
  savedUsername: string | null;
  savedToken: string | null;
  saveCredentials: (serverUrl: string, username: string, token: string) => void;
  clearCredentials: () => void;
}

export const useCredentialStore = create<CredentialState>((set) => ({
  savedServerUrl: null,
  savedUsername: null,
  savedToken: null,

  saveCredentials: (serverUrl, username, token) =>
    set({ savedServerUrl: serverUrl, savedUsername: username, savedToken: token }),

  clearCredentials: () =>
    set({ savedServerUrl: null, savedUsername: null, savedToken: null }),
}));
