/**
 * Routes inference requests across available backends.
 *
 * Priority chain:
 *   local (100)  — on-device llama.rn
 *   remote (50)  — household/LAN OpenAI-compatible endpoint
 *   server (10)  — cloud relay
 *
 * When a higher-priority backend fails, the router falls through to
 * the next available backend before throwing.
 */

import { ChatMessage, ChatResponse, CompletionOptions } from './types';
import { LlamaService } from './LlamaService';

export type ActiveBackend = 'local' | 'remote' | 'server' | 'none';

/**
 * Which of the companion's two models a request is for.
 *
 * The zone runs these as separate models with different jobs — drive
 * (9B: skills, planning, tool emission, the ReAct loop) and voice
 * (4B + steering vectors: register, presence, polish). The phone ships one
 * model, and it is voice-class. So "can this device do it locally?" has two
 * different answers, and a single backend priority cannot express both.
 *
 *e.
 */
export type ModelRole = 'voice' | 'drive';

/** Backend preference for a role, best first. */
export type BackendChain = readonly Exclude<ActiveBackend, 'none'>[];

/**
 * Voice prefers the device: the on-device model IS voice-class, and register
 * and presence are what it is actually good at.
 */
const VOICE_CHAIN: BackendChain = ['local', 'remote', 'server'];

/**
 * Drive borrows first. Planning and tool emission want the 9B, and the phone
 * does not have it — so when there is anything to borrow from, borrow.
 *
 * `local` stays LAST rather than being removed: with no remote and no server
 * configured the phone is genuinely standalone, and a 4B attempting drive is
 * far better than refusing to think. That is the whole of the "truly
 * standalone attempts drive" rule — it falls out of the ordering, with no
 * separate mode test to keep in sync.
 */
const DRIVE_CHAIN: BackendChain = ['remote', 'server', 'local'];

/**
 * Auth header format used by a remote inference endpoint.
 * - `x-api-key`: Anthropic native (`x-api-key: <key>`)
 * - `bearer`: OpenAI, OpenRouter, llama-server (`Authorization: Bearer <key>`)
 * - `none`: no auth (default for LAN/household llama-server)
 */
export type RemoteAuthType = 'x-api-key' | 'bearer' | 'none';

export class InferenceRouter {
  private llamaService: LlamaService;
  private serverBaseUrl: string | null = null;
  private remoteBaseUrl: string | null = null;
  private remoteAuthType: RemoteAuthType = 'none';
  private remoteApiKey: string | null = null;
  private remoteExtraHeaders: Record<string, string> = {};
  private remoteModel: string | null = null;
  /**
   * Operator override, per role. Mode is intent; tier is a constraint — a hot
   * device escalates more often, it does NOT silently stop being the mode the
   * user chose. A pin says "I know what this hardware can do": a strong laptop
   * may well match the home zone's 4B voice, and can pin `voice: 'local'`.
   */
  private rolePins: Partial<Record<ModelRole, Exclude<ActiveBackend, 'none'>>> = {};
  /**
   * True when the remote slot holds a URL the USER chose, and LAN discovery
   * must not replace it.
   *
   * There is only one remote slot, so "household GPU" and "cloud API" compete
   * for it — that competition IS the mode-4-vs-5 fork, and it is settled here
   * rather than in the routing chain. A mode-5 phone (own node, cloud behind
   * it) sitting on the house LAN would otherwise be dragged onto the home GPU
   * by discovery, silently and durably.
   *
   *c.
   */
  private remotePinned = false;

  constructor(llamaService: LlamaService) {
    this.llamaService = llamaService;
  }

  /** Set the cloud server relay URL (priority 10). */
  setServerUrl(url: string | null): void {
    this.serverBaseUrl = url;
  }

  /**
   * Set the household/LAN remote inference URL (priority 50).
   * Expects an OpenAI-compatible `/v1/chat/completions` endpoint.
   */
  setRemoteUrl(url: string | null): void {
    this.remoteBaseUrl = url;
  }

