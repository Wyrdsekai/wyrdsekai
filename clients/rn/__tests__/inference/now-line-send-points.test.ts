/**
 * The phone's send points stamp the outgoing copy with what each request
 * declared (NowLine): the InferenceRouter for every backend — on-device too —
 * and the web router. What she says leaves with `[Now: …]`; what writes her
 * identity or classifies a message leaves untouched.
 */

import { InferenceRouter } from '../../src/inference/InferenceRouter';
import { LlamaService } from '../../src/inference/LlamaService';
import { NowLine, dateTimeText } from '../../src/inference/NowLine';
import type { ChatMessage, ChatResponse, CompletionOptions } from '../../src/inference/types';
import { WebInferenceRouter } from '../../src/web/WebInferenceRouter';
import type { WebLLMService } from '../../src/web/WebLLMService';
import { regenerateIdentity } from '../../src/engine/soul/IdentityEvolver';
import { extractWithLlm } from '../../src/engine/soul/LlmExtractor';
import { emptyFingerprint } from '../../src/engine/soul/PhoneFingerprint';
import { createNamedBootstrap } from '../../src/engine/soul/NamedBootstrapManifest';

const AT = new Date('2026-09-23T14:05:00Z');
const HOUSEHOLD = 'https://household.test';

const turn = (): ChatMessage[] => [
  { role: 'system', content: 'You are Wyrd.' },
  { role: 'user', content: 'Alice says: what are we doing today?' },
];

/** On-device model that records the messages llama.rn would format. */
class RecordingLlama extends LlamaService {
  received: ChatMessage[][] = [];
  fail = false;
  isLoaded(): boolean { return true; }
  async complete(messages: ChatMessage[], _options?: CompletionOptions): Promise<ChatResponse> {
    this.received.push(messages);
    if (this.fail) throw new Error('on-device failed');
    return { content: 'ok', promptTokens: 1, completionTokens: 1 };
  }
}

/** Remote endpoint that records every request body's messages. */
function recordFetch(content = 'A long, thoughtful reply about who I am becoming.') {
  const sent: ChatMessage[][] = [];
  globalThis.fetch = jest.fn(async (_url: unknown, init?: { body?: string }) => {
    sent.push(JSON.parse(init?.body ?? '{}').messages);
    return {
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => ({
        choices: [{ message: { content } }],
        usage: { prompt_tokens: 1, completion_tokens: 1 },
      }),
      text: async () => '',
    };
  }) as unknown as typeof fetch;
  return sent;
}

const hasDateOrTime = (msgs: ChatMessage[]) =>
  msgs.some((m) => m.content.includes('[Now: ') || m.content.includes('Today is '));

describe('InferenceRouter stamps the outgoing copy', () => {
  let originalFetch: typeof fetch;
  beforeEach(() => { originalFetch = globalThis.fetch; });
  afterEach(() => { globalThis.fetch = originalFetch; });

  it('her turn reaches the on-device model stamped; the caller keeps its messages', async () => {
    const llama = new RecordingLlama();
    const router = new InferenceRouter(llama);
    const msgs = turn();

    await router.complete('voice', msgs, { maxTokens: 64, now: NowLine.dateTime(AT) });

    const last = llama.received[0][1];
    expect(last.content).toBe(`${dateTimeText(AT)}\nAlice says: what are we doing today?`);
    expect(llama.received[0][0]).toEqual({ role: 'system', content: 'You are Wyrd.' });
    expect(msgs).toEqual(turn());
  });

  it('her turn reaches a household endpoint stamped', async () => {
    const sent = recordFetch();
    const router = new InferenceRouter(new LlamaService());
    router.setRemoteUrl(HOUSEHOLD);

    await router.complete('drive', turn(), { maxTokens: 64, now: NowLine.dateTime(AT) });

    expect(sent[0][1].content).toBe(`${dateTimeText(AT)}\nAlice says: what are we doing today?`);
  });

  it('every backend in the fallthrough gets the same stamped bytes', async () => {
    const sent = recordFetch();
    const llama = new RecordingLlama();
    llama.fail = true;
    const router = new InferenceRouter(llama);
    router.setRemoteUrl(HOUSEHOLD);

    await router.complete('voice', turn(), { maxTokens: 64, now: NowLine.dateTime() });

    expect(sent[0]).toEqual(llama.received[0]);
    expect(sent[0][1].content.startsWith('[Now: ')).toBe(true);
  });

  it('NONE leaves untouched', async () => {
    const llama = new RecordingLlama();
    const router = new InferenceRouter(llama);
    const msgs = turn();

    await router.complete('voice', msgs, { maxTokens: 8, now: NowLine.NONE });

    expect(llama.received[0]).toEqual(turn());
  });

  it('a request that declares nothing still knows the day', async () => {
    const llama = new RecordingLlama();
    const router = new InferenceRouter(llama);

    await router.complete('voice', turn());

    expect(llama.received[0][1].content).toMatch(/^\[Now: \w+day \d{1,2} \w+ \d{4}, \d{2}:\d{2} .+\]\nAlice says: /);
  });

  it('her identity is written without a date in it', async () => {
    const sent = recordFetch();
    const router = new InferenceRouter(new LlamaService());
    router.setRemoteUrl(HOUSEHOLD);
    const infer = (m: ChatMessage[], o: CompletionOptions) => router.complete('drive', m, o);

    const identity = await regenerateIdentity(infer, createNamedBootstrap('Mia'));

    expect(identity).not.toBeNull();
    expect(sent).toHaveLength(1);
    expect(hasDateOrTime(sent[0])).toBe(false);
  });

  it('the sleep extractor reads her conversation without a date in it', async () => {
    const sent = recordFetch('{"topicAffinities": {}}');
    const router = new InferenceRouter(new LlamaService());
    router.setRemoteUrl(HOUSEHOLD);
    const infer = (m: ChatMessage[], o: CompletionOptions) => router.complete('drive', m, o);

    await extractWithLlm(infer, emptyFingerprint(), [], 'Mia');

    expect(sent).toHaveLength(1);
    expect(hasDateOrTime(sent[0])).toBe(false);
  });
});

describe('WebInferenceRouter stamps the outgoing copy', () => {
  function recordingWebLLM() {
    const received: ChatMessage[][] = [];
    const service = {
      isLoaded: () => true,
      complete: async (messages: ChatMessage[]) => {
        received.push(messages);
        return { content: 'ok', promptTokens: 1, completionTokens: 1 };
      },
    } as unknown as WebLLMService;
    return { service, received };
  }

  it('her turn reaches WebLLM stamped', async () => {
    const { service, received } = recordingWebLLM();
    await new WebInferenceRouter(service).complete('voice', turn(), { now: NowLine.dateTime(AT) });
    expect(received[0][1].content).toBe(`${dateTimeText(AT)}\nAlice says: what are we doing today?`);
  });

  it('NONE leaves untouched', async () => {
    const { service, received } = recordingWebLLM();
    await new WebInferenceRouter(service).complete('voice', turn(), { now: NowLine.NONE });
    expect(received[0]).toEqual(turn());
  });
});
