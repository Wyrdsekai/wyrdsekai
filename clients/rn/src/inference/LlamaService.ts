/**
 * Wraps llama.rn for on-device GGUF inference.
 *
 * The llama.rn dependency is loaded via dynamic require so the service
 * can be instantiated in environments where the native module is absent
 * (web, unit tests). Callers must handle the Error thrown by loadModel
 * if the module is unavailable.
 */

import { ChatMessage, ChatResponse, CompletionOptions } from './types';

/** Minimal interface matching the llama.rn context shape. */
interface LlamaContext {
  completion(
    params: Record<string, unknown>,
    callback?: (data: { token: string }) => void,
  ): Promise<{
    text?: string;
    timings?: { prompt_n?: number; predicted_n?: number };
  }>;
  release(): Promise<void>;
}

export class LlamaService {
  private context: LlamaContext | null = null;
  private currentModelPath: string | null = null;

  /** Whether a model is currently loaded and ready for inference. */
  isLoaded(): boolean {
    return this.context !== null;
  }

  /** Absolute path of the currently loaded model, or null. */
  getLoadedModel(): string | null {
    return this.currentModelPath;
  }

  /**
   * Load a GGUF model into memory.
   *
   * If another model is already loaded it is unloaded first.
   * @param modelPath Absolute filesystem path to the .gguf file.
   * @param options   Optional tuning knobs forwarded to llama.rn.
   */
  async loadModel(
    modelPath: string,
    options?: {
      nCtx?: number;
      nGpuLayers?: number;
      nThreads?: number;
    },
  ): Promise<void> {
    // Unload previous model
    if (this.context) {
      await this.unloadModel();
    }

    try {
      const { initLlama } = require('llama.rn');
      const result = await initLlama({
        model: modelPath,
        use_mlock: true,
        n_ctx: options?.nCtx ?? 2048,
        n_gpu_layers: options?.nGpuLayers ?? 0,
        n_threads: options?.nThreads ?? 4,
      });
      this.context = result;
      this.currentModelPath = modelPath;
    } catch (e: unknown) {
      throw new Error(`Failed to load model: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  /** Release the loaded model and free memory. */
  async unloadModel(): Promise<void> {
    if (this.context) {
      await this.context.release();
      this.context = null;
      this.currentModelPath = null;
    }
  }

  /**
   * Run chat completion against the loaded model.
   *
   * Supports streaming via `options.onToken`.
   * @throws If no model is loaded.
   */
  async complete(
    messages: ChatMessage[],
    options?: CompletionOptions,
  ): Promise<ChatResponse> {
    if (!this.context) {
      throw new Error('No model loaded');
    }

    const params: Record<string, unknown> = {
      messages: messages.map((m) => ({ role: m.role, content: m.content })),
      n_predict: options?.maxTokens ?? 256,
      temperature: options?.temperature ?? 0.7,
      stop: ['</s>', '<|endoftext|>', '<|im_end|>', '</think>'],
    };
    // GBNF grammar for constrained generation
    if (options?.grammar) {
      params.grammar = options.grammar;
    }

    const result = await this.context.completion(
      params,
      options?.onToken
        ? (data: { token: string }) => {
            options.onToken!(data.token);
          }
        : undefined,
    );

    return {
      content: result.text ?? '',
      promptTokens: result.timings?.prompt_n ?? 0,
      completionTokens: result.timings?.predicted_n ?? 0,
    };
  }

  /**
   * Abort a running completion.
   *
   * llama.rn does not expose a direct abort primitive; releasing the
   * context is the only reliable way to cancel. In practice the
   * completion promise will reject.
   */
  async abort(): Promise<void> {
    // No-op — callers should call unloadModel() for a hard stop.
  }
}
