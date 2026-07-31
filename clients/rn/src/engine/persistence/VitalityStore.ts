/**
 * Persistence for agent vitality state.
 * TypeScript port of KMP's VitalityStore.kt.
 */

import type { VitalityState } from '../agent/VitalityState';

export interface VitalityStore {
  save(entityId: string, state: VitalityState): Promise<void>;
  load(entityId: string): Promise<VitalityState | null>;
}
