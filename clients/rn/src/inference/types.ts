/** Shared inference types — matches server-side InferenceClient contract. */

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
