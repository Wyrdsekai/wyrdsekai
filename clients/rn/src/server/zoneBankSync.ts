/**
 * zoneBankSync — cross-device sync of the zone bank.
 *
 * The bank (the address book of zones) is sovereign account state mirrored on
 * the user's home zone. Each authenticated device pulls it on connect and
 * pushes its merged view back, so every device the user signs in on converges
 * to the union of their zones.
 *
 * What syncs: the `zones[]` address book only. What does NOT:
 *   • held relays  — transport credentials (natsUser/natsPass) are per-device
 *     secrets, pinned via TOFU from invites. Never travel through the bank blob.
 *   • zone passwords — per-device secure storage (@wyrd_zone_pw_*), §4.4.
 *
 * Merge is per-entry last-write-wins, keyed by zoneId. "Newest" = the larger of
 * (lastUsedAt, addedAt) — a zone touched more recently on any device wins. The
 * merge is symmetric and idempotent: pull → merge newest-wins into local → push
 * the merged superset. Two devices that both run this converge.
 *
 * The server (McpNatsHandler account.zonebank.{get,put}) is a dumb blob store
 * keyed by the caller's account; the LWW logic lives here on the client.
 */
import { useZoneBankStore, type ZoneBankEntry } from '../state/zoneBankStore';
import type { NatsResult } from './NatsServerClient';

/** The slice of NatsServerClient this module needs — keeps it test-mockable. */
export interface ZoneBankSyncClient {
  getZoneBank(): Promise<NatsResult<{ bank: string | null; updatedAt: number }>>;
  putZoneBank(bankJson: string, updatedAt: number): Promise<NatsResult<{ updatedAt: number }>>;
}

/** Recency stamp used for per-entry LWW. */
function entryTs(z: ZoneBankEntry): number {
  return Math.max(z.lastUsedAt ?? 0, z.addedAt ?? 0);
}

/**
 * Merge a remote zones[] array into the local store, per-entry newest-wins.
 * Relay credentials are NOT carried in the blob, so a synced entry may name
 * relayUrls this device hasn't pinned yet — that's fine; relaysForZone() falls
 * back to all held relays. Returns the number of entries added or updated.
 */
export function mergeRemoteZones(remote: ZoneBankEntry[]): number {
  const store = useZoneBankStore.getState();
  let changed = 0;
  for (const r of remote) {
    if (!r || typeof r.zoneId !== 'string' || !r.zoneId) continue;
    const local = store.getZone(r.zoneId);
    if (!local || entryTs(r) > entryTs(local)) {
      // addOrUpdateZone unions relayUrls + keeps local addedAt; for a brand-new
      // remote zone it inserts wholesale. Both are correct for a newest-wins
      // remote entry.
      store.addOrUpdateZone({
        zoneId: r.zoneId,
        displayName: r.displayName ?? r.zoneId,
        relayUrls: Array.isArray(r.relayUrls) ? r.relayUrls : [],
        username: r.username ?? '',
        homeZone: r.homeZone,
        lastUsedAt: r.lastUsedAt,
      });
      changed++;
    }
  }
  return changed;
}

/** Serialize the current local bank for upload — zones only, no secrets. */
export function serializeBank(): string {
  return JSON.stringify(useZoneBankStore.getState().zones);
}

function parseRemote(bank: string | null): ZoneBankEntry[] {
  if (!bank) return [];
  try {
    const parsed = JSON.parse(bank);
    return Array.isArray(parsed) ? (parsed as ZoneBankEntry[]) : [];
  } catch {
    return [];
  }
}

export interface SyncResult {
  ok: boolean;
  pulled: number; // entries merged in from the server
  pushed: boolean; // whether we uploaded the merged bank
  error?: string;
}

/**
 * Full sync against the connected zone: pull the server's bank, merge it
 * newest-wins into the local store, then push the merged superset back so the
 * server (and thus the user's other devices) converges. Best-effort — a sync
 * failure never blocks the session; the local bank remains authoritative on
 * this device.
 *
 * @param now monotonic-ish timestamp for the push stamp (Date.now()); passed in
 *            so callers/tests control it and the module stays pure.
 */
export async function syncZoneBank(
  client: ZoneBankSyncClient,
  now: number,
): Promise<SyncResult> {
  const got = await client.getZoneBank();
  if (!got.ok) {
    return { ok: false, pulled: 0, pushed: false, error: got.error };
  }
  const remote = parseRemote(got.data?.bank ?? null);
  const pulled = mergeRemoteZones(remote);

  // Push the merged view. We push even when pulled===0: this device may hold
  // zones the server hasn't seen yet (first sync from a device that onboarded
  // offline). Skip only when the local bank is empty AND nothing came down.
  const localZones = useZoneBankStore.getState().zones;
  if (localZones.length === 0) {
    return { ok: true, pulled, pushed: false };
  }
  const put = await client.putZoneBank(serializeBank(), now);
  if (!put.ok) {
    return { ok: true, pulled, pushed: false, error: put.error };
  }
  return { ok: true, pulled, pushed: true };
}
