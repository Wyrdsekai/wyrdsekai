/**
 * WebInferenceRouter — routes inference requests between WebLLM (in-browser)
 * and a remote server relay.
 *
 * On web, local inference means WebGPU via WebLLM.
 * Falls back to 'none' if no backend is available.
 */
import { ChatMessage, ChatResponse, CompletionOptions } from '../inference/types';
import type { ModelRole } from '../inference/InferenceRouter';
import { WebLLMService } from './WebLLMService';

export type WebActiveBackend = 'webllm' | 'server' | 'none';

export class WebInferenceRouter {
  private webLLMService: WebLLMService;

  constructor(webLLMService: WebLLMService) {
    this.webLLMService = webLLMService;
  }

  /** Determine which backend is currently active. */
  getActiveBackend(): WebActiveBackend {
    if (this.webLLMService.isLoaded()) return 'webllm';
    return 'none';
  }

  /** Whether a local model is loaded and ready for inference. */
  canInferLocally(): boolean {
    return this.webLLMService.isLoaded();
  }

  /**
   * Run inference using the best available backend.
   * Currently supports WebLLM only; server relay is a future extension.
   */
  async complete(
    role: ModelRole,
    messages: ChatMessage[],
    options?: CompletionOptions,
  ): Promise<ChatResponse> {
    // WebLLM is the only backend here, so both roles land on it. The parameter
    // is kept so this satisfies CompanionInferenceClient and so the day a
    // borrow path is added, callers already say which model they want.
    if (this.canInferLocally()) {
      return this.webLLMService.complete(messages, options);
    }
    throw new Error(
      `No inference backend available for ${role}. Load a WebLLM model or connect to a server.`,
    );
  }
}
