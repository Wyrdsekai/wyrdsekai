/**
 * IndexedDB-backed vitality store for web ephemeral nodes.
 * Persists agent vitality state across browser sessions.
 */

import type { VitalityState } from '../engine/agent/VitalityState';
import type { VitalityStore } from '../engine/persistence/VitalityStore';
import { WebStorageService, webStorage } from './WebStorageService';

const VITALITY_STORE = 'state';
const VITALITY_PREFIX = 'vitality:';

export class IndexedDBVitalityStore implements VitalityStore {
  constructor(private readonly storage: WebStorageService = webStorage) {}

  async save(entityId: string, state: VitalityState): Promise<void> {
    try {
      await this.storage.set(VITALITY_STORE, VITALITY_PREFIX + entityId, JSON.stringify(state));
    } catch {
      // Non-fatal
    }
  }

  async load(entityId: string): Promise<VitalityState | null> {
    try {
      const data = await this.storage.get(VITALITY_STORE, VITALITY_PREFIX + entityId);
      if (!data) return null;
      return JSON.parse(data) as VitalityState;
    } catch {
      return null;
    }
  }
}