  /**
   * Configure auth for the remote endpoint. Required for cloud providers
   * (Anthropic, OpenAI, OpenRouter); harmless for unauthenticated LAN
   * llama-server. Maps the Welcome wizard's `apiProvider` choice to the
   * right header shape:
   *   anthropic  → x-api-key
   *   openai     → bearer
   *   openrouter → bearer
   *   custom/llama → none (or bearer if user gave a key)
   * Anthropic also requires `anthropic-version` — set automatically.
   */
  setRemoteAuth(authType: RemoteAuthType, key: string | null): void {
    this.remoteAuthType = authType;
    this.remoteApiKey = key && key.length > 0 ? key : null;
    this.remoteExtraHeaders = {};
    if (authType === 'x-api-key' && this.remoteApiKey) {
      this.remoteExtraHeaders['anthropic-version'] = '2023-06-01';
    }
  }

  /**
   * Set the model name sent in the `model` field of the chat-completion body.
   * Cloud providers (Anthropic, OpenAI, OpenRouter) REJECT requests with a
   * 400 "model: Field required" when it's absent, so this must be set for the
   * cloud-API path. LAN/household llama-server ignores the value. The Welcome
   * wizard maps its `apiProvider` choice to a sane default (see wireCredentials).
   */
  setRemoteModel(model: string | null): void {
    this.remoteModel = model && model.length > 0 ? model : null;
  }

  /** Get the configured remote URL, or null. */
  getRemoteUrl(): string | null {
    return this.remoteBaseUrl;
  }

  /**
   * Which backend is currently the highest-priority available.
   * local=100, remote=50, server=10.
   */
  getActiveBackend(): ActiveBackend {
    if (this.llamaService.isLoaded()) return 'local';
    if (this.remoteBaseUrl) return 'remote';
    if (this.serverBaseUrl) return 'server';
    return 'none';
  }

  /** Pin a role to a backend, or clear the pin by passing null. */
  setRolePin(role: ModelRole, backend: Exclude<ActiveBackend, 'none'> | null): void {
    if (backend) this.rolePins[role] = backend;
    else delete this.rolePins[role];
  }

  /**
   * Backend order for a role. A pin moves one backend to the front; it never
   * REMOVES the others, so a pinned-but-unreachable backend degrades to the
   * next option instead of failing the request outright.
   */
  chainFor(role: ModelRole): BackendChain {
    const base = role === 'drive' ? DRIVE_CHAIN : VOICE_CHAIN;
    const pin = this.rolePins[role];
    if (!pin) return base;
    return [pin, ...base.filter((b) => b !== pin)];
  }

  private canServe(backend: Exclude<ActiveBackend, 'none'>): boolean {
    if (backend === 'local') return this.llamaService.isLoaded();
    if (backend === 'remote') return this.remoteBaseUrl != null;
    return this.serverBaseUrl != null;
  }

  /**
   * Run a chat completion for `role` using the best backend available to it.
   *
   * Voice tries the device first; drive borrows first and only attempts the
   * device when nothing else is configured. Each falls through the rest of its
   * chain on failure, so a dead backend costs a retry rather than the request.
   *
   * @throws If every backend in the chain fails or none is configured.
   */
  async complete(
    role: ModelRole,
    messages: ChatMessage[],
    options?: CompletionOptions,
  ): Promise<ChatResponse> {
    const errors: string[] = [];
    for (const backend of this.chainFor(role)) {
      if (!this.canServe(backend)) continue;
      try {
        if (backend === 'local') {
          return await this.llamaService.complete(messages, options);
        }
        const url = backend === 'remote' ? this.remoteBaseUrl! : this.serverBaseUrl!;
        return await this.completeViaHttp(url, messages, options);
      } catch (e) {
        errors.push(`${backend}: ${e instanceof Error ? e.message : String(e)}`);
      }
    }

    if (errors.length > 0) {
      throw new Error(
        `All inference backends failed for ${role}: ${errors.join('; ')}`,
      );
    }

    throw new Error(
      `No inference backend available for ${role}. Download a model, connect to a household node, or set a server URL.`,
    );
  }

