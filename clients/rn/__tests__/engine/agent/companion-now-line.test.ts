/**
 * Every CompanionEngine path where she speaks leaves the phone with the date
 * and time on its last user message; the triage classifier's request leaves
 * without it; an offline request replayed later says when it was asked.
 *
 * Driven end to end through the real InferenceRouter against a recorded
 * household endpoint, so what is asserted is the request body on the wire.
 */

import { CompanionEngine } from '../../../src/engine/agent/CompanionEngine';
import { OfflineQueue } from '../../../src/engine/agent/OfflineQueue';
import { NEXUS_COMPANION } from '../../../src/engine/agent/AgentProfile';
import { RoomEngine } from '../../../src/engine/room/RoomEngine';
import { InMemoryEventJournal } from '../../../src/engine/persistence/InMemoryEventJournal';
import { InferenceRouter } from '../../../src/inference/InferenceRouter';
import { LlamaService } from '../../../src/inference/LlamaService';
import { askedText, dateTimeText } from '../../../src/inference/NowLine';
import type { ChatMessage } from '../../../src/inference/types';
import { createMockAsyncStorage } from '../../helpers/mockAsyncStorage';

const HOUSEHOLD = 'https://household.test';

interface Sent {
  url: string;
  at: Date;
  messages: ChatMessage[];
}

/**
 * A household endpoint that records each request (with the clock when it was
 * sent) and answers the classifier with a tier and everything else with speech.
 * `failFirst` rejects that many calls first, each after `hangMs` on the clock
 * (a connect that hangs until the OS gives up).
 */
function household(opts: { tier?: 'SIMPLE' | 'COMPLEX'; failFirst?: number; hangMs?: number } = {}) {
  const sent: Sent[] = [];
  let failures = opts.failFirst ?? 0;
  globalThis.fetch = jest.fn(async (url: unknown, init?: { body?: string }) => {
    const messages: ChatMessage[] = JSON.parse(init?.body ?? '{}').messages;
    sent.push({ url: String(url), at: new Date(), messages });
    if (failures > 0) {
      failures--;
      if (opts.hangMs) jest.setSystemTime(Date.now() + opts.hangMs);
      throw new Error('household unreachable');
    }
    const classifying = messages[0]?.content.startsWith('Classify this message');
    return {
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => ({
        choices: [{ message: { content: classifying ? (opts.tier ?? 'SIMPLE') : 'It is a good day for it.' } }],
        usage: { prompt_tokens: 1, completion_tokens: 1 },
      }),
      text: async () => '',
    };
  }) as unknown as typeof fetch;
  return sent;
}

const lastUser = (msgs: ChatMessage[]) => [...msgs].reverse().find((m) => m.role === 'user')!;
const carriesNow = (m: ChatMessage) => m.content.includes('[Now: ');

