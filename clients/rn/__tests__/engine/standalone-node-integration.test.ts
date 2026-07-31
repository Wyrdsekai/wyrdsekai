/**
 * Integration tests for standalone PhoneNode → standaloneNodeStore flow.
 *
 * Tests that PhoneNode events correctly propagate through the listener
 * wiring that StandaloneNodeContext would set up.
 */
import { PhoneNode, PhoneNodeEvent } from '../../src/engine/PhoneNode';
import type { ModelRole } from '../../src/inference/InferenceRouter';
import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';
import { InMemoryVitalityStore } from '../../src/engine/persistence/InMemoryVitalityStore';
import { InMemorySoulManifestStore } from '../../src/engine/persistence/InMemorySoulManifestStore';
import { useStandaloneNodeStore } from '../../src/state/standaloneNodeStore';
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

describe('Standalone PhoneNode integration', () => {
  let node: PhoneNode;
  let unsub: () => void;

  beforeEach(async () => {
    useStandaloneNodeStore.getState().reset();

    node = new PhoneNode(
      new InMemoryEventJournal(),
      new InMemoryVitalityStore(),
      mockInference,
      null, // no tier manager
      new InMemorySoulManifestStore(),
    );

    // Wire events like StandaloneNodeContext does
    unsub = node.onEvent((event: PhoneNodeEvent) => {
      const store = useStandaloneNodeStore.getState();
      switch (event.type) {
        case 'prose':
          store.addProse({ speaker: event.speaker, text: event.text });
          break;
        case 'room_changed':
          store.applyRoomSnapshot(event.snapshot);
          store.addProse({
            speaker: 'narrator',
            text: `${event.snapshot.name}\n${event.snapshot.description}`,
          });
          break;
        case 'state_changed':
          store.addProse({ speaker: 'narrator', text: `~ ${event.description}` });
          break;
        case 'error':
          store.addProse({ speaker: 'system', text: `Error [${event.code}]: ${event.message}` });
          break;
      }
    });

    await node.start();
    useStandaloneNodeStore.getState().setNodeState(node.state);

    // Apply initial snapshot
    const snapshot = node.look();
    if (snapshot) {
      useStandaloneNodeStore.getState().applyRoomSnapshot(snapshot);
    }
  });

  afterEach(() => {
    unsub();
    node.stop();
  });

  it('starts and populates room state', () => {
    const state = useStandaloneNodeStore.getState();
    expect(state.nodeState).toBe('running');
    // Boot room is now The Study (the player's home base on the phone).
    expect(state.roomName).toBe('The Study');
    expect(state.exits.length).toBeGreaterThan(0);
  });

  it('loads bootstrap soul manifest', () => {
    expect(node.companion).not.toBeNull();
    expect(node.companion!.getSoulManifest()).not.toBeNull();
    expect(node.companion!.getSoulManifest()!.did).toBe('did:key:bootstrap-wyrd');
  });

  it('say produces prose event', async () => {
    const initialCount = useStandaloneNodeStore.getState().proseStream.length;
    await node.say('p1', 'Alice', 'Hello Wyrd');

    // The 'said' event from room engine propagates to PhoneNode listener
    const stream = useStandaloneNodeStore.getState().proseStream;
    expect(stream.length).toBeGreaterThan(initialCount);
    const last = stream[stream.length - 1];
    expect(last.speaker).toBe('Alice');
    expect(last.text).toBe('Hello Wyrd');
  });

  it('look returns valid snapshot', () => {
    const snapshot = node.look();
    expect(snapshot).not.toBeNull();
    expect(snapshot!.name).toBe('The Study');
    expect(snapshot!.roomId).toBe('study');
  });

  it('invalid direction produces error prose', async () => {
    await node.go('p1', 'Alice', 'up');
    const stream = useStandaloneNodeStore.getState().proseStream;
    const errorEntry = stream.find(e => e.speaker === 'system' && e.text.includes('no exit'));
    expect(errorEntry).toBeDefined();
  });
});
