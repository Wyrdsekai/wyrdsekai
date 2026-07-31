/**
 * zoneBankStore — the phone's HELD RELAYS + ZONE BANK.
 *
 * Two lists, both encrypted (secureStorage / MMKV):
 *
 *   • relays[] — held transport credentials. A relay is dumb plumbing: pin it
 *     once (TOFU on caFp) from an invite/join, then never think about it again.
 *     The phone holds SEVERAL.
 *
 *   • zones[]  — the user's ADDRESS BOOK of zones they can reach. This is the
 *     ONLY routing source: to open a zone we look it up here, take its
 *     relayUrls, and auto-attempt the login across whichever held relay(s)
 *     reach it. The phone NEVER enumerates a relay — a public/commons relay
 *     with thousands of zones is a non-issue because the phone's surface is
 *     YOUR zones, not the relay's roster.
 *
 * Secrets: a zone entry stores `username` but NEVER the password. The password
 * lives in per-device secure storage (`@wyrd_zone_pw_{zoneId}`); first use of a
 * synced zone on a new device prompts once, then remembers locally. See
 *
 * Sync across the user's own devices (§4) is layered on top in P3: the bank is
 * mirrored as account state on the user's home zone. This store is the local
 * cache + the merge target.
 */
import { create } from 'zustand';
import { secureStorage as AsyncStorage } from './secureStorage';

const KEY_RELAYS = '@wyrd_held_relays';
const KEY_ZONES = '@wyrd_zone_bank';

/** A held relay = transport credential. Dumb plumbing, pinned once. */
export interface HeldRelay {
  /** wss://host:port — the relay's NATS-over-WebSocket endpoint. */
  wsUrl: string;
  /** Household CA SHA-256 (colon-hex) for TOFU pin; absent on web-PKI relays. */
  caFp?: string;
  /** Relay leaf-cert SHA-256 (colon-hex). The relay serves a self-signed leaf
   *  (chain length 1) — its fp, NOT the CA's, is what the served cert matches,
   *  so pinning must offer both. Absent on web-PKI relays. */
  fp?: string;
  /** Relay NATS credentials (transport auth, not account auth). */
  natsUser: string;
  natsPass: string;
  /** Optional human label. */
  label?: string;
  addedAt: number;
}

/** A zone bank entry = one server the user has access to. */
export interface ZoneBankEntry {
  /** Canonical zone id — subject scope wyrd.zone.{zoneId}.* */
  zoneId: string;
  /** Human label ("home-server", "example-relay Commons"). */
  displayName: string;
  /** wsUrls of held relays that reach this zone, in preference order. */
  relayUrls: string[];
  /** YOUR account name on this zone. The password is NOT stored here. */
  username: string;
  /** True for the user's home zone (the sync anchor, §4.1). */
  homeZone?: boolean;
  addedAt: number;
  lastUsedAt?: number;
}

interface ZoneBankState {
  relays: HeldRelay[];
  zones: ZoneBankEntry[];
  loaded: boolean;

  loadFromStorage: () => Promise<void>;

  /** Add/refresh a held relay (dedupe by wsUrl; updates creds/label in place). */
  addRelay: (relay: Omit<HeldRelay, 'addedAt'> & { addedAt?: number }) => void;
  removeRelay: (wsUrl: string) => void;

  /** Add/update a zone (last-write-wins by zoneId; merges relayUrls). */
  addOrUpdateZone: (zone: Omit<ZoneBankEntry, 'addedAt'> & { addedAt?: number }) => void;
  removeZone: (zoneId: string) => void;
  /** Bump lastUsedAt (call after a successful open). */
  touchZone: (zoneId: string) => void;
  /** Move (or add) a relay to the front of a zone's preference order — call
   *  with the relay that just succeeded so next open tries it first. */
  bumpRelay: (zoneId: string, wsUrl: string) => void;
  /** Mark exactly one zone as the home/anchor; clears the flag on others. */
  setHomeZone: (zoneId: string) => void;

  getZone: (zoneId: string) => ZoneBankEntry | undefined;
  /** Held relays that reach a zone, in the entry's preference order. */
  relaysForZone: (zoneId: string) => HeldRelay[];
  /** The home/anchor zone, if set. */
  homeZone: () => ZoneBankEntry | undefined;
}

function persistRelays(relays: HeldRelay[]): void {
  AsyncStorage.setItem(KEY_RELAYS, JSON.stringify(relays)).catch(() => {});
}
function persistZones(zones: ZoneBankEntry[]): void {
  AsyncStorage.setItem(KEY_ZONES, JSON.stringify(zones)).catch(() => {});
}

