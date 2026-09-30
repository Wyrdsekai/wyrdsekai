/**
 * Every request leaves the phone with one system message, at index 0.
 *
 * The prompt assembler sends each layer as its own system message (identity,
 * room, vitality, recency anchor, …). The Qwen chat template refuses a system
 * message anywhere but first — llama-server answers 400, and llama.rn formats
 * with the same template on the device — and WebLLM throws
 * SystemMessageOrderError. The send points merge them the way the server's
 * PromptAssembler.mergeConsecutiveSystemMessages does, before the NowLine stamp.
 */

import { consolidateSystemMessages } from '../../src/inference/consolidateSystemMessages';
import { InferenceRouter } from '../../src/inference/InferenceRouter';
import { LlamaService } from '../../src/inference/LlamaService';
import { NowLine, dateText, dateTimeText } from '../../src/inference/NowLine';
import type { ChatMessage, ChatResponse, CompletionOptions } from '../../src/inference/types';
import { WebInferenceRouter } from '../../src/web/WebInferenceRouter';
import type { WebLLMService } from '../../src/web/WebLLMService';
import { assemblePrompt } from '../../src/engine/agent/FullPromptAssembler';
import { NEXUS_COMPANION } from '../../src/engine/agent/AgentProfile';
import { initialVitality } from '../../src/engine/agent/VitalityState';
import type { Said } from '../../src/engine/events/WorldEvent';
import type { RoomSnapshot } from '../../src/protocol/models';

const AT = new Date('2026-09-23T14:05:00Z');
const HOUSEHOLD = 'https://household.test';

const snapshot: RoomSnapshot = {
  roomId: 'nexus', name: 'The Nexus', description: 'A crystalline hub.',
  zone: 'foundation',
  exits: [{ direction: 'north', targetRoom: 'terminal', label: 'To Terminal' }],
  entities: [{ id: 'p1', name: 'Alice', type: 'player', description: '' }],
  objects: [],
  hints: [],
};

const said = (entityId: string, entityName: string, text: string): Said =>
  ({ type: 'said', roomId: 'nexus', timestamp: AT.getTime(), entityId, entityName, text });

/** Her full prompt as the assembler builds it: several system layers, then the conversation. */
function layered(): ChatMessage[] {
  const history = [said('p1', 'Alice', 'Morning.'), said('companion-wyrd', 'Wyrd', 'Morning, Alice.')];
  const trigger = said('p1', 'Alice', 'what are we doing today?');
  return assemblePrompt(NEXUS_COMPANION, snapshot, [...history, trigger], trigger, initialVitality());
}

const systems = (msgs: ChatMessage[]) => msgs.filter((m) => m.role === 'system');

/** What the one system message must read: every layer, in layer order, a blank line apart. */
const mergedLayers = (msgs: ChatMessage[]) => systems(msgs).map((m) => m.content).join('\n\n');

function expectOneLeadingSystem(sent: ChatMessage[], from: ChatMessage[]): void {
  expect(sent[0]).toEqual({ role: 'system', content: mergedLayers(from) });
  expect(sent.slice(1).some((m) => m.role === 'system')).toBe(false);
}

class RecordingLlama extends LlamaService {
  received: ChatMessage[][] = [];
  isLoaded(): boolean { return true; }
  async complete(messages: ChatMessage[], _options?: CompletionOptions): Promise<ChatResponse> {
    this.received.push(messages);
    return { content: 'ok', promptTokens: 1, completionTokens: 1 };
  }
}

function recordFetch() {
  const sent: ChatMessage[][] = [];
  globalThis.fetch = jest.fn(async (_url: unknown, init?: { body?: string }) => {
    sent.push(JSON.parse(init?.body ?? '{}').messages);
    return {
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => ({ choices: [{ message: { content: 'ok' } }], usage: {} }),
      text: async () => '',
    };
  }) as unknown as typeof fetch;
  return sent;
}

describe('consolidateSystemMessages', () => {
  it('joins the leading layers into one system message, in order, a blank line apart', () => {
    const out = consolidateSystemMessages([
      { role: 'system', content: 'You are Wyrd.' },
      { role: 'system', content: 'Current location: The Nexus' },
      { role: 'system', content: '[Current state: The Nexus]' },
      { role: 'user', content: 'Alice says: hi' },
      { role: 'assistant', content: 'Hello.' },
      { role: 'user', content: 'Alice says: and now?' },
    ]);
    expect(out).toEqual([
      { role: 'system', content: 'You are Wyrd.\n\nCurrent location: The Nexus\n\n[Current state: The Nexus]' },
      { role: 'user', content: 'Alice says: hi' },
      { role: 'assistant', content: 'Hello.' },
      { role: 'user', content: 'Alice says: and now?' },
    ]);
  });

  it('folds a system message after the conversation into the message before it', () => {
    expect(consolidateSystemMessages([
      { role: 'system', content: 'You are Wyrd.' },
      { role: 'user', content: 'Alice says: hi' },
      { role: 'assistant', content: 'Hello.' },
      { role: 'system', content: 'Keep it short.' },
      { role: 'system', content: 'Answer in English.' },
      { role: 'user', content: 'Alice says: and now?' },
      { role: 'system', content: 'One sentence.' },
    ])).toEqual([
      { role: 'system', content: 'You are Wyrd.' },
      { role: 'user', content: 'Alice says: hi' },
      { role: 'assistant', content: 'Hello.\n\n[system note: Keep it short.\n\nAnswer in English.]' },
      { role: 'user', content: 'Alice says: and now?\n\n[system note: One sentence.]' },
    ]);
  });

  it('a list that opens with the conversation gets no system message', () => {
    expect(consolidateSystemMessages([
      { role: 'user', content: 'Alice says: hi' },
      { role: 'system', content: 'Keep it short.' },
    ])).toEqual([{ role: 'user', content: 'Alice says: hi\n\n[system note: Keep it short.]' }]);
  });

  it('leaves a list that is already one leading system message as it is, and never changes its input', () => {
    const one: ChatMessage[] = [
      { role: 'system', content: 'You are Wyrd.' },
      { role: 'user', content: 'Alice says: hi' },
    ];
    expect(consolidateSystemMessages(one)).toEqual(one);
    expect(consolidateSystemMessages([])).toEqual([]);

    const input = layered();
    const before = JSON.parse(JSON.stringify(input));
    consolidateSystemMessages(input);
    expect(input).toEqual(before);
  });
});

