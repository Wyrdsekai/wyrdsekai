import {
  HouseholdConnector,
  HouseholdUnreachableError,
  backoffDelayMs,
} from '../../../src/engine/discovery/HouseholdConnector';
import type {
  ConnectivityState,
  DiscoveredHousehold,
  MdnsScanner,
  SavedHouseholdConfig,
} from '../../../src/engine/discovery/types';
import type { BetweenClientFactory, ConfigStorage } from '../../../src/engine/discovery/HouseholdConnector';
import { InMemoryBetweenClient } from '../../../src/engine/between/BetweenClient';
import type { BetweenClient } from '../../../src/engine/between/BetweenClient';

/** mDNS scanner that returns a fixed result. */
class FixedMdnsScanner implements MdnsScanner {
  constructor(private result: DiscoveredHousehold | null) {}
  async scan(): Promise<DiscoveredHousehold | null> {
    return this.result;
  }
}

/** Between client factory that optionally fails connect. */
class TestFactory implements BetweenClientFactory {
  created: InMemoryBetweenClient[] = [];
  failConnect = false;

  constructor(failConnect = false) {
    this.failConnect = failConnect;
  }

  create(): BetweenClient {
    const self = this;
    const client = new (class extends InMemoryBetweenClient {
      async connect(url: string): Promise<void> {
        if (self.failConnect) throw new Error('Connection refused');
        await super.connect(url);
      }
    })();
    this.created.push(client);
    return client;
  }
}

/** In-memory config storage for testing. */
class InMemoryStorage implements ConfigStorage {
  store = new Map<string, string>();
  async getItem(key: string): Promise<string | null> {
    return this.store.get(key) ?? null;
  }
  async setItem(key: string, value: string): Promise<void> {
    this.store.set(key, value);
  }
}

const DISCOVERED: DiscoveredHousehold = {
  householdId: 'hh-123',
  householdName: 'Smith Home',
  natsWsUrl: 'ws://198.51.100.100:9222',
  relayUrl: 'wss://relay.wyrdsekai.org:9222',
  relayToken: 'test-token-abc',
  version: '1.0',
};

