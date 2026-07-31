/**
 * In-memory AsyncStorage mock for tests.
 * Simple Map-based implementation of the AsyncStorage interface.
 */

export interface MockAsyncStorage {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
  multiGet(keys: string[]): Promise<Array<[string, string | null]>>;
  multiSet(pairs: Array<[string, string]>): Promise<void>;
  /** Test helper: clear all stored data. */
  clear(): void;
  /** Test helper: get the raw backing map. */
  _store: Map<string, string>;
}

export function createMockAsyncStorage(): MockAsyncStorage {
  const store = new Map<string, string>();

  return {
    _store: store,

    getItem: async (key: string): Promise<string | null> => {
      return store.get(key) ?? null;
    },

    setItem: async (key: string, value: string): Promise<void> => {
      store.set(key, value);
    },

    removeItem: async (key: string): Promise<void> => {
      store.delete(key);
    },

    multiGet: async (keys: string[]): Promise<Array<[string, string | null]>> => {
      return keys.map((key) => [key, store.get(key) ?? null]);
    },

    multiSet: async (pairs: Array<[string, string]>): Promise<void> => {
      for (const [key, value] of pairs) {
        store.set(key, value);
      }
    },

    clear: () => {
      store.clear();
    },
  };
}
