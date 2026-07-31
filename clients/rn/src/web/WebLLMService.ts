/**
 * WebLLMService — wraps @mlc-ai/web-llm for browser-based inference via WebGPU.
 *
 * This service is only usable in web context (browser tabs with WebGPU support).
 * The @mlc-ai/web-llm package is dynamically imported so native builds are not affected.
 */
import { ChatMessage, ChatResponse, CompletionOptions } from '../inference/types';

/** Browser-compatible models from the MLC model catalog.
 *  q4f32_1 variants use f32 shaders (wider compat, slightly larger).
 *  q4f16_1 variants need shader-f16 (faster, smaller, but not always available in headless).
 */
export const WEB_MODEL_CATALOG = [
  {
    id: 'Qwen3-0.6B-q4f32_1-MLC',
    name: 'Qwen3 0.6B (Browser Tiny)',
    size: 490_000_000,
    description: 'Smallest Qwen3. Works on all WebGPU browsers.',
  },
  {
    id: 'Qwen2.5-0.5B-Instruct-q4f32_1-MLC',
    name: 'Qwen2.5 0.5B Instruct (Browser Tiny)',
    size: 430_000_000,
    description: 'Instruction-tuned tiny model. Works on all WebGPU browsers.',
  },
  {
    id: 'Llama-3.2-1B-Instruct-q4f16_1-MLC',
    name: 'Llama 3.2 1B (Browser Small)',
    size: 680_000_000,
    description: 'Better quality. Requires 4GB+ VRAM and shader-f16 support.',
  },
  {
    id: 'Qwen3-1.7B-q4f32_1-MLC',
    name: 'Qwen3 1.7B (Browser Medium)',
    size: 1_300_000_000,
    description: 'Good quality Qwen3. Requires 4GB+ VRAM.',
  },
];

/**
 * Minimal interface for the @mlc-ai/web-llm MLCEngine.
 * Avoids importing the full type to keep native builds clean.
 */
interface WebLLMEngine {
  chat: {
    completions: {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      create(params: any): Promise<any>;
    };
  };
  unload(): Promise<void>;
}

interface WebLLMChunk {
  choices?: Array<{ delta?: { content?: string } }>;
  usage?: { prompt_tokens?: number; completion_tokens?: number };
}

interface WebLLMResult {
  choices?: Array<{ message?: { content?: string } }>;
  usage?: { prompt_tokens?: number; completion_tokens?: number };
}

export class WebLLMService {
  private engine: WebLLMEngine | null = null;
  private currentModelId: string | null = null;
  private loading: boolean = false;

  isLoaded(): boolean {
    return this.engine !== null && this.currentModelId !== null;
  }

  getLoadedModel(): string | null {
    return this.currentModelId;
  }

  isLoading(): boolean {
    return this.loading;
  }

  /**
   * Load a WebLLM model by ID. Downloads and compiles the model in-browser.
   * Progress callback receives { text, progress } where progress is 0.0-1.0.
   */
  async loadModel(
    modelId: string,
    onProgress?: (progress: { text: string; progress: number }) => void,
  ): Promise<void> {
    if (this.loading) throw new Error('Already loading a model');
    this.loading = true;

    try {
      // Dynamic import for tree-shaking (only loaded in web context)
      const webllm =
        (await import('@mlc-ai/web-llm').catch(() => null));

      if (!webllm) {
        throw new Error(
          '@mlc-ai/web-llm could not be loaded. Ensure it is installed and you are in a browser context.',
        );
      }

      // Check WebGPU support
      if (typeof navigator !== 'undefined' && !('gpu' in navigator)) {
        throw new Error(
          'WebGPU not supported in this browser. Try Chrome 113+ or Edge 113+.',
        );
      }

      // Unload previous model
      if (this.engine) {
        await this.engine.unload();
        this.engine = null;
        this.currentModelId = null;
      }

      const engine = await webllm.CreateMLCEngine(modelId, {
        initProgressCallback: (report: { text: string; progress: number }) => {
          onProgress?.(report);
        },
      });

      this.engine = engine;
      this.currentModelId = modelId;
    } finally {
      this.loading = false;
    }
  }

  /** Unload the current model and free GPU memory. */
  async unloadModel(): Promise<void> {
    if (this.engine) {
      await this.engine.unload();
      this.engine = null;
      this.currentModelId = null;
    }
  }

  /**
   * Run chat completion against the loaded model.
   * Supports both streaming (via options.onToken) and non-streaming modes.
   * Uses the OpenAI-compatible chat.completions API that @mlc-ai/web-llm provides.
   */
  async complete(
    messages: ChatMessage[],
    options?: CompletionOptions,
  ): Promise<ChatResponse> {
    if (!this.engine) {
      throw new Error('No model loaded. Call loadModel() first.');
    }

    const params: Record<string, unknown> = {
      messages: messages.map((m) => ({ role: m.role, content: m.content })),
      max_tokens: options?.maxTokens ?? 256,
      temperature: options?.temperature ?? 0.7,
      stream: !!options?.onToken,
    };

    if (options?.onToken) {
      // Streaming mode
      let fullContent = '';
      let promptTokens = 0;
      let completionTokens = 0;

      const chunks = (await this.engine.chat.completions.create(params)) as AsyncIterable<WebLLMChunk>;
      for await (const chunk of chunks) {
        const delta = chunk.choices?.[0]?.delta?.content ?? '';
        if (delta) {
          fullContent += delta;
          completionTokens++;
          options.onToken(delta);
        }
        if (chunk.usage) {
          promptTokens = chunk.usage.prompt_tokens ?? 0;
          completionTokens = chunk.usage.completion_tokens ?? completionTokens;
        }
      }

      return { content: fullContent, promptTokens, completionTokens };
    } else {
      // Non-streaming mode
      const result = (await this.engine.chat.completions.create(params)) as WebLLMResult;
      const choice = result.choices?.[0];
      return {
        content: choice?.message?.content ?? '',
        promptTokens: result.usage?.prompt_tokens ?? 0,
        completionTokens: result.usage?.completion_tokens ?? 0,
      };
    }
  }

  /** Check if the current browser supports WebGPU. */
  static isWebGPUSupported(): boolean {
    return typeof navigator !== 'undefined' && 'gpu' in navigator;
  }

  /** Check if we are running in a web (browser) platform. */
  static isWebPlatform(): boolean {
    return typeof window !== 'undefined' && typeof document !== 'undefined';
  }
}
