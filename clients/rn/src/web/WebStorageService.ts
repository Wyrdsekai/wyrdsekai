/**
 * WebStorageService — browser-based persistent storage using IndexedDB.
 *
 * Replaces react-native-fs and react-native-secure-storage for the web target.
 * All operations are no-ops if IndexedDB is unavailable (e.g. SSR or unsupported browser).
 *
 * NOTE: IndexedDB is NOT encrypted. On web, browser storage is inherently less secure
 * than native secure storage. Credentials stored here are protected only by the
 * browser's same-origin policy.
 */

const DB_NAME = 'wyrdsekai';
const DB_VERSION = 1;
const STORE_CREDENTIALS = 'credentials';
const STORE_PREFERENCES = 'preferences';
const STORE_STATE = 'state';

export class WebStorageService {
  private db: IDBDatabase | null = null;

  /** Open the IndexedDB database and create object stores if needed. */
  async init(): Promise<void> {
    if (this.db) return;
    if (typeof indexedDB === 'undefined') return;

    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);

      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(STORE_CREDENTIALS)) {
          db.createObjectStore(STORE_CREDENTIALS);
        }
        if (!db.objectStoreNames.contains(STORE_PREFERENCES)) {
          db.createObjectStore(STORE_PREFERENCES);
        }
        if (!db.objectStoreNames.contains(STORE_STATE)) {
          db.createObjectStore(STORE_STATE);
        }
      };

      request.onsuccess = () => {
        this.db = request.result;
        resolve();
      };

      request.onerror = () => {
        console.warn('IndexedDB not available, using in-memory storage');
        resolve();
      };
    });
  }

  /** Get a value from a named object store by key. Returns null if not found. */
  async get(store: string, key: string): Promise<string | null> {
    if (!this.db) return null;

    return new Promise((resolve) => {
      try {
        const tx = this.db!.transaction(store, 'readonly');
        const objectStore = tx.objectStore(store);
        const request = objectStore.get(key);
        request.onsuccess = () => resolve(request.result ?? null);
        request.onerror = () => resolve(null);
      } catch {
        resolve(null);
      }
    });
  }

  /** Set a value in a named object store by key. */
  async set(store: string, key: string, value: string): Promise<void> {
    if (!this.db) return;

    return new Promise((resolve) => {
      try {
        const tx = this.db!.transaction(store, 'readwrite');
        const objectStore = tx.objectStore(store);
        objectStore.put(value, key);
        tx.oncomplete = () => resolve();
        tx.onerror = () => resolve();
      } catch {
        resolve();
      }
    });
  }

  /** Delete a value from a named object store by key. */
  async delete(store: string, key: string): Promise<void> {
    if (!this.db) return;

    return new Promise((resolve) => {
      try {
        const tx = this.db!.transaction(store, 'readwrite');
        const objectStore = tx.objectStore(store);
        objectStore.delete(key);
        tx.oncomplete = () => resolve();
        tx.onerror = () => resolve();
      } catch {
        resolve();
      }
    });
  }

  // --- Convenience methods for credential store ---

  /** Save server URL, username, and auth token to the credential store. */
  async saveCredentials(
    serverUrl: string,
    username: string,
    token: string,
  ): Promise<void> {
    await this.set(STORE_CREDENTIALS, 'serverUrl', serverUrl);
    await this.set(STORE_CREDENTIALS, 'username', username);
    await this.set(STORE_CREDENTIALS, 'token', token);
  }

  /** Load saved credentials. Any field may be null if not previously saved. */
  async loadCredentials(): Promise<{
    serverUrl: string | null;
    username: string | null;
    token: string | null;
  }> {
    return {
      serverUrl: await this.get(STORE_CREDENTIALS, 'serverUrl'),
      username: await this.get(STORE_CREDENTIALS, 'username'),
      token: await this.get(STORE_CREDENTIALS, 'token'),
    };
  }

  /** Clear all saved credentials. */
  async clearCredentials(): Promise<void> {
    await this.delete(STORE_CREDENTIALS, 'serverUrl');
    await this.delete(STORE_CREDENTIALS, 'username');
    await this.delete(STORE_CREDENTIALS, 'token');
  }

  // --- Convenience methods for preferences ---

  /** Save a named preference. */
  async savePreference(key: string, value: string): Promise<void> {
    await this.set(STORE_PREFERENCES, key, value);
  }

  /** Load a named preference. Returns null if not set. */
  async loadPreference(key: string): Promise<string | null> {
    return this.get(STORE_PREFERENCES, key);
  }
}

/** Singleton instance for use across the application. */
export const webStorage = new WebStorageService();
