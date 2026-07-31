/**
 * Tests for optional NATS/Between connectivity in standalone mode.
 *
 * Verifies:
 * - getActiveRoomEngines() returns room engines after start
 * - Standalone mode works without any NATS dependency
 * - NatsBetweenAdapter starts in disconnected state
 */
import { PhoneNode } from '../../src/engine/PhoneNode';
import type { ModelRole } from '../../src/inference/InferenceRouter';
import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';
import { InMemoryVitalityStore } from '../../src/engine/persistence/InMemoryVitalityStore';
import { InMemorySoulManifestStore } from '../../src/engine/persistence/InMemorySoulManifestStore';
import { NatsBetweenAdapter } from '../../src/engine/between/NatsBetweenAdapter';
import type { ChatMessage, ChatResponse } from '../../src/inference/types';

const mockInference = {
  async complete(_role: ModelRole, messages: ChatMessage[]): Promise<ChatResponse> {
    const lastUser = messages.filter(m => m.role === 'user').pop();
    return {
      content: `Echo: ${lastUser?.content ?? 'silence'}`,
      promptTokens: 10,
      completionTokens: 5,
    };
  },
};

describe('Standalone NATS wiring', () => {
  let node: PhoneNode;

  beforeEach(async () => {
    node = new PhoneNode(
      new InMemoryEventJournal(),
      new InMemoryVitalityStore(),
      mockInference,
      null, // no tier manager
      new InMemorySoulManifestStore(),
    );
    await node.start();
  });

  afterEach(() => {
    node.stop();
  });

  it('getActiveRoomEngines returns room engines after start', () => {
    const engines = node.getActiveRoomEngines();
    expect(engines.length).toBeGreaterThanOrEqual(1);

    // The Home room (formerly "The Nexus") is always booted at T1.
    const home = engines.find(e => e.roomId === 'home');
    expect(home).toBeDefined();
    expect(home!.state.name).toBe('Home');
  });

  it('getActiveRoomEngines returns empty before start', () => {
    const freshNode = new PhoneNode(
      new InMemoryEventJournal(),
      new InMemoryVitalityStore(),
      mockInference,
    );
    expect(freshNode.getActiveRoomEngines()).toEqual([]);
    // No stop needed — never started
  });

  it('standalone works without NATS — rooms and companion functional', async () => {
    // Verify core functionality without any Between connection
    expect(node.state).toBe('running');
    expect(node.companion).not.toBeNull();

    const snapshot = node.look();
    expect(snapshot).not.toBeNull();
    // Boot room is now The Study.
    expect(snapshot!.name).toBe('The Study');

    // Can say and receive events
    const events: string[] = [];
    const unsub = node.onEvent(e => {
      if (e.type === 'prose') events.push(e.text);
    });

    await node.say('p1', 'Tester', 'Hello');
    expect(events).toContain('Hello');

    unsub();
  });

  it('active room engines match activeRoomIds', () => {
    const engines = node.getActiveRoomEngines();
    const ids = node.activeRoomIds();

    expect(engines.length).toBe(ids.size);
    for (const engine of engines) {
      expect(ids.has(engine.roomId)).toBe(true);
    }
  });
});

describe('NatsBetweenAdapter', () => {
  it('starts disconnected', () => {
    const adapter = new NatsBetweenAdapter();
    expect(adapter.isConnected).toBe(false);
  });

  it('publish throws when not connected', () => {
    const adapter = new NatsBetweenAdapter();
    expect(() => adapter.publish('test', new Uint8Array())).toThrow('Not connected');
  });

  it('subscribe throws when not connected', () => {
    const adapter = new NatsBetweenAdapter();
    expect(() => adapter.subscribe('test', () => {})).toThrow('Not connected');
  });

  it('connect fails gracefully without nats.ws', async () => {
    const adapter = new NatsBetweenAdapter();
    // In test environment, nats.ws is not available — connect should fail
    await expect(adapter.connect('ws://localhost:9222')).rejects.toThrow();
    expect(adapter.isConnected).toBe(false);
  });

  it('disconnect is safe when not connected', async () => {
    const adapter = new NatsBetweenAdapter();
    // Should not throw
    await adapter.disconnect();
    expect(adapter.isConnected).toBe(false);
  });
});
