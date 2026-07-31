/** User preferences — Zustand store with AsyncStorage persistence */

import { create } from 'zustand';
import { Platform } from 'react-native';

export type ThemeMode = 'light' | 'dark' | 'system';

export interface PreferencesState {
  locale: string;
  theme: ThemeMode;
  _loaded: boolean;
  setLocale: (locale: string) => void;
  setTheme: (theme: ThemeMode) => void;
  loadFromStorage: () => Promise<void>;
}

// Async storage helpers (cross-platform)
async function savePrefs(key: string, value: string) {
  try {
    if (Platform.OS === 'web') {
      localStorage.setItem(`wyrd_${key}`, value);
    } else {
      const AS = require('@react-native-async-storage/async-storage').default;
      await AS.setItem(`wyrd_${key}`, value);
    }
  } catch { /* best effort */ }
}

async function loadPref(key: string): Promise<string | null> {
  try {
    if (Platform.OS === 'web') {
      return localStorage.getItem(`wyrd_${key}`);
    } else {
      const AS = require('@react-native-async-storage/async-storage').default;
      return await AS.getItem(`wyrd_${key}`);
    }
  } catch {
    return null;
  }
}

export const usePreferencesStore = create<PreferencesState>((set) => ({
  locale: 'en',
  theme: 'system',
  _loaded: false,
  setLocale: (locale) => {
    set({ locale });
    savePrefs('locale', locale);
  },
  setTheme: (theme) => {
    set({ theme });
    savePrefs('theme', theme);
  },
  loadFromStorage: async () => {
    const locale = await loadPref('locale');
    const theme = await loadPref('theme');
    set({
      _loaded: true,
      ...(locale ? { locale } : {}),
      ...(theme ? { theme: theme as ThemeMode } : {}),
    });
  },
}));