export const useZoneBankStore = create<ZoneBankState>((set, get) => ({
  relays: [],
  zones: [],
  loaded: false,

  loadFromStorage: async () => {
    try {
      const [rawRelays, rawZones] = await Promise.all([
        AsyncStorage.getItem(KEY_RELAYS),
        AsyncStorage.getItem(KEY_ZONES),
      ]);
      const relays = rawRelays ? (JSON.parse(rawRelays) as HeldRelay[]) : [];
      const zones = rawZones ? (JSON.parse(rawZones) as ZoneBankEntry[]) : [];
      set({
        relays: Array.isArray(relays) ? relays : [],
        zones: Array.isArray(zones) ? zones : [],
        loaded: true,
      });
    } catch {
      set({ relays: [], zones: [], loaded: true });
    }
  },

  addRelay: (relay) => {
    const now = relay.addedAt ?? Date.now();
    const existing = get().relays;
    const idx = existing.findIndex((r) => r.wsUrl === relay.wsUrl);
    let next: HeldRelay[];
    if (idx >= 0) {
      // Refresh creds/label/fp in place, keep original addedAt.
      next = existing.slice();
      next[idx] = { ...existing[idx], ...relay, addedAt: existing[idx].addedAt };
    } else {
      next = [...existing, { ...relay, addedAt: now }];
    }
    set({ relays: next });
    persistRelays(next);
  },

  removeRelay: (wsUrl) => {
    const next = get().relays.filter((r) => r.wsUrl !== wsUrl);
    set({ relays: next });
    persistRelays(next);
  },

  addOrUpdateZone: (zone) => {
    const now = zone.addedAt ?? Date.now();
    const existing = get().zones;
    const idx = existing.findIndex((z) => z.zoneId === zone.zoneId);
    let next: ZoneBankEntry[];
    if (idx >= 0) {
      const prev = existing[idx];
      // Merge relayUrls (union, preserve prev preference order first).
      const mergedRelays = [...prev.relayUrls];
      for (const u of zone.relayUrls ?? []) {
        if (!mergedRelays.includes(u)) mergedRelays.push(u);
      }
      next = existing.slice();
      next[idx] = {
        ...prev,
        ...zone,
        relayUrls: mergedRelays,
        addedAt: prev.addedAt,
        lastUsedAt: zone.lastUsedAt ?? prev.lastUsedAt,
      };
    } else {
      next = [...existing, { ...zone, addedAt: now }];
    }
    set({ zones: next });
    persistZones(next);
  },

  removeZone: (zoneId) => {
    const next = get().zones.filter((z) => z.zoneId !== zoneId);
    set({ zones: next });
    persistZones(next);
  },

  touchZone: (zoneId) => {
    const next = get().zones.map((z) =>
      z.zoneId === zoneId ? { ...z, lastUsedAt: Date.now() } : z,
    );
    set({ zones: next });
    persistZones(next);
  },

  bumpRelay: (zoneId, wsUrl) => {
    const next = get().zones.map((z) =>
      z.zoneId === zoneId
        ? { ...z, relayUrls: [wsUrl, ...z.relayUrls.filter((u) => u !== wsUrl)] }
        : z,
    );
    set({ zones: next });
    persistZones(next);
  },

  setHomeZone: (zoneId) => {
    const next = get().zones.map((z) => ({ ...z, homeZone: z.zoneId === zoneId }));
    set({ zones: next });
    persistZones(next);
  },

  getZone: (zoneId) => get().zones.find((z) => z.zoneId === zoneId),

  relaysForZone: (zoneId) => {
    const zone = get().zones.find((z) => z.zoneId === zoneId);
    if (!zone) return [];
    const held = get().relays;
    // Preserve the entry's preference order; fall back to all held relays if
    // the entry names none (e.g. a freshly synced entry from another device
    // that knew the zone by a relay this device hasn't pinned yet).
    const byUrl = zone.relayUrls
      .map((u) => held.find((r) => r.wsUrl === u))
      .filter((r): r is HeldRelay => !!r);
    return byUrl.length > 0 ? byUrl : held;
  },

  homeZone: () => get().zones.find((z) => z.homeZone),
}));

/** Per-device password storage for a zone (NEVER synced — §4.4). */
export const zonePasswordKey = (zoneId: string): string => `@wyrd_zone_pw_${zoneId}`;
