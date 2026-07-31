/**
 * Soul Seed Importer — Wave 5 of Phone Forge plan.
 *
 * Supports two import paths:
 *   1. Local JSON file: parse a .soul.json string into a ClientSoulManifest.
 *   2. Household server: fetch available souls from GET /api/soul/list and
 *      download a specific soul by DID from GET /api/soul/{did}.
 *
 * All methods are null-safe: network or parse errors return null / empty array.
 * Uses plain fetch() — no additional dependencies needed.
 */

import type { ClientSoulManifest } from './SoulManifest';

/** Lightweight entry returned by the household list endpoint. */
export interface SoulListEntry {
  did: string;
  agentName: string;
  manifestVersion: number;
  forgedAt: number;
}

function normalizeUrl(url: string): string {
  let u = url.trim().replace(/\/$/, '');
  if (!/^https?:\/\//i.test(u)) {
    u = 'http://' + u;
  }
  return u;
}

// ---------------------------------------------------------------------------
// Local import
// ---------------------------------------------------------------------------

/**
 * Import a soul manifest from a JSON string (.soul.json format).
 * Returns null on any parse error or if required fields are missing.
 */
export function importFromJson(jsonString: string): ClientSoulManifest | null {
  try {
    const manifest: ClientSoulManifest = JSON.parse(jsonString);
    return validateManifest(manifest) ? manifest : null;
  } catch {
    return null;
  }
}

/**
 * Validate that a manifest has the minimum required fields for use as a soul seed.
 */
export function validateManifest(manifest: ClientSoulManifest): boolean {
  return (
    typeof manifest.did === 'string' &&
    manifest.did.length > 0 &&
    typeof manifest.agentName === 'string' &&
    manifest.agentName.length > 0 &&
    typeof manifest.residentIdentity === 'string' &&
    manifest.residentIdentity.length > 0 &&
    typeof manifest.systemPrompt === 'string' &&
    manifest.systemPrompt.length > 0
  );
}

// ---------------------------------------------------------------------------
// Export
// ---------------------------------------------------------------------------

/**
 * Export a soul manifest as a JSON string (.soul.json format).
 * The string can be shared, saved to a file, or pasted into another device's import.
 */
export function exportToJson(manifest: ClientSoulManifest): string {
  return JSON.stringify(manifest, null, 2);
}

// ---------------------------------------------------------------------------
// Household server import
// ---------------------------------------------------------------------------

/**
 * Fetch the list of available souls from a household server.
 *
 * Calls GET {serverUrl}/api/soul/list?token={token}.
 * Returns an empty array on any error (network, parse, auth).
 * Times out after 5 seconds.
 */
export async function fetchHouseholdSouls(
  serverUrl: string,
  token?: string,
): Promise<SoulListEntry[]> {
  try {
    const base = normalizeUrl(serverUrl);
    const params = token ? `?token=${encodeURIComponent(token)}` : '';
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 5000);

    const res = await fetch(`${base}/api/soul/list${params}`, {
      signal: controller.signal,
    });
    clearTimeout(timeoutId);

    if (!res.ok) return [];
    return await res.json();
  } catch {
    return [];
  }
}

/**
 * Import a specific soul manifest from a household server by DID.
 *
 * Calls GET {serverUrl}/api/soul/{did}?token={token}.
 * Returns null on any error (network, parse, auth, 404).
 * Times out after 5 seconds.
 */
export async function importFromHousehold(
  serverUrl: string,
  did: string,
  token?: string,
): Promise<ClientSoulManifest | null> {
  try {
    const base = normalizeUrl(serverUrl);
    const params = token ? `?token=${encodeURIComponent(token)}` : '';
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 5000);

    const res = await fetch(
      `${base}/api/soul/${encodeURIComponent(did)}${params}`,
      { signal: controller.signal },
    );
    clearTimeout(timeoutId);

    if (!res.ok) return null;
    const manifest: ClientSoulManifest = await res.json();
    return validateManifest(manifest) ? manifest : null;
  } catch {
    return null;
  }
}