describe('every send point sends one leading system message', () => {
  let originalFetch: typeof fetch;
  beforeEach(() => { originalFetch = globalThis.fetch; });
  afterEach(() => { globalThis.fetch = originalFetch; });

  it('her full prompt really has several system layers (so the rest cannot pass by accident)', () => {
    expect(systems(layered()).length).toBeGreaterThanOrEqual(4);
  });

  it('on the device: what llama.rn formats', async () => {
    const llama = new RecordingLlama();
    await new InferenceRouter(llama).complete('voice', layered(), { now: NowLine.NONE });
    expectOneLeadingSystem(llama.received[0], layered());
  });

  it('through the router to a household or cloud endpoint', async () => {
    const sent = recordFetch();
    const router = new InferenceRouter(new LlamaService());
    router.setRemoteUrl(HOUSEHOLD);
    await router.complete('drive', layered(), { now: NowLine.NONE });
    expectOneLeadingSystem(sent[0], layered());
  });

  it('to a named endpoint (completeAt, the companion\'s household call)', async () => {
    const sent = recordFetch();
    await new InferenceRouter(new LlamaService()).completeAt(HOUSEHOLD, layered(), { now: NowLine.NONE });
    expectOneLeadingSystem(sent[0], layered());
  });

  it('in the browser: what WebLLM gets', async () => {
    const received: ChatMessage[][] = [];
    const service = {
      isLoaded: () => true,
      complete: async (messages: ChatMessage[]) => {
        received.push(messages);
        return { content: 'ok', promptTokens: 1, completionTokens: 1 };
      },
    } as unknown as WebLLMService;
    await new WebInferenceRouter(service).complete('voice', layered(), { now: NowLine.NONE });
    expectOneLeadingSystem(received[0], layered());
  });
});

describe('the merge runs before the NowLine stamp', () => {
  it('DATE_TIME stays on the last user message; the one system message does not carry it', async () => {
    const llama = new RecordingLlama();
    await new InferenceRouter(llama).complete('voice', layered(), { now: NowLine.dateTime(AT) });

    const out = llama.received[0];
    expect(out[0]).toEqual({ role: 'system', content: mergedLayers(layered()) });
    expect(out[out.length - 1]).toEqual({
      role: 'user',
      content: `${dateTimeText(AT)}\nAlice says: what are we doing today?`,
    });
    expect(out.filter((m) => m.content.includes('[Now: '))).toHaveLength(1);
  });

  it('DATE opens the one system message, once', async () => {
    const llama = new RecordingLlama();
    await new InferenceRouter(llama).complete('voice', layered(), { now: NowLine.date(AT) });

    const out = llama.received[0];
    expect(out[0]).toEqual({ role: 'system', content: `${dateText(AT)}\n${mergedLayers(layered())}` });
    expect(out.filter((m) => m.content.includes('Today is '))).toHaveLength(1);
  });

  it('a request with no user turn: the Now line comes after the merged system message', async () => {
    // Stamping first would put the line between the two layers, and the merge
    // would then fold the second layer into it as a [system note].
    const llama = new RecordingLlama();
    await new InferenceRouter(llama).complete('voice', [
      { role: 'system', content: 'You are Wyrd.' },
      { role: 'system', content: 'Current location: The Nexus' },
    ], { now: NowLine.dateTime(AT) });

    expect(llama.received[0]).toEqual([
      { role: 'system', content: 'You are Wyrd.\n\nCurrent location: The Nexus' },
      { role: 'user', content: dateTimeText(AT) },
    ]);
  });

  it('every backend in the fallthrough gets the same bytes, merged and stamped once', async () => {
    const sent = recordFetch();
    const llama = new RecordingLlama();
    llama.complete = async (messages: ChatMessage[]) => {
      llama.received.push(messages);
      throw new Error('on-device failed');
    };
    const router = new InferenceRouter(llama);
    router.setRemoteUrl(HOUSEHOLD);

    await router.complete('voice', layered(), { now: NowLine.date(AT) });

    expect(sent[0]).toEqual(llama.received[0]);
    expect(systems(sent[0])).toHaveLength(1);
    expect(sent[0].filter((m) => m.content.includes('Today is '))).toHaveLength(1);
  });
});
