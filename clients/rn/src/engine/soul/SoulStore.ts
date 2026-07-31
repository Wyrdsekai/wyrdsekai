/**
 * Soul store — persist and retrieve soul manifests.
 *
 * SoulStore is the interface. HttpSoulStore implements it against the
 * server REST endpoints at /api/soul/{did}.
 */

import type { ClientSoulManifest } from './SoulManifest';
import {
  getSoulLatest,
  getSoulHistory,
  syncSoulManifest,
} from '../../network/soul';

export interface SoulStore {
  /** Save (sync) a manifest to the backing store. */
  save(manifest: ClientSoulManifest): Promise<void>;

  /** Load the latest manifest for a DID, or null if not found. */
  load(did: string): Promise<ClientSoulManifest | null>;

  /** List all DIDs known to this store. */
  listDids(): Promise<string[]>;

  /** Delete a DID's manifest from the store. */
  delete(did: string): Promise<void>;
}

/**
 * HTTP-backed soul store — delegates to the server's /api/soul endpoints.
 *
 * Follows the same stateless-function pattern as the rest of the RN network
 * layer, but wrapped in a class to satisfy the SoulStore interface with
 * pre-bound baseUrl and token.
 */
export class HttpSoulStore implements SoulStore {
  constructor(
    private baseUrl: string,
    private token: string,
  ) {}

  async save(manifest: ClientSoulManifest): Promise<void> {
    await syncSoulManifest(this.baseUrl, manifest.did, manifest, this.token);
  }

  async load(did: string): Promise<ClientSoulManifest | null> {
    try {
      return await getSoulLatest(this.baseUrl, did, this.token);
    } catch {
      // 404 or network error — no manifest on server
      return null;
    }
  }

  async listDids(): Promise<string[]> {
    // The server does not expose a "list all DIDs" endpoint.
    // This is a best-effort impl: callers should track known DIDs locally.
    // Returns empty — override in subclass if a list endpoint is added.
    return [];
  }

  async delete(did: string): Promise<void> {
    // Sync an empty-versioned manifest to signal deletion.
    // The server can interpret manifestVersion -1 as a tombstone.
    const tombstone: ClientSoulManifest = {
      did,
      publicKeyMultibase: '',
      manifestVersion: -1,
      forgedAt: Date.now(),
      agentName: '',
      entityId: '',
      residentIdentity: '',
      systemPrompt: '',
      contextWindowTokens: 0,
      maxResponseTokens: 0,
      temperature: 0,
    };
    await syncSoulManifest(this.baseUrl, did, tombstone, this.token);
  }
}

/**
 * In-memory soul store — useful for tests and offline bootstrapping.
 */
export class MemorySoulStore implements SoulStore {
  private manifests = new Map<string, ClientSoulManifest>();

  async save(manifest: ClientSoulManifest): Promise<void> {
    this.manifests.set(manifest.did, manifest);
  }

  async load(did: string): Promise<ClientSoulManifest | null> {
    return this.manifests.get(did) ?? null;
  }

  async listDids(): Promise<string[]> {
    return Array.from(this.manifests.keys());
  }

  async delete(did: string): Promise<void> {
    this.manifests.delete(did);
  }
}
