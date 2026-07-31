import { BetweenBridge } from '../../src/web/BetweenBridge';
import { RoomEngine } from '../../src/engine/room/RoomEngine';
import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';
import { NatsClient } from '../../src/web/NatsClient';

// Mock nats.ws
jest.mock('nats.ws', () => ({
  connect: jest.fn().mockResolvedValue({
    subscribe: jest.fn().mockReturnValue({
      [Symbol.asyncIterator]: () => ({
        next: () => new Promise(() => {}),
      }),
    }),
    publish: jest.fn(),
    drain: jest.fn().mockResolvedValue(undefined),
    close: jest.fn().mockResolvedValue(undefined),
  }),
}), { virtual: true });

describe('BetweenBridge', () => {
  let natsClient: NatsClient;
  let bridge: BetweenBridge;
  let engine: RoomEngine;

  beforeEach(async () => {
    natsClient = new NatsClient();
    await natsClient.connect('ws://localhost:9222');
    bridge = new BetweenBridge(natsClient);
    engine = new RoomEngine('test', new InMemoryEventJournal());
  });

  afterEach(async () => {
    bridge.shutdown();
    engine.shutdown();
    await natsClient.disconnect();
  });

  it('bridges a room to NATS', () => {
    // Should not throw
    bridge.bridgeRoom(engine);
  });

  it('does not duplicate bridge for same room', () => {
    bridge.bridgeRoom(engine);
    bridge.bridgeRoom(engine); // Should be a no-op
    // No assertion — just verify no crash
  });

  it('shutdown cleans up', () => {
    bridge.bridgeRoom(engine);
    bridge.shutdown();
    // Should be safe to call again
    bridge.shutdown();
  });
});