  /** Whether on-device inference is available right now. */
  canInferLocally(): boolean {
    return this.llamaService.isLoaded();
  }

  /** Load a GGUF model into the local LlamaService for on-device inference. */
  async loadLocalModel(modelPath: string, options?: { nCtx?: number; nThreads?: number }): Promise<void> {
    await this.llamaService.loadModel(modelPath, options);
  }

  /**
   * Pin (or release) the remote slot against LAN discovery.
   *
   * Set from the resolved phone mode: mode 5 pins, because the user picked a
   * cloud API and walking into the house must not change that. Mode 4 releases,
   * because borrowing the household GPU is the whole point and a freshly
   * discovered endpoint is the better one.
   */
  pinRemote(pinned: boolean): void {
    this.remotePinned = pinned;
  }

  /** Whether the remote slot is currently protected from discovery. */
  isRemotePinned(): boolean {
    return this.remotePinned;
  }

  /**
   * Offer a discovered endpoint for the remote slot. Returns whether it was
   * taken.
   *
   * Declines when the slot is pinned (see {@link pinRemote}). Otherwise a
   * discovered endpoint wins — on the LAN it is the direct household one,
   * which beats a saved URL that may no longer resolve.
   *
   * The previous behaviour was to overwrite whenever the URL merely DIFFERED,
   * under a comment claiming it protected a manual URL. It did the opposite:
   * every cloud-API user who opened the app at home had their endpoint
   * replaced, and the caller then persisted the replacement.
   */
  setRemoteUrlIfBetter(url: string): boolean {
    if (this.remotePinned) return false;
    this.remoteBaseUrl = url;
    return true;
  }

  /** Whether any backend can handle inference. */
  canInfer(): boolean {
    return this.llamaService.isLoaded() || this.remoteBaseUrl !== null || this.serverBaseUrl !== null;
  }

  /**
   * Call an OpenAI-compatible /v1/chat/completions endpoint.
   * Used for both remote (household) and server (cloud) backends.
   */
  private async completeViaHttp(
    baseUrl: string,
    messages: ChatMessage[],
    options?: CompletionOptions,
  ): Promise<ChatResponse> {
    const url = `${baseUrl}/v1/chat/completions`;
    const body: Record<string, unknown> = {
      // Cloud OpenAI-compat endpoints (Anthropic/OpenAI/OpenRouter) require a
      // `model` field — without it Anthropic returns 400 "model: Field required"
      // and the companion never replies. LAN/household llama-server ignores the
      // value, so a placeholder is safe when no remote model is configured.
      model: this.remoteModel ?? 'local-model',
      messages: messages.map(m => ({ role: m.role, content: m.content })),
      max_tokens: options?.maxTokens ?? 256,
      temperature: options?.temperature ?? 0.7,
      stream: false,
    };
    if (options?.grammar) {
      body.grammar = options.grammar;
    }

    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...this.remoteExtraHeaders,
    };
    if (this.remoteApiKey) {
      if (this.remoteAuthType === 'x-api-key') {
        headers['x-api-key'] = this.remoteApiKey;
      } else if (this.remoteAuthType === 'bearer') {
        headers['Authorization'] = `Bearer ${this.remoteApiKey}`;
      }
    }    const response = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
    });
    if (!response.ok) {
      // Drain the error body so the connection is released cleanly.
      await response.text().catch(() => '');
      throw new Error(`HTTP inference failed: ${response.status} ${response.statusText}`);
    }

    const data = await response.json();
    const choice = data.choices?.[0];

    return {
      content: choice?.message?.content ?? '',
      promptTokens: data.usage?.prompt_tokens ?? 0,
      completionTokens: data.usage?.completion_tokens ?? 0,
    };
  }
}
