/**
 * Her deep answer and the offline catch-up leave the phone the way the rest of
 * her requests do, and the catch-up never talks over a new turn.
 *
 *   - The direct household call carries the configured model and auth, and one
 *     leading system message (it used to post with neither, and with every
 *     prompt layer as its own system message).
 *   - The replay goes the way a live deep turn goes: the household when one is
 *     set, else the configured client (the cloud router in API-key mode). It
 *     used to be household-only, so in API-key mode it announced a catch-up,
 *     replayed nothing, and the queue never drained.
 *   - A new turn waits for the replay (it used to start without await), but
 *     only for the request in flight: the replay stops there, a trigger that
 *     arrives meanwhile is deferred as it is behind a busy turn, and a stalled
 *     household is given up after a deadline.
 *   - The router's key and model go only to the router's own remote URL. The
 *     engine keeps its own household URL, and Settings can move the router to
 *     another provider without moving it.
 *   - When delegation is the only thing that answers, the replay goes there.
 *
 * Driven through the real InferenceRouter; what is asserted is on the wire.
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
const OPENROUTER = 'https://openrouter.test/api';
const QUEUED = 'remind me what we planned for the garden';
const QUEUED_2 = 'what did we decide about the new shelves';
const CATCH_UP = 'catches up on earlier conversations...';

interface Sent {
  url: string;
  headers: Record<string, string>;
  body: { model?: string; messages: ChatMessage[] };
}

/** Over 30 words: COMPLEX by heuristic, so no classifier request. */
const deep = (topic: string) => `${topic} ${Array.from({ length: 32 }, (_, i) => `word${i}`).join(' ')}`;

const lastUser = (msgs: ChatMessage[]) => [...msgs].reverse().find((m) => m.role === 'user')!;
/** Every queued request in these tests is Bob's. */
const isReplay = (s: Sent) => lastUser(s.body.messages).content.includes('Bob says: ');

const answerFor = (text: string) =>
  text.includes(QUEUED) ? 'We planned tomatoes.'
  : text.includes(QUEUED_2) ? 'Oak, two shelves.'
  : text.includes('third') ? 'Third answer.'
  : text.includes('second') ? 'Second answer.'
  : 'First answer.';

/**
 * Endpoints that record each request. `down` lists base URLs that refuse;
 * `hold` makes matching requests wait until `release()`, or until the
 * request's own signal aborts it, as fetch does.
 */
function endpoints(opts: { down?: string[]; hold?: (s: Sent) => boolean } = {}) {
  const sent: Sent[] = [];
  let release: () => void = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  globalThis.fetch = jest.fn(async (url: unknown, init?: { body?: string; headers?: Record<string, string>; signal?: AbortSignal }) => {
    const s: Sent = { url: String(url), headers: init?.headers ?? {}, body: JSON.parse(init?.body ?? '{}') };
    sent.push(s);
    if ((opts.down ?? []).some((base) => s.url.startsWith(base))) throw new Error('unreachable');
    if (opts.hold?.(s)) {
      await new Promise<void>((resolve, reject) => {
        if (init?.signal?.aborted) reject(new Error('aborted'));
        held.then(resolve);
        init?.signal?.addEventListener('abort', () => reject(new Error('aborted')));
      });
    }
    const answer = answerFor(lastUser(s.body.messages).content);
    return {
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => ({ choices: [{ message: { content: answer } }], usage: {} }),
      text: async () => '',
    };
  }) as unknown as typeof fetch;
  return { sent, release: () => release() };
}