describe('CompanionEngine — what leaves the phone knows the date and time', () => {
  let originalFetch: typeof fetch;
  let room: RoomEngine;
  let router: InferenceRouter;
  let engine: CompanionEngine;

  beforeEach(async () => {
    originalFetch = globalThis.fetch;
    jest.useFakeTimers();
    jest.setSystemTime(new Date('2026-09-23T14:05:00Z'));
    room = new RoomEngine('nexus', new InMemoryEventJournal());
    router = new InferenceRouter(new LlamaService());
    router.setRemoteUrl(HOUSEHOLD);
    engine = new CompanionEngine(NEXUS_COMPANION, room, router, null);
  });

  afterEach(() => {
    engine.shutdown();
    jest.useRealTimers();
    globalThis.fetch = originalFetch;
  });

  async function say(text: string): Promise<string> {
    await engine.start();
    const spoke = new Promise<string>((resolve) => engine.onSpeech(resolve));
    await room.send({ type: 'say_in_room', entityId: 'player-1', entityName: 'Alice', text });
    await jest.advanceTimersByTimeAsync(5_000);
    return spoke;
  }

  it('quick path: her reply request is stamped, the classifier request is not', async () => {
    const sent = household({ tier: 'SIMPLE' });
    // Undecided by the heuristics, so the LLM classifier runs first.
    const text = 'we could name the garden shed after someone';

    await say(text);

    expect(sent).toHaveLength(2);
    const [classifier, reply] = sent;
    expect(classifier.messages.some(carriesNow)).toBe(false);
    expect(classifier.messages.some((m) => m.content.includes('Today is '))).toBe(false);

    expect(reply.url).toBe(`${HOUSEHOLD}/v1/chat/completions`);
    expect(lastUser(reply.messages).content).toBe(`${dateTimeText(reply.at)}\nAlice says: ${text}`);
    expect(reply.messages[0].role).toBe('system');
    expect(reply.messages[0].content.startsWith('You are Wyrd.')).toBe(true);
  });

  it('full prompt: only the last user message carries the line, never a system layer', async () => {
    const sent = household();
    // Over 30 words: COMPLEX by heuristic — no classifier call, the full
    // assembled prompt goes to the configured client.
    const text = Array.from({ length: 32 }, (_, i) => `word${i}`).join(' ');

    await say(text);

    expect(sent).toHaveLength(1);
    const [deep] = sent;
    expect(lastUser(deep.messages).content).toBe(`${dateTimeText(deep.at)}\nAlice says: ${text}`);
    expect(deep.messages.filter(carriesNow)).toHaveLength(1);
    expect(deep.messages.filter((m) => m.role === 'system').some(carriesNow)).toBe(false);
  });

  it('acknowledgement when she cannot think deeply is stamped too', async () => {
    // The deep request fails; the quick acknowledgement answers.
    const sent = household({ failFirst: 1 });
    engine.setOfflineQueue(new OfflineQueue(createMockAsyncStorage()));
    const text = Array.from({ length: 32 }, (_, i) => `word${i}`).join(' ');

    await say(text);

    expect(sent).toHaveLength(2);
    const ack = sent[1];
    expect(ack.messages[0].content).toContain('Acknowledge briefly');
    expect(lastUser(ack.messages).content).toBe(`${dateTimeText(ack.at)}\nAlice says: ${text}`);
  });

  it('offline replay: answered now, and says when it was asked', async () => {
    const asked = new Date('2026-09-23T13:40:00Z');
    jest.setSystemTime(asked);
    const queue = new OfflineQueue(createMockAsyncStorage());
    await queue.enqueue('remind me what we planned for the garden', 'Bob', 'nexus');

    jest.setSystemTime(new Date('2026-09-23T14:05:00Z'));
    const sent = household();
    engine.setOfflineQueue(queue);
    engine.setRemoteInferenceUrl(HOUSEHOLD);
    await engine.start();

    await engine.replayOfflineQueue();

    expect(sent).toHaveLength(1);
    const [replay] = sent;
    expect(lastUser(replay.messages).content).toBe(
      `${dateTimeText(replay.at)}\n${askedText(asked)}\nBob says: remind me what we planned for the garden`,
    );
    expect(await queue.size()).toBe(0);
  });

  it('a request queued after the fallbacks hung says when she was asked, not when it was queued', async () => {
    // Alice speaks at 14:05:00; the deep request hangs three minutes before it
    // fails, so the queue is written at ~14:08. The replay runs 30 s after that
    // write, so a queue-time stamp would print 14:08 or drop the line entirely.
    const spokeAt = new Date('2026-09-23T14:05:00Z');
    household({ failFirst: 1, hangMs: 3 * 60_000 });
    const queue = new OfflineQueue(createMockAsyncStorage());
    engine.setOfflineQueue(queue);
    const text = Array.from({ length: 32 }, (_, i) => `word${i}`).join(' ');

    await say(text);

    const [pending] = await queue.pending();
    expect(pending.timestamp).toBe(spokeAt.getTime());

    jest.setSystemTime(Date.now() + 30_000);
    const sent = household();
    engine.setRemoteInferenceUrl(HOUSEHOLD);
    await engine.replayOfflineQueue();

    const [replay] = sent;
    expect(lastUser(replay.messages).content).toBe(
      `${dateTimeText(replay.at)}\n${askedText(spokeAt)}\nAlice says: ${text}`,
    );
  });

  it('offline replay within the minute adds no Asked line', async () => {
    const queue = new OfflineQueue(createMockAsyncStorage());
    await queue.enqueue('remind me what we planned for the garden', 'Bob', 'nexus');
    const sent = household();
    engine.setOfflineQueue(queue);
    engine.setRemoteInferenceUrl(HOUSEHOLD);
    await engine.start();

    await engine.replayOfflineQueue();

    expect(lastUser(sent[0].messages).content).toBe(
      `${dateTimeText(sent[0].at)}\nBob says: remind me what we planned for the garden`,
    );
  });

  it('her full prompt says how long since this person last spoke; the date and time ride only the Now line', async () => {
    const sent = household();
    await engine.start();
    const deep = (topic: string) => `${topic} ${Array.from({ length: 32 }, (_, i) => `word${i}`).join(' ')}`;
    const aliceSays = async (text: string) => {
      await room.send({ type: 'say_in_room', entityId: 'player-1', entityName: 'Alice', text });
      await jest.advanceTimersByTimeAsync(5_000);
    };

    await aliceSays(deep('good night'));
    // Bob spoke an hour ago: "you" is Alice, so it is her gap, not his.
    jest.setSystemTime(new Date('2026-09-23T16:05:00Z'));
    await room.send({ type: 'say_in_room', entityId: 'player-2', entityName: 'Bob', text: 'anyone still up then' });
    jest.setSystemTime(new Date('2026-09-23T17:05:00Z'));
    await aliceSays(deep('morning'));

    const turn = sent[sent.length - 1];
    // One leading system message on the wire, the layers merged into it.
    const [system] = turn.messages.filter((m) => m.role === 'system');
    expect(system.content.split('\n')).toContain('Last heard from you: 3 hours ago.');
    expect(carriesNow(system)).toBe(false);
    expect(lastUser(turn.messages).content.startsWith(dateTimeText(turn.at))).toBe(true);
  });
});
