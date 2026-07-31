/**
 * Network discovery types for the phone node.
 *
 * Matches KMP data classes:
 * - DiscoveredHousehold: mDNS discovery result
 * - SavedHouseholdConfig: persisted fallback configuration
 * - ConnectivityState: cascade connection state
 *
 */

/** Information discovered about a household server via mDNS. */
export interface DiscoveredHousehold {
  householdId: string;
  householdName: string;
  natsWsUrl: string;
  relayUrl: string | null;
  relayToken: string | null;
  version: string;
}

/** Persisted household configuration for fallback when mDNS is unavailable. */
export interface SavedHouseholdConfig {
  householdId: string;
  householdName: string;
  natsWsUrl: string;
  relayUrl: string | null;
  relayToken: string | null;
  lastConnected: number;
}

/**
 * Connectivity states for the household connection.
 *
 */
export type ConnectivityState =
  | 'DISCOVERING'
  | 'CONNECTED_LAN'
  | 'CONNECTED_RELAY'
  | 'RECONNECTING'
  | 'OFFLINE';

/**
 * Interface for mDNS/DNS-SD service discovery.
 *
 * Platform-specific implementations:
 * - RN: react-native-zeroconf or native bridge
 * - Web: not available (defaults to null)
 */
export interface MdnsScanner {
  scan(serviceType?: string, timeoutMs?: number): Promise<DiscoveredHousehold | null>;
}

/** mDNS service type for Wyrdsekai household servers. */
export const MDNS_SERVICE_TYPE = '_wyrdsekai._tcp.local';
