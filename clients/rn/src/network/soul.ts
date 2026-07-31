/** Soul sync client — fetch, push, and version soul manifests via REST. */

import type { ClientSoulManifest } from '../engine/soul/SoulManifest';

function normalizeUrl(url: string): string {
  let u = url.trim().replace(/\/$/, '');
  if (!/^https?:\/\//i.test(u)) {
    u = 'http://' + u;
  }
  return u;
}

export interface VersionEntry {
  version: number;
  forged_at: number;
  content_hash: string;
}

export interface SyncResponse {
  status: string;
  version: number;
}

/**
 * GET /api/soul/{did} — fetch the latest soul manifest for a DID.
 */
export async function getSoulLatest(
  baseUrl: string,
  did: string,
  token: string,
): Promise<ClientSoulManifest> {
  const res = await fetch(
    `${normalizeUrl(baseUrl)}/api/soul/${encodeURIComponent(did)}?token=${token}`,
  );
  if (!res.ok) throw new Error(`getSoulLatest failed: ${res.status}`);
  return res.json();
}

/**
 * GET /api/soul/{did}/history — fetch version history for a DID.
 */
export async function getSoulHistory(
  baseUrl: string,
  did: string,
  token: string,
): Promise<VersionEntry[]> {
  const res = await fetch(
    `${normalizeUrl(baseUrl)}/api/soul/${encodeURIComponent(did)}/history?token=${token}`,
  );
  if (!res.ok) throw new Error(`getSoulHistory failed: ${res.status}`);
  return res.json();
}

/**
 * GET /api/soul/{did}/version/{version} — fetch a specific manifest version.
 */
export async function getSoulVersion(
  baseUrl: string,
  did: string,
  version: number,
  token: string,
): Promise<ClientSoulManifest> {
  const res = await fetch(
    `${normalizeUrl(baseUrl)}/api/soul/${encodeURIComponent(did)}/version/${version}?token=${token}`,
  );
  if (!res.ok) throw new Error(`getSoulVersion failed: ${res.status}`);
  return res.json();
}

/**
 * POST /api/soul/{did} — sync (push) a manifest to the server.
 */
export async function syncSoulManifest(
  baseUrl: string,
  did: string,
  manifest: ClientSoulManifest,
  token: string,
): Promise<SyncResponse> {
  const res = await fetch(
    `${normalizeUrl(baseUrl)}/api/soul/${encodeURIComponent(did)}?token=${token}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(manifest),
    },
  );
  if (!res.ok) throw new Error(`syncSoulManifest failed: ${res.status}`);
  return res.json();
}
