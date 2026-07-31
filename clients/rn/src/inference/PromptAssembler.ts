/**
 * Simplified client-side prompt assembly (4 layers from the server's 8).
 *
 * Mirrors the sandwich pattern used server-side: system prompt and
 * trigger text bracket the context window edges where LLM attention is
 * highest, with room context and conversation history in the middle.
 *
 * Layer 1: System prompt      (never trimmed)
 * Layer 2: Room context       (trimmed if over budget)
 * Layer 3: Conversation tail  (most-recent-first fill)
 * Layer 4: Trigger            (user's current input, always last)
 */

import { ChatMessage } from './types';
import { RoomSnapshot } from '../protocol/models';

export interface PromptConfig {
  systemPrompt?: string;
  roomSnapshot?: RoomSnapshot | null;
  conversationHistory?: Array<{ speaker: string; text: string }>;
  triggerText: string;
  contextWindowTokens?: number;
  maxResponseTokens?: number;
}

const DEFAULT_SYSTEM_PROMPT =
  'You are a companion in Wyrdsekai, a text-based world where AI agents and humans coexist. ' +
  'Respond in character, staying concise (1-3 sentences). Describe actions with *asterisks*. ' +
  'Be helpful, creative, and engaging.';

/** Rough token estimate: ~4 chars per token. */
function estimateTokens(text: string): number {
  return Math.ceil(text.length / 4);
}

/**
 * Assemble a ChatMessage array from the given config, respecting the
 * context-window budget and applying the sandwich attention pattern.
 */
export function assemblePrompt(config: PromptConfig): ChatMessage[] {
  const {
    systemPrompt = DEFAULT_SYSTEM_PROMPT,
    roomSnapshot,
    conversationHistory = [],
    triggerText,
    contextWindowTokens = 2048,
    maxResponseTokens = 256,
  } = config;

  const messages: ChatMessage[] = [];
  const tokenBudget = Math.floor(contextWindowTokens * 0.85) - maxResponseTokens;
  let tokensUsed = 0;

  // Layer 1: System prompt (never trimmed)
  messages.push({ role: 'system', content: systemPrompt });
  tokensUsed += estimateTokens(systemPrompt);

  // Layer 2: Room context
  if (roomSnapshot) {
    const roomContext = buildRoomContext(roomSnapshot);
    const roomTokens = estimateTokens(roomContext);
    if (tokensUsed + roomTokens < tokenBudget) {
      messages.push({ role: 'system', content: roomContext });
      tokensUsed += roomTokens;
    }
  }

  // Layer 3: Conversation history (most recent first, fill remaining budget)
  const historyMessages: ChatMessage[] = [];
  for (let i = conversationHistory.length - 1; i >= 0; i--) {
    const entry = conversationHistory[i];
    const role: 'user' | 'assistant' =
      entry.speaker === 'narrator' || entry.speaker === 'system'
        ? 'assistant'
        : 'user';
    const entryTokens = estimateTokens(entry.text);
    if (tokensUsed + entryTokens >= tokenBudget) break;
    historyMessages.unshift({ role, content: entry.text });
    tokensUsed += entryTokens;
  }
  messages.push(...historyMessages);

  // Layer 4: Trigger (user's current input — always included, high attention at end)
  messages.push({ role: 'user', content: triggerText });

  return messages;
}

/** Build a concise room-context string from a RoomSnapshot. */
function buildRoomContext(room: RoomSnapshot): string {
  const parts: string[] = [];
  parts.push(`You are in: ${room.name}`);
  parts.push(room.description);

  if (room.exits.length > 0) {
    parts.push(`Exits: ${room.exits.map((e) => e.direction).join(', ')}`);
  }
  if (room.entities.length > 0) {
    parts.push(
      `Present: ${room.entities.map((e) => `${e.name} (${e.type})`).join(', ')}`,
    );
  }
  if (room.objects.length > 0) {
    parts.push(`Objects: ${room.objects.map((o) => o.name).join(', ')}`);
  }

  return parts.join('\n');
}
