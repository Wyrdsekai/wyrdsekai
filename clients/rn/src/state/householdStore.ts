/**
 * Zustand store for household/Between network state.
 *
 * Tracks connectivity to the household server, connected nodes,
 * and user-configurable URLs for LAN and relay connections.
 *
 */

import { create } from 'zustand';
import { Platform } from 'react-native';
import type { ConnectivityState } from '../engine/discovery/types';
import type { PresenceState } from '../engine/between/PresenceManager';

/** A node visible on the household Between network. */
export interface NodePresence {
  nodeId: string;
  status: string;
  tier?: string;
  timestamp: number;
}

interface HouseholdState {
  /** Current connectivity state of the Between connection. */
  connectivityState: ConnectivityState;
  /** Online nodes discovered via PresenceManager. */
  connectedNodes: NodePresence[];
  /** Household identifier (from mDNS discovery or saved config). */
  householdId: string | null;
  /** Household server NATS WebSocket URL (manual entry or discovered). */
  householdUrl: string;
  /** Cloud relay URL for when LAN is unavailable. */
  relayUrl: string;
  /** Whether mDNS auto-discovery is enabled. */
  autoDiscover: boolean;

  // Actions
  setConnectivityState: (state: ConnectivityState) => void;
  setConnectedNodes: (nodes: NodePresence[]) => void;
  setHouseholdId: (id: string | null) => void;
  setHouseholdUrl: (url: string) => void;
  setRelayUrl: (url: string) => void;
  setAutoDiscover: (enabled: boolean) => void;
  /** Update nodes from a PresenceManager presence map. */
  updateFromPresenceMap: (map: Map<string, PresenceState>) => void;
  /** Connect action — sets state to DISCOVERING. */
  connect: () => void;
  /** Disconnect action — clears nodes, sets OFFLINE. */
  disconnect: () => void;
}

// Persistence helpers (same pattern as preferencesStore)
async function savePref(key: string, value: string) {
  try {
    if (Platform.OS === 'web') {
      localStorage.setItem(`wyrd_household_${key}`, value);
    } else {
      const AS = require('@react-native-async-storage/async-storage').default;
      await AS.setItem(`wyrd_household_${key}`, value);
    }
  } catch { /* best effort */ }
}

async function loadPref(key: string): Promise<string | null> {
  try {
    if (Platform.OS === 'web') {
      return localStorage.getItem(`wyrd_household_${key}`);
    } else {
      const AS = require('@react-native-async-storage/async-storage').default;
      return await AS.getItem(`wyrd_household_${key}`);
    }
  } catch {
    return null;
  }
}

export const useHouseholdStore = create<HouseholdState>((set) => ({
  connectivityState: 'OFFLINE',
  connectedNodes: [],
  householdId: null,
  householdUrl: '',
  relayUrl: '',
  autoDiscover: true,

  setConnectivityState: (state) => set({ connectivityState: state }),

  setConnectedNodes: (nodes) => set({ connectedNodes: nodes }),

  setHouseholdId: (id) => set({ householdId: id }),

  setHouseholdUrl: (url) => {
    set({ householdUrl: url });
    savePref('url', url);
  },

  setRelayUrl: (url) => {
    set({ relayUrl: url });
    savePref('relay_url', url);
  },

  setAutoDiscover: (enabled) => {
    set({ autoDiscover: enabled });
    savePref('auto_discover', String(enabled));
  },

  updateFromPresenceMap: (map) => {
    const nodes: NodePresence[] = [];
    map.forEach((state, _nodeId) => {
      nodes.push({
        nodeId: state.nodeId,
        status: state.status,
        tier: state.tier,
        timestamp: state.timestamp,
      });
    });
    set({ connectedNodes: nodes });
  },

  connect: () => set({ connectivityState: 'DISCOVERING' }),

  disconnect: () => set({
    connectivityState: 'OFFLINE',
    connectedNodes: [],
  }),
}));

/** Load persisted household settings into the store. */
export async function loadHouseholdSettings(): Promise<void> {
  const url = await loadPref('url');
  const relayUrl = await loadPref('relay_url');
  const autoDiscover = await loadPref('auto_discover');
  useHouseholdStore.setState({
    ...(url != null ? { householdUrl: url } : {}),
    ...(relayUrl != null ? { relayUrl } : {}),
    ...(autoDiscover != null ? { autoDiscover: autoDiscover !== 'false' } : {}),
  });
}
