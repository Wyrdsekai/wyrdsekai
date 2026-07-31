/**
 * In-memory VitalityStore — no platform deps, useful for tests and mobile default.
 */

import type { VitalityState } from '../agent/VitalityState';
import type { VitalityStore } from './VitalityStore';

export class InMemoryVitalityStore implements VitalityStore {
  private store = new Map<string, VitalityState>();

  async save(entityId: string, state: VitalityState): Promise<void> {
    this.store.set(entityId, { ...state });
  }

  async load(entityId: string): Promise<VitalityState | null> {
    const s = this.store.get(entityId);
    return s ? { ...s } : null;
  }

  clear(): void {
    this.store.clear();
  }
}
