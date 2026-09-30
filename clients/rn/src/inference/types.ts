/** Shared inference types — matches server-side InferenceClient contract. */

import type { NowLine } from './NowLine';

export interface ChatMessage {
  role: 'system' | 'user' | 'assistant';
  content: string;
}

export interface ChatResponse {
  content: string;
  promptTokens: number;
  completionTokens: number;
}

export interface CompletionOptions {
  maxTokens?: number;
  temperature?: number;
  onToken?: (token: string) => void;
  /** GBNF grammar string for constrained generation (llama.cpp). Null = unconstrained. */
  grammar?: string;
  /**
   * What this request knows about today; the send point stamps the outgoing
   * copy (see NowLine). Every call site declares one — absent means DATE_TIME
   * as of when sent.
   */
  now?: NowLine;
  /**
   * Gives up on an HTTP request when aborted (fetch's own signal). On-device
   * backends ignore it. The offline replay bounds each request with one, so a
   * stalled household cannot hold the catch-up, and the turns waiting behind
   * it, open forever.
   */
  signal?: AbortSignal;
}

export interface ModelInfo {
  id: string;
  name: string;
  filename: string;
  url: string;
  size: number;
  tier: 'tiny' | 'small' | 'medium' | 'phone';
  description: string;
}
