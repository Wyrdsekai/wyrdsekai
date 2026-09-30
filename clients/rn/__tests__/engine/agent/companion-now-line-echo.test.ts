/**
 * A model that repeats the date line of its request (`[Now: …]`, a replay's
 * `[Asked: …]`) does not have it said in the room as her words. The server
 * strips it (core ActionParser.NOW_LINE_ECHO); the phone had no strip.
 *
 * Every path where a model reply becomes her speech: the quick path, the deep
 * path at the household and through the configured client, the offline
 * catch-up (which speaks its answer as it is, not through parseActions), and
 * delegation. What is asserted is what her room's journal holds.
 *
 * Driven through the real InferenceRouter; the endpoint answers with the date
 * lines of the request it was sent, copied, above the answer.
 */

import { CompanionEngine } from '../../../src/engine/agent/CompanionEngine';
import { OfflineQueue } from '../../../src/engine/agent/OfflineQueue';
import { NEXUS_COMPANION } from '../../../src/engine/agent/AgentProfile';
import { RoomEngine } from '../../../src/engine/room/RoomEngine';
import { InMemoryEventJournal } from '../../../src/engine/persistence/InMemoryEventJournal';
import { InferenceRouter } from '../../../src/inference/InferenceRouter';
import { LlamaService } from '../../../src/inference/LlamaService';
import type { ChatMessage } from '../../../src/inference/types';
import type { BudDelegation, DelegationResult } from '../../../src/engine/between/BudDelegation';
import { createMockAsyncStorage } from '../../helpers/mockAsyncStorage';

