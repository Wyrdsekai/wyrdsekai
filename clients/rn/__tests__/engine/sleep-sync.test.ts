import { InMemoryBetweenClient } from '../../src/engine/between/BetweenClient';
import { SleepSyncManager, type SleepSyncRequest, type SleepSyncResponse } from '../../src/engine/between/SleepSync';

describe('SleepSyncManager', () => {
  it('buildRequest includes all fields', () => {
    const between = new InMemoryBetweenClient();
    const manager = new SleepSyncManager(between, 'node-1', 'family-1');

    const request = manager.buildRequest({
      budDid: 'bud-1',
      manifestVersion: 3,
      localItemHashes: ['hash-a', 'hash-b'],
      localTombstones: [
        { itemHash: 'hash-c', reason: 'superseded', createdBy: 'bud-1', timestamp: 100 },
      ],
      lastSyncTimestamp: 5000,
    });

    expect(request.budDid).toBe('bud-1');
    expect(request.nodeId).toBe('node-1');
    expect(request.manifestVersion).toBe(3);
    expect(request.localItemHashes.length).toBe(2);
    expect(request.localTombstones.length).toBe(1);
    expect(request.lastSyncTimestamp).toBe(5000);
    expect(request.timestamp).toBeGreaterThan(0);
  });

  it('requestSync publishes to Between', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new SleepSyncManager(between, 'node-1', 'family-1');

    const request: SleepSyncRequest = {
      budDid: 'bud-1',
      nodeId: 'node-1',
      manifestVersion: 1,
      localItemHashes: ['h1'],
      localTombstones: [],
      lastSyncTimestamp: 0,
      timestamp: 1000,
    };
    manager.requestSync(request);

    expect(between.published.length).toBe(1);
    const { subject } = between.published[0];
    expect(subject).toContain('soul.sync.request');
    expect(subject).toContain('family-1');
    expect(subject).toContain('node-1');
  });

  it('receive sync response triggers callback', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new SleepSyncManager(between, 'node-1', 'family-1');

    let received: SleepSyncResponse | null = null;
    manager.onSyncResponse(r => { received = r; });
    manager.startListening();

    const response: SleepSyncResponse = {
      budDid: 'bud-1',
      newItems: [
        { hash: 'hash-x', category: 'memory', significance: 0.9, createdBy: 'bud-2', timestamp: 500 },
      ],
      newTombstones: [
        { itemHash: 'hash-old', reason: 'expired', createdBy: 'system', timestamp: 600 },
      ],
      manifestUpdated: true,
      itemsMerged: 3,
      tombstonesApplied: 1,
      timestamp: 2000,
    };
    const data = new TextEncoder().encode(JSON.stringify(response));
    between.publish('between.household.family-1.server.node-1.soul.sync.response', data);

    expect(received).not.toBeNull();
    expect(received!.newItems.length).toBe(1);
    expect(received!.newItems[0].hash).toBe('hash-x');
    expect(received!.manifestUpdated).toBe(true);
    expect(received!.itemsMerged).toBe(3);

    manager.stopListening();
  });

  it('sync request subject matches pattern', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new SleepSyncManager(between, 'node-1', 'family-abc');

    const request: SleepSyncRequest = {
      budDid: 'bud-1', nodeId: 'node-1',
      manifestVersion: 1, localItemHashes: [],
      localTombstones: [], lastSyncTimestamp: 0, timestamp: 100,
    };
    manager.requestSync(request);

    expect(between.published[0].subject).toBe(
      'between.household.family-abc.node-1.server.soul.sync.request',
    );
  });
});
