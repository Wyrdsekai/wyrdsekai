/**
 * HouseholdScreen logic tests.
 *
 * Tests the household store and connectivity helper functions
 * WITHOUT rendering React components. Matches the pattern from
 * connect-screen.test.ts.
 */

// Mock react-native (used by householdStore + HouseholdScreen)
jest.mock('react-native', () => ({
  Platform: { OS: 'web' },
  StyleSheet: { create: (styles: Record<string, unknown>) => styles },
  Switch: 'Switch',
}));

import { useHouseholdStore } from '../../src/state/householdStore';
import { connectivityDotColor } from '../../src/screens/HouseholdScreen';
import type { ConnectivityState } from '../../src/engine/discovery/types';

// Reset store between tests
beforeEach(() => {
  useHouseholdStore.setState({
    connectivityState: 'OFFLINE',
    connectedNodes: [],
    householdId: null,
    householdUrl: '',
    relayUrl: '',
    autoDiscover: true,
  });
});

describe('householdStore state management', () => {
  test('starts with OFFLINE connectivity', () => {
    expect(useHouseholdStore.getState().connectivityState).toBe('OFFLINE');
  });

  test('starts with no connected nodes', () => {
    expect(useHouseholdStore.getState().connectedNodes).toEqual([]);
  });

  test('starts with no household ID', () => {
    expect(useHouseholdStore.getState().householdId).toBeNull();
  });

  test('starts with auto-discover enabled', () => {
    expect(useHouseholdStore.getState().autoDiscover).toBe(true);
  });

  test('connect sets state to DISCOVERING', () => {
    useHouseholdStore.getState().connect();
    expect(useHouseholdStore.getState().connectivityState).toBe('DISCOVERING');
  });

  test('disconnect sets state to OFFLINE and clears nodes', () => {
    useHouseholdStore.setState({
      connectivityState: 'CONNECTED_LAN',
      connectedNodes: [{ nodeId: 'node-1', status: 'online', timestamp: Date.now() }],
    });
    useHouseholdStore.getState().disconnect();
    expect(useHouseholdStore.getState().connectivityState).toBe('OFFLINE');
    expect(useHouseholdStore.getState().connectedNodes).toEqual([]);
  });

  test('setConnectivityState updates state', () => {
    useHouseholdStore.getState().setConnectivityState('CONNECTED_RELAY');
    expect(useHouseholdStore.getState().connectivityState).toBe('CONNECTED_RELAY');
  });

  test('setHouseholdUrl updates URL', () => {
    useHouseholdStore.getState().setHouseholdUrl('ws://198.51.100.100:4222');
    expect(useHouseholdStore.getState().householdUrl).toBe('ws://198.51.100.100:4222');
  });

  test('setRelayUrl updates relay URL', () => {
    useHouseholdStore.getState().setRelayUrl('wss://relay.example.com');
    expect(useHouseholdStore.getState().relayUrl).toBe('wss://relay.example.com');
  });

  test('setAutoDiscover toggles auto-discover', () => {
    useHouseholdStore.getState().setAutoDiscover(false);
    expect(useHouseholdStore.getState().autoDiscover).toBe(false);
    useHouseholdStore.getState().setAutoDiscover(true);
    expect(useHouseholdStore.getState().autoDiscover).toBe(true);
  });

  test('setHouseholdId updates household ID', () => {
    useHouseholdStore.getState().setHouseholdId('hh-abc123');
    expect(useHouseholdStore.getState().householdId).toBe('hh-abc123');
  });

  test('setConnectedNodes replaces node list', () => {
    const nodes = [
      { nodeId: 'phone-1', status: 'online', timestamp: 1000 },
      { nodeId: 'desktop-1', status: 'away', tier: 'T2', timestamp: 2000 },
    ];
    useHouseholdStore.getState().setConnectedNodes(nodes);
    expect(useHouseholdStore.getState().connectedNodes).toEqual(nodes);
  });

  test('updateFromPresenceMap converts map to node array', () => {
    const map = new Map([
      ['node-a', { nodeId: 'node-a', status: 'online', timestamp: 100 }],
      ['node-b', { nodeId: 'node-b', status: 'sleeping', tier: 'T1', timestamp: 200 }],
    ]);
    useHouseholdStore.getState().updateFromPresenceMap(map);
    const nodes = useHouseholdStore.getState().connectedNodes;
    expect(nodes).toHaveLength(2);
    expect(nodes.find(n => n.nodeId === 'node-a')?.status).toBe('online');
    expect(nodes.find(n => n.nodeId === 'node-b')?.tier).toBe('T1');
  });
});

describe('connectivityDotColor', () => {
  const cases: [ConnectivityState, string][] = [
    ['CONNECTED_LAN', '#4CAF50'],
    ['CONNECTED_RELAY', '#2196F3'],
    ['DISCOVERING', '#FF9800'],
    ['RECONNECTING', '#FF9800'],
    ['OFFLINE', '#D32F2F'],
  ];

  test.each(cases)('%s returns %s', (state, expected) => {
    expect(connectivityDotColor(state)).toBe(expected);
  });
});