const HOUSEHOLD = 'https://household.test';
const CLOUD = 'https://api.cloud.test';
const QUEUED = 'remind me what we planned for the garden';
const NOW_LINE = /\[(?:Now|Asked):/;

/** Over 30 words: COMPLEX by heuristic, so no classifier request. */
const deep = Array.from({ length: 32 }, (_, i) => `word${i}`).join(' ');
/** Five words, no question: SIMPLE by heuristic. */
const quick = 'the garden looks very lovely';

const lastUser = (msgs: ChatMessage[]) => [...msgs].reverse().find((m) => m.role === 'user')!;

/** The request's own `[Now: …]` / `[Asked: …]` lines, as a model copies them. */
const dateLines = (msgs: ChatMessage[]) =>
  lastUser(msgs).content.split('\n').filter((l) => l.startsWith('[Now: ') || l.startsWith('[Asked: '));

/**
 * An endpoint that answers each request with its date lines and then
 * `answer(messages, url)`; an empty answer is the lines alone.
 */
function echoing(answer: (msgs: ChatMessage[], url: string) => string) {
  const sent: ChatMessage[][] = [];
  globalThis.fetch = jest.fn(async (url: unknown, init?: { body?: string }) => {
    const messages: ChatMessage[] = JSON.parse(init?.body ?? '{}').messages;
    sent.push(messages);
    const content = [...dateLines(messages), answer(messages, String(url))].filter((l) => l.length > 0).join('\n');
    return {
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => ({ choices: [{ message: { content } }], usage: {} }),
      text: async () => '',
    };
  }) as unknown as typeof fetch;
  return sent;
}

describe('CompanionEngine — a repeated date line is not her words', () => {
  let originalFetch: typeof fetch;
  let journal: InMemoryEventJournal;
  let room: RoomEngine;
  let router: InferenceRouter;
  let engine: CompanionEngine;
  let queue: OfflineQueue;

  beforeEach(async () => {
    originalFetch = globalThis.fetch;
    jest.useFakeTimers();
    jest.setSystemTime(new Date('2026-09-23T14:05:00Z'));
    journal = new InMemoryEventJournal();
    room = new RoomEngine('nexus', journal);
    router = new InferenceRouter(new LlamaService());
    engine = new CompanionEngine(NEXUS_COMPANION, room, router, null);
    queue = new OfflineQueue(createMockAsyncStorage());
    engine.setOfflineQueue(queue);
    await engine.start();
  });

  afterEach(() => {
    engine.shutdown();
    jest.useRealTimers();
    globalThis.fetch = originalFetch;
  });

  /** What her room's journal holds from her, in order: `say: …` and `emote: …`. */
  const hers = () =>
    journal.allEvents('nexus').flatMap((e) =>
      (e.type === 'said' || e.type === 'emoted') && e.entityId === NEXUS_COMPANION.entityId
        ? [`${e.type === 'said' ? 'say' : 'emote'}: ${e.text}`]
        : []);

  async function aliceSays(text: string): Promise<void> {
    await room.send({ type: 'say_in_room', entityId: 'player-Alice', entityName: 'Alice', text });
    await jest.advanceTimersByTimeAsync(5_000);
  }

  it('the quick path', async () => {
    const sent = echoing(() => 'It is a good day for it.');
    router.setRemoteUrl(HOUSEHOLD);

    await aliceSays(quick);

    expect(dateLines(sent[0])).toHaveLength(1); // the model had a line to copy
    expect(hers()).toEqual(['emote: considers...', 'say: It is a good day for it.']);
  });

  it('the deep path at the household', async () => {
    echoing(() => 'First answer.');
    engine.setRemoteInferenceUrl(HOUSEHOLD);
    router.setRemoteUrl(HOUSEHOLD);

    await aliceSays(deep);

    expect(hers()).toEqual(['emote: is thinking deeply...', 'say: First answer.']);
  });

  it('at the household, a reply that is only the line is no answer: the configured client answers', async () => {
    // It came back as '' and was taken as her answer: nothing was said.
    echoing((_msgs, url) => (url.startsWith(HOUSEHOLD) ? '' : 'From the configured client.'));
    engine.setRemoteInferenceUrl(HOUSEHOLD);
    router.setRemoteUrl(CLOUD);

    await aliceSays(deep);

    expect(hers()).toEqual(['emote: is thinking deeply...', 'say: From the configured client.']);
  });

  it('at the household and the configured client, a reply that is only the line: the turn is queued', async () => {
    // As through the configured client alone (below). It used to be neither
    // queued nor acknowledged: she said nothing after "is thinking deeply".
    echoing((msgs) => (msgs[0].content.includes('Acknowledge briefly') ? "I'll come back to this." : ''));
    engine.setRemoteInferenceUrl(HOUSEHOLD);
    router.setRemoteUrl(HOUSEHOLD);

    await aliceSays(deep);

    expect(await queue.size()).toBe(1);
    expect(hers()).toEqual([
      'emote: is thinking deeply...',
      'emote: makes a mental note...',
      "say: I'll come back to this.",
    ]);
  });

  it('through the configured client, a reply that is only the line is no answer: the turn is queued', async () => {
    // API-key mode: no household URL on the engine, the router on the cloud.
    echoing((msgs) => (msgs[0].content.includes('Acknowledge briefly') ? "I'll come back to this." : ''));
    router.setRemoteUrl(CLOUD);

    await aliceSays(deep);

    expect(await queue.size()).toBe(1);
    expect(hers()).toEqual([
      'emote: is thinking deeply...',
      'emote: makes a mental note...',
      "say: I'll come back to this.",
    ]);
  });

  it('the offline catch-up, which speaks its answer as it is', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus', Date.now() - 25 * 60_000);
    const sent = echoing(() => 'We planned tomatoes.');
    engine.setRemoteInferenceUrl(HOUSEHOLD);

    await engine.replayOfflineQueue();

    expect(dateLines(sent[0])).toHaveLength(2); // [Now: …] and [Asked: …]
    expect(hers()).toEqual([
      'emote: catches up on earlier conversations...',
      `say: About "${QUEUED}" — We planned tomatoes.`,
    ]);
    expect(await queue.size()).toBe(0);
  });

  it('a catch-up answer that is only the lines is no answer: nothing is said and the request stays queued', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus', Date.now() - 25 * 60_000);
    echoing(() => '');
    engine.setRemoteInferenceUrl(HOUSEHOLD);

    await engine.replayOfflineQueue();

    expect(hers()).toEqual([]);
    expect(await queue.size()).toBe(1);
  });

  it('delegation, live and in the catch-up', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus');
    const sent = echoing(() => '');
    const line = '[Now: Wednesday 23 September 2026, 10:05 EDT (UTC-4), morning]';
    const delegate = jest.fn(async ({ message }: { message: string }): Promise<DelegationResult> => ({
      text: `${line}\n${message.includes(QUEUED) ? 'From the zone: tomatoes.' : 'From the zone.'}`,
      actions: [],
    }));
    engine.setBudDelegation({ delegate } as unknown as BudDelegation);

    await aliceSays(deep);

    expect(sent).toHaveLength(0);
    expect(hers()).toEqual([
      'emote: is thinking deeply...',
      'say: From the zone.',
      'emote: catches up on earlier conversations...',
      `say: About "${QUEUED}" — From the zone: tomatoes.`,
    ]);
    expect(hers().some((h) => NOW_LINE.test(h))).toBe(false);
  });

  describe('delegation in the catch-up, with nothing else to answer', () => {
    const line = '[Now: Wednesday 23 September 2026, 10:05 EDT (UTC-4), morning]';
    const narrated = () =>
      journal.allEvents('nexus').flatMap((e) =>
        e.type === 'emoted' && e.entityId === 'narrator' ? [e.text] : []);
    const delegating = (result: DelegationResult) =>
      engine.setBudDelegation({ delegate: jest.fn(async () => result) } as unknown as BudDelegation);

    it('text that is only the line, and no actions, is no answer: nothing is said and it stays queued', async () => {
      // The check ran before delegation, so delegated text was never checked:
      // the intro was said with nothing after it and the request was dropped.
      await queue.enqueue(QUEUED, 'Bob', 'nexus');
      const sent = echoing(() => '');
      delegating({ text: line, actions: [] });

      await engine.replayOfflineQueue();

      expect(sent).toHaveLength(0); // no household, no configured model
      expect(hers()).toEqual([]);
      expect(await queue.size()).toBe(1);
    });

    it('blank text is no answer either', async () => {
      await queue.enqueue(QUEUED, 'Bob', 'nexus');
      echoing(() => '');
      delegating({ text: '', actions: [] });

      await engine.replayOfflineQueue();

      expect(hers()).toEqual([]);
      expect(await queue.size()).toBe(1);
    });

    it('only the line, but it acted: the actions are narrated without the intro and it is answered', async () => {
      await queue.enqueue(QUEUED, 'Bob', 'nexus');
      echoing(() => '');
      delegating({
        text: line,
        actions: [{ type: 'notification', data: { message: 'The tomatoes need water.', priority: 'high' } }],
      });

      await engine.replayOfflineQueue();

      expect(hers()).toEqual(['emote: catches up on earlier conversations...']);
      expect(narrated()).toEqual(['*notification (high)*: The tomatoes need water.']);
      expect(await queue.size()).toBe(0);
    });
  });
});
