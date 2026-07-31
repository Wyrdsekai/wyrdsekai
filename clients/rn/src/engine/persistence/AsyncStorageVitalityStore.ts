/**
 * AsyncStorage-backed vitality store for React Native (Android/iOS).
 * Persists agent vitality state across app restarts.
 */

import type { VitalityState } from '../agent/VitalityState';
import type { VitalityStore } from './VitalityStore';

const VITALITY_PREFIX = '@wyrd_vitality:';

/** Minimal AsyncStorage interface — avoids hard import dependency. */
interface AsyncStorageLike {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
}

export class AsyncStorageVitalityStore implements VitalityStore {
  constructor(private readonly storage: AsyncStorageLike) {}

  async save(entityId: string, state: VitalityState): Promise<void> {
    try {
      await this.storage.setItem(VITALITY_PREFIX + entityId, JSON.stringify(state));
    } catch {
      // Non-fatal
    }
  }

  async load(entityId: string): Promise<VitalityState | null> {
    try {
      const data = await this.storage.getItem(VITALITY_PREFIX + entityId);
      if (!data) return null;
      return JSON.parse(data) as VitalityState;
    } catch {
      return null;
    }
  }
}
