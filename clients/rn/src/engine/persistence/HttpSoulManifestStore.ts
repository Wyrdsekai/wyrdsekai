import type { ClientSoulManifest } from '../soul/SoulManifest';
import type { SoulManifestStore } from './SoulManifestStore';
import { getLatestManifest, syncManifest } from '../../network/SoulClient';

/**
 * #7 (2026-07-19 OSS hardening) — a {@link SoulManifestStore} backed by the
 * server's soul REST API. Used as a SYNC TARGET (not the sole source of truth):
 * the phone keeps a local store and additionally pushes evolved manifests here
 * so phone-side soul evolution (PhoneForge on sleep) reaches the household.
 *
 * TS port of the KMP HttpSoulManifestStore. save() is best-effort — the
 * underlying syncManifest returns null on failure rather than throwing, so a
 * network error is silently retried on the next sleep.
 */
export class HttpSoulManifestStore implements SoulManifestStore {
  constructor(
    private readonly serverUrl: string,
    private readonly token?: string,
  ) {}

  async save(manifest: ClientSoulManifest): Promise<void> {
    await syncManifest(this.serverUrl, manifest.did, manifest, this.token);
  }

  async load(did: string): Promise<ClientSoulManifest | null> {
    return getLatestManifest(this.serverUrl, did, this.token);
  }

  async delete(_did: string): Promise<void> {
    // No server delete endpoint yet — no-op.
  }

  async listDids(): Promise<string[]> {
    // The server soul API is queried by DID; enumeration isn't exposed.
    return [];
  }
}
