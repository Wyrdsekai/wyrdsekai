/**
 * SoulSyncManager — coordinates soul manifest synchronization between
 * the local device and the household server.
 *
 * Offline-first: all server communication is best-effort. If the server
 * is unreachable, the local manifest remains authoritative.
 *
 * Key behavior:
 * - Pull: if the server has a newer manifest version, replace local —
 *   but PRESERVE the user-chosen companion name (the user named Ma,
 *   she stays Ma even if the server says "Wyrd").
 * - Push: after a Forge/sleep cycle, upload the new manifest to the server.
 * - Bootstrap detection: manifests with "did:key:bootstrap-" DIDs are
 *   considered bootstrap (not yet replaced by a real Forge).
 */

import type { ClientSoulManifest } from './SoulManifest';
import type { SoulManifestStore } from '../persistence/SoulManifestStore';
import { getLatestManifest, syncManifest } from '../../network/SoulClient';

export class SoulSyncManager {
  private lastSyncTime: number | null = null;
  private lastSyncVersion: number | null = null;

  constructor(
    private readonly soulManifestStore: SoulManifestStore,
    private readonly serverUrl: string,
    private readonly token?: string,
  ) {}

  /**
   * Try to pull a newer manifest from the server.
   *
   * If the server has a newer version, replaces local — but preserves
   * the user-chosen companion name (the user named Ma, she stays Ma
   * even if the server says "Wyrd").
   *
   * @param currentDid   DID to query on the server
   * @param currentName  User-chosen companion name to preserve
   * @returns The updated manifest, or null if no update was needed/available
   */
  async tryPullFromServer(
    currentDid: string,
    currentName?: string,
  ): Promise<ClientSoulManifest | null> {
    // Fetch latest from server (returns null if unreachable)
    const serverManifest = await getLatestManifest(
      this.serverUrl,
      currentDid,
      this.token,
    );
    if (!serverManifest) return null;

    // Check if server has a newer version than local
    const localManifest = await this.soulManifestStore.load(currentDid);
    const localVersion = localManifest?.manifestVersion ?? -1;
    if (serverManifest.manifestVersion <= localVersion) return null;

    // Preserve the user-chosen companion name if it differs from server's
    let merged = serverManifest;
    if (currentName && currentName !== serverManifest.agentName) {
      merged = { ...serverManifest, agentName: currentName };
    }

    // Save to local store
    await this.soulManifestStore.save(merged);

    // Update sync metadata
    this.lastSyncTime = Date.now();
    this.lastSyncVersion = merged.manifestVersion;

    return merged;
  }

  /**
   * Push local manifest to server after a Forge/sleep cycle.
   * Returns true if the server accepted the manifest, false otherwise.
   */
  async pushToServer(manifest: ClientSoulManifest): Promise<boolean> {
    const response = await syncManifest(
      this.serverUrl,
      manifest.did,
      manifest,
      this.token,
    );
    if (response) {
      this.lastSyncTime = Date.now();
      this.lastSyncVersion = response.version;
      return true;
    }
    return false;
  }

  /**
   * Check if a manifest is a bootstrap (not yet replaced by a real Forge).
   * Bootstrap manifests have DIDs matching "did:key:bootstrap-*".
   */
  isBootstrap(manifest: ClientSoulManifest): boolean {
    return manifest.did.startsWith('did:key:bootstrap-');
  }

  /** Last sync time as epoch millis, or null if never synced. */
  getLastSyncTime(): number | null {
    return this.lastSyncTime;
  }

  /** Last synced manifest version, or null if never synced. */
  getLastSyncVersion(): number | null {
    return this.lastSyncVersion;
  }
}
