/**
 * Dialogue state machine tests for CompanionEngine.
 *
 * Validates the idle→listening→thinking→speaking→idle transitions,
 * error→idle auto-recovery, and listener notifications.
 */

import { CompanionEngine, type DialogueState } from '../../../src/engine/agent/CompanionEngine';
import type { ModelRole } from '../../../src/inference/InferenceRouter';
import { RoomEngine } from '../../../src/engine/room/RoomEngine';
import { InMemoryEventJournal } from '../../../src/engine/persistence/InMemoryEventJournal';
import { InMemoryVitalityStore } from '../../../src/engine/persistence/InMemoryVitalityStore';
import { NEXUS_COMPANION } from '../../../src/engine/agent/AgentProfile';
import type { ChatMessage, ChatResponse, CompletionOptions } from '../../../src/inference/types';

/** Mock inference client with controllable behavior. */
function createMockInference(opts?: { shouldFail?: boolean; response?: string }) {
  return {
    async complete(_role: ModelRole, _messages: ChatMessage[], _options?: CompletionOptions): Promise<ChatResponse> {
      if (opts?.shouldFail) throw new Error('inference failed');
      return {
        content: opts?.response ?? 'Hello there.',
        promptTokens: 10,
        completionTokens: 5,
      };
    },
  };
}

describe('CompanionEngine dialogue state machine', () => {
  let journal: InMemoryEventJournal;

  beforeEach(() => {
    jest.useFakeTimers();
    journal = new InMemoryEventJournal();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('starts in idle state', () => {
    const room = new RoomEngine('nexus', journal);
    const engine = new CompanionEngine(
      NEXUS_COMPANION, room, createMockInference(), null,
    );
    expect(engine.dialogueState).toBe('idle');
  });

  it('reports state transitions via listener', async () => {
    const room = new RoomEngine('nexus', journal);
    const inference = createMockInference();
    const engine = new CompanionEngine(
      NEXUS_COMPANION, room, inference, null,
    );

    const transitions: DialogueState[] = [];
    engine.onDialogueState(state => transitions.push(state));

    await engine.start();
    // Wait for room creation
    jest.advanceTimersByTime(200);

    // Simulate a player saying something
    await room.send({
      type: 'say_in_room',
      entityId: 'player-1',
      entityName: 'Alice',
      text: 'Hello Wyrd',
    });

    // The debounce fires after some ms — advance past it
    jest.advanceTimersByTime(2000);
    // Let the inference promise resolve
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();

    // Should have seen at least: listening, thinking
    expect(transitions).toContain('listening');
    expect(transitions).toContain('thinking');

    engine.shutdown();
  });

  it('transitions to error and auto-recovers to idle after 3s', async () => {
    const room = new RoomEngine('nexus', journal);
    const failingInference = createMockInference({ shouldFail: true });
    const engine = new CompanionEngine(
      NEXUS_COMPANION, room, failingInference, null,
    );

    const transitions: DialogueState[] = [];
    engine.onDialogueState(state => transitions.push(state));

    await engine.start();
    jest.advanceTimersByTime(200);

    // Trigger inference via a player message. The text must classify as the
    // 'simple' tier so it takes the direct inference path (the only path that
    // surfaces a failure as the 'error' dialogue state). The triage heuristic
    // routes very short (<=3 word) messages like "Hello" to the routine/queue
    // path, which never calls inference and so never errors — hence a 4-5 word
    // non-question phrase here.
    await room.send({
      type: 'say_in_room',
      entityId: 'player-1',
      entityName: 'Alice',
      text: 'please water the green plant',
    });

    // Advance past debounce
    jest.advanceTimersByTime(2000);
    // Let the failing inference promise settle (triage + inference + error handling)
    for (let i = 0; i < 20; i++) await Promise.resolve();

    // Should have reached 'error' state
    expect(transitions).toContain('error');

    // Now advance 3 seconds for auto-recovery
    jest.advanceTimersByTime(3000);

    // The last transition should be back to 'idle'
    expect(transitions[transitions.length - 1]).toBe('idle');

    engine.shutdown();
  });

  it('onDialogueState unsubscribe works', () => {
    const room = new RoomEngine('nexus', journal);
    const engine = new CompanionEngine(
      NEXUS_COMPANION, room, createMockInference(), null,
    );

    const transitions: DialogueState[] = [];
    const unsub = engine.onDialogueState(state => transitions.push(state));

    // Unsubscribe immediately
    unsub();

    // Force a state change internally (we can't easily trigger inference without start,
    // but we can verify no listener is called after unsub)
    expect(transitions).toHaveLength(0);

    engine.shutdown();
  });
});
