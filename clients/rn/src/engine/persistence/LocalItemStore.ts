/**
 * Local persistence for PhoneSoulItems.
 * TypeScript port adapted from the AsyncStorage pattern used by
 * AsyncStorageVitalityStore and AsyncStorageSoulManifestStore.
 *
 * Key prefix: `@wyrd-items:`
 * Index stored in `@wyrd-items:_index` as JSON array of hashes.
 */

import type { PhoneSoulItem } from '../item/PhoneSoulItem';

// ---------------------------------------------------------------------------
// Interface
// ---------------------------------------------------------------------------

export interface LocalItemStore {
  /** Store an item. Overwrites if hash already exists. */
  store(item: PhoneSoulItem): Promise<void>;

  /** Get an item by its content hash, or null. */
  get(hash: string): Promise<PhoneSoulItem | null>;

  /** Get all items matching a category. */
  byCategory(category: string): Promise<PhoneSoulItem[]>;

  /** Get all items matching a label (case-insensitive). */
  byLabel(label: string): Promise<PhoneSoulItem[]>;

  /** Get all stored items. */
  all(): Promise<PhoneSoulItem[]>;

  /** Remove an item by hash. Returns true if it existed. */
  remove(hash: string): Promise<boolean>;
}

// ---------------------------------------------------------------------------
// AsyncStorage implementation
// ---------------------------------------------------------------------------

const KEY_PREFIX = '@wyrd-items:';
const INDEX_KEY = `${KEY_PREFIX}_index`;

/** Minimal AsyncStorage interface -- avoids hard import dependency. */
interface AsyncStorageLike {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
  multiGet(keys: string[]): Promise<readonly [string, string | null][]>;
}

export class AsyncStorageItemStore implements LocalItemStore {
  constructor(private readonly storage: AsyncStorageLike) {}

  async store(item: PhoneSoulItem): Promise<void> {
    try {
      const key = KEY_PREFIX + item.hash;
      await this.storage.setItem(key, JSON.stringify(item));

      // Update index
      const index = await this.loadIndex();
      if (!index.includes(item.hash)) {
        index.push(item.hash);
        await this.saveIndex(index);
      }
    } catch {
      // Non-fatal -- best effort persistence
    }
  }

  async get(hash: string): Promise<PhoneSoulItem | null> {
    try {
      const key = KEY_PREFIX + hash;
      const data = await this.storage.getItem(key);
      if (!data) return null;
      return JSON.parse(data) as PhoneSoulItem;
    } catch {
      return null;
    }
  }

  async byCategory(category: string): Promise<PhoneSoulItem[]> {
    const items = await this.all();
    return items.filter(item => item.category === category);
  }

  async byLabel(label: string): Promise<PhoneSoulItem[]> {
    const lower = label.toLowerCase();
    const items = await this.all();
    return items.filter(item => item.label.toLowerCase() === lower);
  }

  async all(): Promise<PhoneSoulItem[]> {
    try {
      const index = await this.loadIndex();
      if (index.length === 0) return [];

      const keys = index.map(hash => KEY_PREFIX + hash);
      const entries = await this.storage.multiGet(keys);

      const items: PhoneSoulItem[] = [];
      for (const [, value] of entries) {
        if (value) {
          try {
            items.push(JSON.parse(value) as PhoneSoulItem);
          } catch {
            // Skip corrupted entries
          }
        }
      }
      return items;
    } catch {
      return [];
    }
  }

  async remove(hash: string): Promise<boolean> {
    try {
      const index = await this.loadIndex();
      const idx = index.indexOf(hash);
      if (idx < 0) return false;

      // Remove from storage
      await this.storage.removeItem(KEY_PREFIX + hash);

      // Remove from index
      index.splice(idx, 1);
      await this.saveIndex(index);
      return true;
    } catch {
      return false;
    }
  }

  // --- Internal ---

  private async loadIndex(): Promise<string[]> {
    try {
      const data = await this.storage.getItem(INDEX_KEY);
      if (!data) return [];
      return JSON.parse(data) as string[];
    } catch {
      return [];
    }
  }

  private async saveIndex(index: string[]): Promise<void> {
    await this.storage.setItem(INDEX_KEY, JSON.stringify(index));
  }
}
