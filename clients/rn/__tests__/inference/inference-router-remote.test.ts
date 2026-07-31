import { InferenceRouter, type ActiveBackend } from '../../src/inference/InferenceRouter';
import { LlamaService } from '../../src/inference/LlamaService';
import type { ChatMessage, ChatResponse } from '../../src/inference/types';

/**
 * Mock LlamaService that can be toggled loaded/unloaded
 * and optionally fails on complete().
 */
class MockLlamaService extends LlamaService {
  private _isLoaded = false;
  private _shouldFail = false;
  private _response: ChatResponse = { content: 'local response', promptTokens: 5, completionTokens: 3 };

  isLoaded(): boolean {
    return this._isLoaded;
  }

  setLoaded(loaded: boolean): void {
    this._isLoaded = loaded;
  }

  setShouldFail(fail: boolean): void {
    this._shouldFail = fail;
  }

  setResponse(response: ChatResponse): void {
    this._response = response;
  }

  async complete(_messages: ChatMessage[]): Promise<ChatResponse> {
    if (this._shouldFail) throw new Error('Local inference failed');
    return this._response;
  }
}

/** Create a mock fetch for OpenAI-compatible API. */
function mockFetch(content: string, status = 200) {
  return jest.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 200 ? 'OK' : 'Internal Server Error',
    json: async () => ({
      choices: [{ message: { content } }],
      usage: { prompt_tokens: 10, completion_tokens: 5 },
    }),
  });
}

function mockFetchFailing() {
  return jest.fn().mockRejectedValue(new Error('Network error'));
}

const testMessages: ChatMessage[] = [
  { role: 'user', content: 'Hello' },
];

describe('InferenceRouter remote backend', () => {
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('reports active backend as remote when configured and no local', () => {
    const llama = new MockLlamaService();
    const router = new InferenceRouter(llama);

    expect(router.getActiveBackend()).toBe('none');

    router.setRemoteUrl('http://198.51.100.100:8080');
    expect(router.getActiveBackend()).toBe('remote');
  });

  it('local takes priority over remote', () => {
    const llama = new MockLlamaService();
    llama.setLoaded(true);
    const router = new InferenceRouter(llama);
    router.setRemoteUrl('http://198.51.100.100:8080');

    expect(router.getActiveBackend()).toBe('local');
  });

  it('remote takes priority over server', () => {
    const llama = new MockLlamaService();
    const router = new InferenceRouter(llama);
    router.setRemoteUrl('http://198.51.100.100:8080');
    router.setServerUrl('https://cloud.example.com');

    expect(router.getActiveBackend()).toBe('remote');
  });

  it('falls back to remote when local is unavailable', async () => {
    const llama = new MockLlamaService();
    const router = new InferenceRouter(llama);
    router.setRemoteUrl('http://198.51.100.100:8080');

    globalThis.fetch = mockFetch('remote response');

    const result = await router.complete('voice', testMessages);
    expect(result.content).toBe('remote response');
    expect(globalThis.fetch).toHaveBeenCalledWith(
      'http://198.51.100.100:8080/v1/chat/completions',
      expect.any(Object),
    );
  });

  it('falls back to remote when local fails', async () => {
    const llama = new MockLlamaService();
    llama.setLoaded(true);
    llama.setShouldFail(true);
    const router = new InferenceRouter(llama);
    router.setRemoteUrl('http://198.51.100.100:8080');

    globalThis.fetch = mockFetch('remote fallback');

    const result = await router.complete('voice', testMessages);
    expect(result.content).toBe('remote fallback');
  });

  it('falls back to server when both local and remote fail', async () => {
    const llama = new MockLlamaService();
    llama.setLoaded(true);
    llama.setShouldFail(true);
    const router = new InferenceRouter(llama);
    router.setRemoteUrl('http://198.51.100.100:8080');
    router.setServerUrl('https://cloud.example.com');

    // Remote fails, server succeeds
    let callCount = 0;
    globalThis.fetch = jest.fn().mockImplementation((url: string) => {
      callCount++;
      if (url.includes('198.51.100.100')) {
        return Promise.reject(new Error('Remote network error'));
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: async () => ({
          choices: [{ message: { content: 'server fallback' } }],
          usage: { prompt_tokens: 10, completion_tokens: 5 },
        }),
      });
    });

    const result = await router.complete('voice', testMessages);
    expect(result.content).toBe('server fallback');
  });

  it('throws when all backends fail', async () => {
    const llama = new MockLlamaService();
    llama.setLoaded(true);
    llama.setShouldFail(true);
    const router = new InferenceRouter(llama);
    router.setRemoteUrl('http://198.51.100.100:8080');
    router.setServerUrl('https://cloud.example.com');

    globalThis.fetch = mockFetchFailing();

    await expect(router.complete('voice', testMessages)).rejects.toThrow('All inference backends failed');
  });

  it('throws with clear message when no backends configured', async () => {
    const llama = new MockLlamaService();
    const router = new InferenceRouter(llama);

    await expect(router.complete('voice', testMessages)).rejects.toThrow('No inference backend available');
  });

  it('setRemoteUrl and getRemoteUrl round-trip', () => {
    const llama = new MockLlamaService();
    const router = new InferenceRouter(llama);

    expect(router.getRemoteUrl()).toBeNull();

    router.setRemoteUrl('http://198.51.100.100:8080');
    expect(router.getRemoteUrl()).toBe('http://198.51.100.100:8080');

    router.setRemoteUrl(null);
    expect(router.getRemoteUrl()).toBeNull();
  });

  it('canInfer is true with only remote configured', () => {
    const llama = new MockLlamaService();
    const router = new InferenceRouter(llama);

    expect(router.canInfer()).toBe(false);
    router.setRemoteUrl('http://198.51.100.100:8080');
    expect(router.canInfer()).toBe(true);
    expect(router.canInferLocally()).toBe(false);
  });
});
