/**
 * Per-role backend routing, driven by the SHARED parity contract.
 *
 * The cases live in clients/parity/parity.json so RN and KMP are held to the
 * same table. Two independent implementations of one contract only stay honest
 * if the contract is executable — a comment in each file is what let `bin/wyrd`
 * and `wyrd.ps1` drift, and the clients would drift the same way.
 *
 *e.
 */
import parity from '../../../parity/parity.json';
import { InferenceRouter } from '../../src/inference/InferenceRouter';
import type { ModelRole, ActiveBackend } from '../../src/inference/InferenceRouter';
import { LlamaService } from '../../src/inference/LlamaService';
import type { ChatMessage, ChatResponse, CompletionOptions } from '../../src/inference/types';

type Backend = Exclude<ActiveBackend, 'none'>;

interface Case {
  name: string;
  role: ModelRole;
  pin?: Backend;
  available: Backend[];
  expectChain: Backend[];
  expectServedBy: Backend | null;
}

const CASES = (parity as { inferenceRouting: { cases: Case[] } }).inferenceRouting.cases;

const REPLY: ChatResponse = { content: 'ok', promptTokens: 1, completionTokens: 1 };

/** Local backend stands in for llama.rn; `loaded` decides whether it can serve. */
class FakeLlama extends LlamaService {
  constructor(private readonly loaded: boolean) {
    super();
  }
  isLoaded(): boolean {
    return this.loaded;
  }
  async complete(_messages: ChatMessage[], _options?: CompletionOptions): Promise<ChatResponse> {
    served.push('local');
    return REPLY;
  }
}

/** Which backend actually answered — the assertion that matters. */
let served: Backend[] = [];

function build(c: Case): InferenceRouter {
  served = [];
  const router = new InferenceRouter(new FakeLlama(c.available.includes('local')));
  if (c.available.includes('remote')) router.setRemoteUrl('http://remote.invalid');
  if (c.available.includes('server')) router.setServerUrl('http://server.invalid');
  if (c.pin) router.setRolePin(c.role, c.pin);

  // HTTP backends: record which URL was hit rather than making a real request.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (router as any).completeViaHttp = async (url: string) => {
    served.push(url.includes('remote') ? 'remote' : 'server');
    return REPLY;
  };
  return router;
}

describe('inference role routing (shared parity contract)', () => {
  it('the contract has cases', () => {
    expect(CASES.length).toBeGreaterThan(0);
  });

  for (const c of CASES) {
    it(`chain — ${c.name}`, () => {
      expect(build(c).chainFor(c.role)).toEqual(c.expectChain);
    });

    it(`serves — ${c.name}`, async () => {
      const router = build(c);
      const msgs: ChatMessage[] = [{ role: 'user', content: 'hi' }];
      if (c.expectServedBy === null) {
        await expect(router.complete(c.role, msgs)).rejects.toThrow(/No inference backend/);
        expect(served).toEqual([]);
      } else {
        await router.complete(c.role, msgs);
        expect(served[0]).toBe(c.expectServedBy);
      }
    });
  }
});
