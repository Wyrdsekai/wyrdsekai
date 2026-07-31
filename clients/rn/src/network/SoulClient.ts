/**
 * SoulClient — lightweight HTTP client for the server's soul REST API.
 *
 * Mirrors the KMP SoulClient pattern (Ktor + ContentNegotiation) but uses
 * fetch() for React Native. Offline-first: returns null on any error rather
 * than throwing, so callers never need try/catch for network failures.
 *
 * Endpoints:
 * - GET  {serverUrl}/api/soul/{did}        — latest manifest
 * - POST {serverUrl}/api/soul/{did}        — sync (upload) manifest
 *
 * Authentication via query parameter ?token=...
 */

import type { ClientSoulManifest } from '../engine/soul/SoulManifest';

export interface SyncResponse {
  status: string;
  version: number;
}

function normalizeUrl(url: string): string {
  let u = url.trim().replace(/\/$/, '');
  if (!/^https?:\/\//i.test(u)) {
    u = 'http://' + u;
  }
  return u;
}

function soulUrl(serverUrl: string, did: string, token?: string): string {
  const base = `${normalizeUrl(serverUrl)}/api/soul/${encodeURIComponent(did)}`;
  return token ? `${base}?token=${encodeURIComponent(token)}` : base;
}

/**
 * Fetch the latest soul manifest for a DID from the server.
 * Returns null on any error (network, 404, parse failure).
 */
export async function getLatestManifest(
  serverUrl: string,
  did: string,
  token?: string,
): Promise<ClientSoulManifest | null> {
  try {
    const res = await fetch(soulUrl(serverUrl, did, token));
    if (!res.ok) return null;
    return (await res.json()) as ClientSoulManifest;
  } catch {
    return null;
  }
}

/**
 * Sync (push) a manifest to the server.
 * Returns null on any error (offline-first, non-fatal).
 */
export async function syncManifest(
  serverUrl: string,
  did: string,
  manifest: ClientSoulManifest,
  token?: string,
): Promise<SyncResponse | null> {
  try {
    const res = await fetch(soulUrl(serverUrl, did, token), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(manifest),
    });
    if (!res.ok) return null;
    return (await res.json()) as SyncResponse;
  } catch {
    return null;
  }
}
