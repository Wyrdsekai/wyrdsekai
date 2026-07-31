/**
 * Persistence for soul manifests.
 * Mirrors the VitalityStore pattern — interface + in-memory + AsyncStorage impls.
 */

import type { ClientSoulManifest } from '../soul/SoulManifest';

export interface SoulManifestStore {
  save(manifest: ClientSoulManifest): Promise<void>;
  load(did: string): Promise<ClientSoulManifest | null>;
  delete(did: string): Promise<void>;
  listDids(): Promise<string[]>;
}
