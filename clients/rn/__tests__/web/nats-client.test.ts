import { NatsClient } from '../../src/web/NatsClient';

// Mock nats.ws — NatsClient uses dynamic import
jest.mock('nats.ws', () => ({
  connect: jest.fn().mockResolvedValue({
    subscribe: jest.fn().mockReturnValue({
      [Symbol.asyncIterator]: () => ({
        next: () => new Promise(() => {}), // Never resolves — mock subscription
      }),
    }),
    publish: jest.fn(),
    drain: jest.fn().mockResolvedValue(undefined),
    close: jest.fn().mockResolvedValue(undefined),
  }),
}), { virtual: true });

describe('NatsClient', () => {
  let client: NatsClient;

  beforeEach(() => {
    client = new NatsClient();
  });

  afterEach(async () => {
    await client.disconnect();
  });

  it('starts disconnected', () => {
    expect(client.state).toBe('disconnected');
  });

  it('connects and transitions to connected state', async () => {
    const states: string[] = [];
    client.onStateChange(s => states.push(s));

    await client.connect('ws://localhost:9222');
    expect(client.state).toBe('connected');
    expect(states).toContain('connecting');
    expect(states).toContain('connected');
  });

  it('subscribes to a subject', async () => {
    await client.connect('ws://localhost:9222');
    const unsub = client.subscribe('room.nexus.events', () => {});
    expect(typeof unsub).toBe('function');
    unsub();
  });

  it('disconnects cleanly', async () => {
    await client.connect('ws://localhost:9222');
    await client.disconnect();
    expect(client.state).toBe('disconnected');
  });
});
