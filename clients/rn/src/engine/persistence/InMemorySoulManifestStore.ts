/**
 * In-memory SoulManifestStore — no platform deps, useful for tests and mobile default.
 */

import type { ClientSoulManifest } from '../soul/SoulManifest';
import type { SoulManifestStore } from './SoulManifestStore';

export class InMemorySoulManifestStore implements SoulManifestStore {
  private store = new Map<string, ClientSoulManifest>();

  async save(manifest: ClientSoulManifest): Promise<void> {
    this.store.set(manifest.did, { ...manifest });
  }

  async load(did: string): Promise<ClientSoulManifest | null> {
    const m = this.store.get(did);
    return m ? { ...m } : null;
  }

  async delete(did: string): Promise<void> {
    this.store.delete(did);
  }

  async listDids(): Promise<string[]> {
    return [...this.store.keys()];
  }

  clear(): void {
    this.store.clear();
  }
}
