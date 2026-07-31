/**
 * Connectivity cascade for discovering and connecting to the household.
 *
 * Implements the 4-level cascade from §19.2:
 * 1. mDNS discovery (LAN)
 * 2. Saved config (from last successful mDNS)
 * 3. Cloud relay (from mDNS TXT or saved config)
 * 4. Offline (companion works locally)
 *
 * On connection loss, retries with exponential backoff and falls
 * through cascade levels after max attempts per level.
 *
 */
import type { BetweenClient } from '../between/BetweenClient';
import { InMemoryBetweenClient } from '../between/BetweenClient';
import type {
  ConnectivityState,
  DiscoveredHousehold,
  MdnsScanner,
  SavedHouseholdConfig,
} from './types';

const MAX_BACKOFF_MS = 16_000;
const MAX_ATTEMPTS_PER_LEVEL = 5;
const SAVED_CONFIG_STORAGE_KEY = '@wyrdsekai/household-config';

/** Storage interface for persisting household config (AsyncStorage, localStorage, etc.). */
export interface ConfigStorage {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
}

/** Factory for creating BetweenClient instances. Abstracted for testability. */
export interface BetweenClientFactory {
  create(): BetweenClient;
}

export type ConnectivityListener = (state: ConnectivityState) => void;

export class HouseholdConnector {
  private _state: ConnectivityState = 'DISCOVERING';
  private listeners: ConnectivityListener[] = [];
  private _savedConfig: SavedHouseholdConfig | null;
  private _lastDiscovered: DiscoveredHousehold | null = null;

  private readonly mdnsScanner: MdnsScanner | null;
  private readonly clientFactory: BetweenClientFactory;
  private readonly storage: ConfigStorage | null;

  constructor(opts?: {
    mdnsScanner?: MdnsScanner | null;
    clientFactory?: BetweenClientFactory;
    savedConfig?: SavedHouseholdConfig | null;
    storage?: ConfigStorage | null;
  }) {
    this.mdnsScanner = opts?.mdnsScanner ?? null;
    this.clientFactory = opts?.clientFactory ?? {
      create: () => new InMemoryBetweenClient(),
    };
    this._savedConfig = opts?.savedConfig ?? null;
    this.storage = opts?.storage ?? null;
  }

  /** Current connectivity state. */
  get state(): ConnectivityState {
    return this._state;
  }

  /** Last discovered household, if any. */
  get lastDiscovered(): DiscoveredHousehold | null {
    return this._lastDiscovered;
  }

  /** Current saved configuration. */
  get savedConfig(): SavedHouseholdConfig | null {
    return this._savedConfig;
  }

  /**
   * Subscribe to state changes. Returns unsubscribe function.
   */
  onStateChange(listener: ConnectivityListener): () => void {
    this.listeners.push(listener);
    return () => {
      this.listeners = this.listeners.filter(l => l !== listener);
    };
  }

  /**
   * Execute the connectivity cascade and return a connected BetweenClient.
   *
   * @throws Error if all cascade levels fail
   */
  async connect(): Promise<BetweenClient> {
    this.setState('DISCOVERING');

    // Level 1: mDNS discovery
    let discovered: DiscoveredHousehold | null = null;
    if (this.mdnsScanner) {
      try {
        discovered = await this.mdnsScanner.scan();
      } catch {
        // mDNS scan failed — fall through
      }
    }

    if (discovered) {
      this._lastDiscovered = discovered;
      try {
        const client = this.clientFactory.create();
        await client.connect(discovered.natsWsUrl);
        await this.saveConfig(discovered);
        this.setState('CONNECTED_LAN');
        return client;
      } catch {
        // LAN connect failed — fall through
      }
    }

    // Level 2: Saved config (LAN URL)
    const saved = this._savedConfig ?? (await this.loadConfig());
    if (saved) {
      try {
        const client = this.clientFactory.create();
        await client.connect(saved.natsWsUrl);
        this.setState('CONNECTED_LAN');
        return client;
      } catch {
        // Saved LAN URL failed — fall through
      }
    }

    // Level 3: Cloud relay
    const relayUrl = discovered?.relayUrl ?? saved?.relayUrl;
    const relayToken = discovered?.relayToken ?? saved?.relayToken;
    if (relayUrl) {
      try {
        const client = this.clientFactory.create();
        const urlWithAuth = relayToken ? `${relayUrl}?token=${relayToken}` : relayUrl;
        await client.connect(urlWithAuth);
        this.setState('CONNECTED_RELAY');
        return client;
      } catch {
        // Relay connect failed — fall through
      }
    }

    // Level 4: Offline
    this.setState('OFFLINE');
    throw new HouseholdUnreachableError('All cascade levels failed');
  }

  /**
   * Reconnect with exponential backoff, falling through cascade levels.
   */
  async reconnect(maxAttempts = MAX_ATTEMPTS_PER_LEVEL): Promise<BetweenClient> {
    this.setState('RECONNECTING');

    let lastError: Error | null = null;
    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        return await this.connect();
      } catch (e) {
        lastError = e instanceof Error ? e : new Error(String(e));
        if (attempt < maxAttempts - 1) {
          await sleep(backoffDelayMs(attempt));
        }
      }
    }

    this.setState('OFFLINE');
    throw lastError ?? new HouseholdUnreachableError(`Reconnection failed after ${maxAttempts} attempts`);
  }

  /**
   * Update the saved configuration (e.g., after manual entry or QR scan).
   */
  updateSavedConfig(config: SavedHouseholdConfig): void {
    this._savedConfig = config;
    this.persistConfig(config);
  }

  // --- Private ---

  private setState(state: ConnectivityState): void {
    this._state = state;
    for (const listener of this.listeners) {
      listener(state);
    }
  }

  private async saveConfig(discovered: DiscoveredHousehold): Promise<void> {
    const config: SavedHouseholdConfig = {
      householdId: discovered.householdId,
      householdName: discovered.householdName,
      natsWsUrl: discovered.natsWsUrl,
      relayUrl: discovered.relayUrl,
      relayToken: discovered.relayToken,
      lastConnected: Date.now(),
    };
    this._savedConfig = config;
    await this.persistConfig(config);
  }

  private async persistConfig(config: SavedHouseholdConfig): Promise<void> {
    if (!this.storage) return;
    try {
      await this.storage.setItem(SAVED_CONFIG_STORAGE_KEY, JSON.stringify(config));
    } catch {
      // Storage failure is non-fatal
    }
  }

  private async loadConfig(): Promise<SavedHouseholdConfig | null> {
    if (!this.storage) return null;
    try {
      const raw = await this.storage.getItem(SAVED_CONFIG_STORAGE_KEY);
      if (raw) {
        const config = JSON.parse(raw) as SavedHouseholdConfig;
        this._savedConfig = config;
        return config;
      }
    } catch {
      // Storage failure is non-fatal
    }
    return null;
  }
}

export class HouseholdUnreachableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'HouseholdUnreachableError';
  }
}

/**
 * Exponential backoff: 1s, 2s, 4s, 8s, 16s (capped).
 */
export function backoffDelayMs(attempt: number): number {
  const base = 1000;
  const delay = base * Math.pow(2, attempt);
  return Math.min(delay, MAX_BACKOFF_MS);
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}