describe('HouseholdConnector', () => {
  describe('connect', () => {
    it('connects via mDNS discovery', async () => {
      const factory = new TestFactory();
      const connector = new HouseholdConnector({
        mdnsScanner: new FixedMdnsScanner(DISCOVERED),
        clientFactory: factory,
      });

      const client = await connector.connect();

      expect(connector.state).toBe('CONNECTED_LAN');
      expect(client.isConnected).toBe(true);
      expect(connector.lastDiscovered).not.toBeNull();
      expect(connector.lastDiscovered!.householdId).toBe('hh-123');
    });

    it('falls to saved config when mDNS returns null', async () => {
      const factory = new TestFactory();
      const saved: SavedHouseholdConfig = {
        householdId: 'hh-saved',
        householdName: 'Saved Home',
        natsWsUrl: 'ws://198.51.100.50:9222',
        relayUrl: null,
        relayToken: null,
        lastConnected: Date.now(),
      };

      const connector = new HouseholdConnector({
        mdnsScanner: new FixedMdnsScanner(null),
        clientFactory: factory,
        savedConfig: saved,
      });

      const client = await connector.connect();

      expect(connector.state).toBe('CONNECTED_LAN');
      expect(client.isConnected).toBe(true);
    });

    it('falls to relay when LAN fails', async () => {
      let connectAttempt = 0;
      const factory: BetweenClientFactory = {
        create(): BetweenClient {
          connectAttempt++;
          const failThis = connectAttempt <= 2;
          return new (class extends InMemoryBetweenClient {
            async connect(url: string): Promise<void> {
              if (failThis) throw new Error('LAN unreachable');
              await super.connect(url);
            }
          })();
        },
      };

      const saved: SavedHouseholdConfig = {
        householdId: 'hh-relay',
        householdName: 'Relay Home',
        natsWsUrl: 'ws://198.51.100.50:9222',
        relayUrl: 'wss://relay.wyrdsekai.org:9222',
        relayToken: 'relay-token',
        lastConnected: Date.now(),
      };

      const connector = new HouseholdConnector({
        mdnsScanner: new FixedMdnsScanner(DISCOVERED),
        clientFactory: factory,
        savedConfig: saved,
      });

      const client = await connector.connect();

      expect(connector.state).toBe('CONNECTED_RELAY');
      expect(client.isConnected).toBe(true);
    });

    it('goes offline when all levels fail', async () => {
      const factory = new TestFactory(true);

      const connector = new HouseholdConnector({
        mdnsScanner: new FixedMdnsScanner(DISCOVERED),
        clientFactory: factory,
      });

      await expect(connector.connect()).rejects.toThrow(HouseholdUnreachableError);
      expect(connector.state).toBe('OFFLINE');
    });

    it('goes offline when no mDNS, no saved, no relay', async () => {
      const factory = new TestFactory();

      const connector = new HouseholdConnector({
        mdnsScanner: new FixedMdnsScanner(null),
        clientFactory: factory,
        savedConfig: null,
      });

      await expect(connector.connect()).rejects.toThrow(HouseholdUnreachableError);
      expect(connector.state).toBe('OFFLINE');
    });
  });

  describe('saved config', () => {
    it('saves config on mDNS success', async () => {
      const factory = new TestFactory();
      const connector = new HouseholdConnector({
        mdnsScanner: new FixedMdnsScanner(DISCOVERED),
        clientFactory: factory,
      });

      expect(connector.savedConfig).toBeNull();

      await connector.connect();

      expect(connector.savedConfig).not.toBeNull();
      expect(connector.savedConfig!.householdId).toBe('hh-123');
      expect(connector.savedConfig!.householdName).toBe('Smith Home');
      expect(connector.savedConfig!.lastConnected).toBeGreaterThan(0);
    });

    it('persists to storage on mDNS success', async () => {
      const storage = new InMemoryStorage();
      const factory = new TestFactory();
      const connector = new HouseholdConnector({
        mdnsScanner: new FixedMdnsScanner(DISCOVERED),
        clientFactory: factory,
        storage,
      });

      await connector.connect();

      const stored = await storage.getItem('@wyrdsekai/household-config');
      expect(stored).not.toBeNull();
      const parsed = JSON.parse(stored!);
      expect(parsed.householdId).toBe('hh-123');
    });

    it('loads from storage on fallback', async () => {
      const storage = new InMemoryStorage();
      const saved: SavedHouseholdConfig = {
        householdId: 'hh-stored',
        householdName: 'Stored Home',
        natsWsUrl: 'ws://198.51.100.75:9222',
        relayUrl: null,
        relayToken: null,
        lastConnected: Date.now(),
      };
      await storage.setItem('@wyrdsekai/household-config', JSON.stringify(saved));

      const factory = new TestFactory();
      const connector = new HouseholdConnector({
        mdnsScanner: new FixedMdnsScanner(null),
        clientFactory: factory,
        storage,
      });

      const client = await connector.connect();

      expect(connector.state).toBe('CONNECTED_LAN');
      expect(client.isConnected).toBe(true);
    });

    it('manual update', () => {
      const connector = new HouseholdConnector();

      expect(connector.savedConfig).toBeNull();

      connector.updateSavedConfig({
        householdId: 'hh-manual',
        householdName: 'Manual Config',
        natsWsUrl: 'ws://192.0.2.1:9222',
        relayUrl: null,
        relayToken: null,
        lastConnected: 0,
      });

      expect(connector.savedConfig!.householdId).toBe('hh-manual');
    });
  });

  describe('state changes', () => {
    it('notifies listeners on state change', async () => {
      const factory = new TestFactory();
      const states: ConnectivityState[] = [];
      const connector = new HouseholdConnector({
        mdnsScanner: new FixedMdnsScanner(DISCOVERED),
        clientFactory: factory,
      });

      connector.onStateChange(state => states.push(state));

      await connector.connect();

      expect(states).toContain('DISCOVERING');
      expect(states).toContain('CONNECTED_LAN');
    });

    it('unsubscribe stops notifications', async () => {
      const factory = new TestFactory();
      const states: ConnectivityState[] = [];
      const connector = new HouseholdConnector({
        mdnsScanner: new FixedMdnsScanner(DISCOVERED),
        clientFactory: factory,
      });

      const unsub = connector.onStateChange(state => states.push(state));
      unsub();

      await connector.connect();

      expect(states).toHaveLength(0);
    });
  });

  describe('backoffDelayMs', () => {
    it('is exponential', () => {
      expect(backoffDelayMs(0)).toBe(1000);
      expect(backoffDelayMs(1)).toBe(2000);
      expect(backoffDelayMs(2)).toBe(4000);
      expect(backoffDelayMs(3)).toBe(8000);
      expect(backoffDelayMs(4)).toBe(16000);
    });

    it('caps at 16s', () => {
      expect(backoffDelayMs(5)).toBe(16000);
      expect(backoffDelayMs(10)).toBe(16000);
    });
  });
});
