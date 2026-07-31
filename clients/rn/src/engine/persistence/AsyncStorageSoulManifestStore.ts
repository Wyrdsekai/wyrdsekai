/**
 * AsyncStorage-backed soul manifest store for React Native (Android/iOS).
 * Persists soul manifests across app restarts.
 *
 * Key scheme:
 *   @wyrd_soul:<did> → serialized ClientSoulManifest
 *   @wyrd_soul_index → JSON array of known DIDs
 */

import type { ClientSoulManifest } from '../soul/SoulManifest';
import { serializeManifest, deserializeManifest } from '../soul/SoulManifest';
import type { SoulManifestStore } from './SoulManifestStore';

const SOUL_PREFIX = '@wyrd_soul:';
const SOUL_INDEX_KEY = '@wyrd_soul_index';

/** Minimal AsyncStorage interface — avoids hard import dependency. */
interface AsyncStorageLike {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
}

export class AsyncStorageSoulManifestStore implements SoulManifestStore {
  constructor(private readonly storage: AsyncStorageLike) {}

  async save(manifest: ClientSoulManifest): Promise<void> {
    try {
      await this.storage.setItem(
        SOUL_PREFIX + manifest.did,
        serializeManifest(manifest),
      );
      // Update index
      const dids = await this.loadIndex();
      if (!dids.includes(manifest.did)) {
        dids.push(manifest.did);
        await this.storage.setItem(SOUL_INDEX_KEY, JSON.stringify(dids));
      }
    } catch {
      // Non-fatal
    }
  }

  async load(did: string): Promise<ClientSoulManifest | null> {
    try {
      const data = await this.storage.getItem(SOUL_PREFIX + did);
      if (!data) return null;
      return deserializeManifest(data);
    } catch {
      return null;
    }
  }

  async delete(did: string): Promise<void> {
    try {
      await this.storage.removeItem(SOUL_PREFIX + did);
      const dids = await this.loadIndex();
      const filtered = dids.filter(d => d !== did);
      await this.storage.setItem(SOUL_INDEX_KEY, JSON.stringify(filtered));
    } catch {
      // Non-fatal
    }
  }

  async listDids(): Promise<string[]> {
    return this.loadIndex();
  }

  private async loadIndex(): Promise<string[]> {
    try {
      const data = await this.storage.getItem(SOUL_INDEX_KEY);
      if (!data) return [];
      return JSON.parse(data) as string[];
    } catch {
      return [];
    }
  }
}
