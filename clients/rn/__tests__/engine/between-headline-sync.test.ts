import { InMemoryBetweenClient } from '../../src/engine/between/BetweenClient';
import { BetweenHeadlineSyncClient } from '../../src/engine/between/BetweenHeadlineSyncClient';
import type { Headline } from '../../src/engine/soul/HeadlineSyncClient';

function makeHeadline(overrides: Partial<Headline> = {}): Headline {
  return {
    budDid: 'bud-1',
    summary: 'All is well',
    vitalitySnapshot: { energy: 0.8, confidence: 0.7 },
    itemCount: 5,
    timestamp: 1000,
    ...overrides,
  };
}

describe('BetweenHeadlineSyncClient', () => {
  it('postHeadline caches locally', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const client = new BetweenHeadlineSyncClient(between, 'node-1', 'family-1');

    client.postHeadline(makeHeadline());

    expect(client.latestHeadlines().length).toBe(1);
    expect(client.latestHeadlines()[0].summary).toBe('All is well');
  });

  it('postHeadline publishes to Between', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const client = new BetweenHeadlineSyncClient(between, 'node-1', 'family-1');

    client.postHeadline(makeHeadline());

    expect(between.published.length).toBe(1);
    expect(between.published[0].subject).toContain('soul.headlines');
    expect(between.published[0].subject).toContain('family-1');
  });

  it('receive headline from sibling', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const client = new BetweenHeadlineSyncClient(between, 'node-1', 'family-1');

    const received: Headline[] = [];
    client.onHeadlineReceived(h => received.push(h));
    client.startListening();

    // Simulate sibling posting a headline
    const siblingHeadline = makeHeadline({ budDid: 'bud-2', summary: 'Exploring terminal' });
    const data = new TextEncoder().encode(JSON.stringify(siblingHeadline));
    between.publish('between.household.family-1.node-2.soul.headlines', data);

    expect(received.length).toBe(1);
    expect(received[0].budDid).toBe('bud-2');
    expect(received[0].summary).toBe('Exploring terminal');

    client.stopListening();
  });

  it('ignores own headlines', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const client = new BetweenHeadlineSyncClient(between, 'node-1', 'family-1');

    const received: Headline[] = [];
    client.onHeadlineReceived(h => received.push(h));
    client.startListening();

    // Simulate own headline echoing back
    const ownHeadline = makeHeadline({ budDid: 'node-1', summary: 'My own' });
    const data = new TextEncoder().encode(JSON.stringify(ownHeadline));
    between.publish('between.household.family-1.node-1.soul.headlines', data);

    expect(received.length).toBe(0);
  });

  it('latest headlines sorted by timestamp', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const client = new BetweenHeadlineSyncClient(between, 'node-1', 'family-1');

    client.postHeadline(makeHeadline({ budDid: 'a', timestamp: 100 }));
    client.postHeadline(makeHeadline({ budDid: 'b', timestamp: 300 }));
    client.postHeadline(makeHeadline({ budDid: 'c', timestamp: 200 }));

    const headlines = client.latestHeadlines();
    expect(headlines[0].budDid).toBe('b');
    expect(headlines[1].budDid).toBe('c');
    expect(headlines[2].budDid).toBe('a');
  });

  it('graceful when disconnected', async () => {
    const between = new InMemoryBetweenClient();
    // NOT connected
    const client = new BetweenHeadlineSyncClient(between, 'node-1', 'family-1');

    client.postHeadline(makeHeadline());
    expect(client.latestHeadlines().length).toBe(1);
    expect(between.published.length).toBe(0);
  });
});