describe('CompanionEngine — deep answers and the offline catch-up', () => {
  let originalFetch: typeof fetch;
  let room: RoomEngine;
  let router: InferenceRouter;
  let engine: CompanionEngine;
  let queue: OfflineQueue;
  /** What she did in the room, in order: `say: …` and `emote: …`. */
  let acts: string[];

  beforeEach(async () => {
    originalFetch = globalThis.fetch;
    jest.useFakeTimers();
    jest.setSystemTime(new Date('2026-09-23T14:05:00Z'));
    room = new RoomEngine('nexus', new InMemoryEventJournal());
    router = new InferenceRouter(new LlamaService());
    engine = new CompanionEngine(NEXUS_COMPANION, room, router, null);
    queue = new OfflineQueue(createMockAsyncStorage());
    engine.setOfflineQueue(queue);
    acts = [];
    room.onEvent((e) => {
      if ((e.type === 'said' || e.type === 'emoted') && e.entityId === NEXUS_COMPANION.entityId) {
        acts.push(`${e.type === 'said' ? 'say' : 'emote'}: ${e.text}`);
      }
    });
    await engine.start();
  });

  afterEach(() => {
    engine.shutdown();
    jest.useRealTimers();
    globalThis.fetch = originalFetch;
  });

  async function says(name: string, text: string): Promise<void> {
    await room.send({ type: 'say_in_room', entityId: `player-${name}`, entityName: name, text });
    await jest.advanceTimersByTimeAsync(5_000);
  }
  const aliceSays = (text: string) => says('Alice', text);

  /** The app sets the engine's household URL and the router's remote together. */
  function household(url: string): void {
    engine.setRemoteInferenceUrl(url);
    router.setRemoteUrl(url);
  }

  /** The last user message of each request that is not a replay, in the order sent. */
  const turns = (sent: Sent[]) => sent.filter((s) => !isReplay(s)).map((s) => lastUser(s.body.messages).content);

  it('the household call carries the configured model and bearer key, and one leading system message', async () => {
    const { sent } = endpoints();
    router.setRemoteAuth('bearer', 'sk-test');
    router.setRemoteModel('household-9b');
    household(HOUSEHOLD);

    await aliceSays(deep('first'));

    expect(sent).toHaveLength(1);
    const [call] = sent;
    expect(call.url).toBe(`${HOUSEHOLD}/v1/chat/completions`);
    expect(call.body.model).toBe('household-9b');
    expect(call.headers.Authorization).toBe('Bearer sk-test');
    expect(call.body.messages[0].role).toBe('system');
    expect(call.body.messages.slice(1).some((m) => m.role === 'system')).toBe(false);
    expect(acts).toContain('say: First answer.');
  });

  it('an Anthropic-style key goes as x-api-key with anthropic-version', async () => {
    const { sent } = endpoints();
    router.setRemoteAuth('x-api-key', 'sk-ant');
    router.setRemoteModel('claude-sonnet-4-6');
    household(CLOUD);

    await aliceSays(deep('first'));

    expect(sent[0].headers['x-api-key']).toBe('sk-ant');
    expect(sent[0].headers['anthropic-version']).toBe('2023-06-01');
    expect(sent[0].body.model).toBe('claude-sonnet-4-6');
  });

  it('no household URL on the engine yet: the replay goes through the configured router and drains the queue', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus');
    const { sent } = endpoints();
    router.setRemoteUrl(CLOUD);
    router.setRemoteAuth('bearer', 'sk-test');
    router.setRemoteModel('cloud-model');

    await aliceSays(deep('first'));

    expect(sent).toHaveLength(2);
    const replay = sent[1];
    expect(isReplay(replay)).toBe(true);
    expect(replay.url).toBe(`${CLOUD}/v1/chat/completions`);
    expect(replay.headers.Authorization).toBe('Bearer sk-test');
    expect(replay.body.model).toBe('cloud-model');
    expect(await queue.size()).toBe(0);
    expect(acts).toEqual([
      'emote: is thinking deeply...',
      'say: First answer.',
      `emote: ${CATCH_UP}`,
      `say: About "${QUEUED}" — We planned tomatoes.`,
    ]);
  });

  it('household unreachable: the replay falls through to the configured client, as a live turn does', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus');
    const { sent } = endpoints({ down: [HOUSEHOLD] });
    router.setRemoteUrl(CLOUD);
    engine.setRemoteInferenceUrl(HOUSEHOLD);

    await engine.replayOfflineQueue();

    expect(sent.map((s) => s.url)).toEqual([
      `${HOUSEHOLD}/v1/chat/completions`,
      `${CLOUD}/v1/chat/completions`,
    ]);
    expect(await queue.size()).toBe(0);
  });

  it('nothing can answer: no catch-up is announced, and the queue keeps the request', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus');
    endpoints({ down: [HOUSEHOLD] });
    engine.setRemoteInferenceUrl(HOUSEHOLD);

    await engine.replayOfflineQueue();

    expect(acts).toEqual([]);
    expect(await queue.size()).toBe(1);
  });

  it('a new turn waits for the catch-up instead of talking over it', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus');
    const { sent, release } = endpoints({ hold: isReplay });
    engine.setRemoteInferenceUrl(HOUSEHOLD);

    await aliceSays(deep('first'));
    // The live answer is out and the replay is on the wire, held.
    expect(sent).toHaveLength(2);
    expect(isReplay(sent[1])).toBe(true);

    await aliceSays(deep('second'));
    await jest.advanceTimersByTimeAsync(10_000);
    // Her second turn has not gone out while the catch-up is still thinking.
    expect(sent).toHaveLength(2);

    release();
    await jest.advanceTimersByTimeAsync(10_000);

    expect(sent).toHaveLength(3);
    expect(lastUser(sent[2].body.messages).content).toContain('Alice says: second');
    expect(acts.filter((a) => a.startsWith('say: '))).toEqual([
      'say: First answer.',
      `say: About "${QUEUED}" — We planned tomatoes.`,
      'say: Second answer.',
    ]);
    expect(await queue.size()).toBe(0);
  });

  it('two drains at once replay each request once', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus');
    const { sent } = endpoints();
    engine.setRemoteInferenceUrl(HOUSEHOLD);

    await Promise.all([engine.replayOfflineQueue(), engine.replayOfflineQueue()]);

    expect(sent.filter(isReplay)).toHaveLength(1);
    expect(acts.filter((a) => a === `emote: ${CATCH_UP}`)).toHaveLength(1);
  });

  it('the router key and model go only to the router\'s own URL: Settings moved it to OpenRouter, the household keeps none', async () => {
    const { sent } = endpoints();
    household(HOUSEHOLD);
    // Settings → "Sign in with OpenRouter": the router moves, the engine's
    // household URL does not.
    router.setRemoteAuth('bearer', 'sk-or-secret');
    router.setRemoteUrl(OPENROUTER);
    router.setRemoteModel('anthropic/claude-sonnet-4');

    await aliceSays(deep('first'));

    expect(sent).toHaveLength(1);
    expect(sent[0].url).toBe(`${HOUSEHOLD}/v1/chat/completions`);
    expect(sent[0].headers.Authorization).toBeUndefined();
    expect(sent[0].headers['x-api-key']).toBeUndefined();
    expect(sent[0].body.model).toBe('local-model');
    expect(acts).toContain('say: First answer.');
  });

  it('an OpenRouter key never goes to the Anthropic URL the engine kept; the router fallthrough carries it', async () => {
    const { sent } = endpoints({ down: ['https://api.anthropic.test'] });
    household('https://api.anthropic.test');
    router.setRemoteAuth('bearer', 'sk-or-secret');
    router.setRemoteUrl(OPENROUTER);

    await aliceSays(deep('first'));

    expect(sent.map((s) => s.url)).toEqual([
      'https://api.anthropic.test/v1/chat/completions',
      `${OPENROUTER}/v1/chat/completions`,
    ]);
    expect(sent[0].headers.Authorization).toBeUndefined();
    expect(sent[1].headers.Authorization).toBe('Bearer sk-or-secret');
  });

  it('API-key mode as the app wires it (engine and router on the cloud URL): the replay is authenticated and drains', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus');
    const { sent } = endpoints();
    household(CLOUD);
    router.setRemoteAuth('bearer', 'sk-test');
    router.setRemoteModel('cloud-model');

    await aliceSays(deep('first'));

    expect(sent).toHaveLength(2);
    for (const s of sent) {
      expect(s.url).toBe(`${CLOUD}/v1/chat/completions`);
      expect(s.headers.Authorization).toBe('Bearer sk-test');
      expect(s.body.model).toBe('cloud-model');
    }
    expect(isReplay(sent[1])).toBe(true);
    expect(await queue.size()).toBe(0);
    expect(acts).toEqual([
      'emote: is thinking deeply...',
      'say: First answer.',
      `emote: ${CATCH_UP}`,
      `say: About "${QUEUED}" — We planned tomatoes.`,
    ]);
  });

  it('a turn waits for the request in flight only: the replay stops there and leaves the rest queued', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus');
    await queue.enqueue(QUEUED_2, 'Bob', 'nexus');
    const { release } = endpoints({ hold: isReplay });
    household(HOUSEHOLD);

    await aliceSays(deep('first'));
    await aliceSays(deep('second'));
    release();
    await jest.advanceTimersByTimeAsync(10_000);

    // Her second turn went right after the first catch-up answer, not after the
    // whole backlog; the next drain (after her answer) took the second one.
    expect(acts.filter((a) => a.startsWith('say: '))).toEqual([
      'say: First answer.',
      `say: About "${QUEUED}" — We planned tomatoes.`,
      'say: Second answer.',
      `say: About "${QUEUED_2}" — Oak, two shelves.`,
    ]);
    expect(await queue.size()).toBe(0);
  });

  it('a greeting during the catch-up does not replace the question waiting behind it', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus');
    const { sent, release } = endpoints({ hold: isReplay });
    household(HOUSEHOLD);

    await aliceSays(deep('first'));
    await aliceSays(deep('second'));
    await room.send({ type: 'enter_room', entityId: 'player-Carol', entityName: 'Carol', entityType: 'player', fromDirection: 'south' });
    await jest.advanceTimersByTimeAsync(2_000);
    release();
    await jest.advanceTimersByTimeAsync(10_000);

    expect(turns(sent)).toContainEqual(expect.stringContaining('Alice says: second'));
    expect(acts).toContain('say: Second answer.');
  });

  it('two people speak during the catch-up: the first is answered and the latest deferred, as behind a busy turn', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus');
    const { sent, release } = endpoints({ hold: isReplay });
    household(HOUSEHOLD);

    await aliceSays(deep('first'));
    await aliceSays(deep('second'));
    await says('Carol', deep('third'));
    release();
    await jest.advanceTimersByTimeAsync(20_000);

    expect(turns(sent)).toEqual([
      expect.stringContaining('Alice says: first'),
      expect.stringContaining('Alice says: second'),
      expect.stringContaining('Carol says: third'),
    ]);
    expect(acts.filter((a) => a.startsWith('say: '))).toEqual([
      'say: First answer.',
      `say: About "${QUEUED}" — We planned tomatoes.`,
      'say: Second answer.',
      'say: Third answer.',
    ]);
  });

  it('a household that stalls the replay is given up after a deadline, and her next turn is answered', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus');
    const { sent } = endpoints({ hold: isReplay }); // the replay is never answered
    household(HOUSEHOLD);

    await aliceSays(deep('first'));
    await aliceSays('the garden looks very lovely'); // a SIMPLE turn
    await jest.advanceTimersByTimeAsync(5 * 60_000);

    expect(turns(sent)).toEqual([
      expect.stringContaining('Alice says: first'),
      expect.stringContaining('Alice says: the garden looks very lovely'),
    ]);
    expect(acts).toContain('say: First answer.');
    // The deadline cut the replay's whole path (household, then the router's
    // fallthrough to the same URL): nothing announced, the request kept.
    expect(sent.filter(isReplay)).toHaveLength(2);
    expect(acts).not.toContain(`emote: ${CATCH_UP}`);
    expect(await queue.size()).toBe(1);
  });

  it('when delegation is the only thing that answers, the replay goes there and the queue drains', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus');
    const { sent } = endpoints();
    const delegate = jest.fn(async ({ message }: { message: string }): Promise<DelegationResult> => ({
      text: message.includes(QUEUED) ? 'From the zone: tomatoes.' : 'From the zone.',
      actions: [],
    }));
    engine.setBudDelegation({ delegate } as unknown as BudDelegation);

    await aliceSays(deep('first'));

    expect(sent).toHaveLength(0);
    expect(delegate).toHaveBeenLastCalledWith({ message: QUEUED, recentHistory: [] });
    expect(await queue.size()).toBe(0);
    expect(acts).toEqual([
      'emote: is thinking deeply...',
      'say: From the zone.',
      `emote: ${CATCH_UP}`,
      `say: About "${QUEUED}" — From the zone: tomatoes.`,
    ]);
  });

  it('a turn waiting on a stalled catch-up does not wait for delegation too: the request stays queued', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus');
    const { sent } = endpoints({ hold: isReplay }); // the replay is never answered
    household(HOUSEHOLD);
    // Delegation cannot answer her live turns, only the queued one.
    const delegate = jest.fn(async ({ message }: { message: string }): Promise<DelegationResult | null> =>
      message.includes(QUEUED) ? { text: 'From the zone: tomatoes.', actions: [] } : null);
    engine.setBudDelegation({ delegate } as unknown as BudDelegation);

    await aliceSays(deep('first'));
    await aliceSays('the garden looks very lovely'); // a SIMPLE turn, waiting
    await jest.advanceTimersByTimeAsync(5 * 60_000);

    expect(delegate).not.toHaveBeenCalledWith(expect.objectContaining({ message: QUEUED }));
    expect(turns(sent)).toContainEqual(expect.stringContaining('Alice says: the garden looks very lovely'));
    expect(await queue.size()).toBe(1);
  });

  it('the replay tries the phone\'s own path before delegation, since only that request can say when she was asked', async () => {
    await queue.enqueue(QUEUED, 'Bob', 'nexus', Date.now() - 3 * 3_600_000);
    const { sent } = endpoints();
    household(HOUSEHOLD);
    const delegate = jest.fn(async (): Promise<DelegationResult> => ({ text: 'From the zone.', actions: [] }));
    engine.setBudDelegation({ delegate } as unknown as BudDelegation);

    await aliceSays(deep('first'));

    expect(delegate).toHaveBeenCalledTimes(1); // the live turn only
    expect(sent).toHaveLength(1);
    expect(isReplay(sent[0])).toBe(true);
    expect(lastUser(sent[0].body.messages).content).toContain('[Asked: ');
    expect(await queue.size()).toBe(0);
  });
});
