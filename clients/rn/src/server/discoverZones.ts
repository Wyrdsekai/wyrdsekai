/**
 * discoverZones — "Find a zone" over the opt-in ZoneDirectory
 *
 * Discovery is a separate, deliberate action from the bank. Only zones that
 * publish themselves to the directory appear; hidden zones never do, and a
 * relay's roster is never enumerated. From a discovered zone the user requests
 * access via a per-zone steward knock — that knock is a cross-zone path verified
 * against live infra (P6), so it is intentionally NOT wired here. This module is
 * the read-only discovery surface; each result carries `inBank` so the UI can
 * show "already in your servers" vs. "ask this zone's steward for an invite".
 */
import { useZoneBankStore } from '../state/zoneBankStore';
import type { NatsResult } from './NatsServerClient';

/** A zone surfaced by the directory, normalised for the UI. */
export interface DiscoveredZone {
  /** Zone label (subject scope) — the bank key when/if it's added. */
  zoneLabel: string;
  /** Decentralised id of the publishing zone. */
  did?: string;
  displayName?: string;
  tagline?: string;
  tags: string[];
  /** True if this zone is already in the user's bank (don't re-request). */
  inBank: boolean;
}

/** The slice of NatsServerClient this module needs — keeps it test-mockable. */
export interface DirectorySearchClient {
  searchDirectory(query?: string, limit?: number): Promise<NatsResult<Array<Record<string, unknown>>>>;
}

function str(v: unknown): string | undefined {
  return typeof v === 'string' && v.length > 0 ? v : undefined;
}

/**
 * Map raw directory manifests onto {@link DiscoveredZone}, dropping entries with
 * no zone label (unaddressable) and flagging the ones already banked. The bank
 * is consulted by zoneId === zoneLabel.
 */
export function normalizeDiscovered(raw: Array<Record<string, unknown>>): DiscoveredZone[] {
  const banked = new Set(useZoneBankStore.getState().zones.map((z) => z.zoneId));
  const out: DiscoveredZone[] = [];
  for (const m of raw) {
    if (!m || typeof m !== 'object') continue;
    const zoneLabel = str(m.zoneLabel) ?? str(m.zoneId);
    if (!zoneLabel) continue;
    out.push({
      zoneLabel,
      did: str(m.did),
      displayName: str(m.displayName),
      tagline: str(m.tagline),
      tags: Array.isArray(m.tags) ? (m.tags as unknown[]).filter((t): t is string => typeof t === 'string') : [],
      inBank: banked.has(zoneLabel),
    });
  }
  return out;
}

/**
 * Query the directory and return normalised results. Best-effort: a transport
 * failure yields an empty list with the error, never throws — discovery is a
 * convenience surface, not a critical path.
 */
export async function discoverZones(
  client: DirectorySearchClient,
  query = '',
  limit = 20,
): Promise<{ zones: DiscoveredZone[]; error?: string }> {
  const res = await client.searchDirectory(query, limit);
  if (!res.ok) {
    return { zones: [], error: res.error };
  }
  return { zones: normalizeDiscovered(res.data ?? []) };
}
