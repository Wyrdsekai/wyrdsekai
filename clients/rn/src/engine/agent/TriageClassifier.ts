import {ChatMessage, CompletionOptions, ChatResponse} from '../../inference/types';

/**
 * Classifies user input as ROUTINE, SIMPLE, or COMPLEX to route between
 * model tiers: cheapest (greetings) → default → most capable (analysis).
 *
 * Uses a three-tier approach:
 * 1. Heuristic fast-path (no inference): greetings, MUD commands, long input
 * 2. LLM classification (~2s on 0.6B): one-word response
 * 3. Fallback: SIMPLE (if classification fails)
 */

export type Tier = 'routine' | 'simple' | 'complex';

export type InferFn = (
  messages: ChatMessage[],
  options?: CompletionOptions,
) => Promise<ChatResponse>;

// Greeting patterns (case-insensitive)
const GREETING_PATTERNS = new Set([
  'hi', 'hello', 'hey', 'yo', 'sup', 'howdy', 'hiya', 'heya',
  'good morning', 'good evening', 'good afternoon', 'good night',
  'morning', 'afternoon', 'evening', 'gm', 'gn',
  "what's up", 'whats up', 'wassup', 'wazzup',
]);

// Acknowledgment patterns → ROUTINE
const ACK_PATTERNS = new Set([
  'ok', 'okay', 'sure', 'thanks', 'thank you', 'ty', 'thx',
  'yes', 'yeah', 'yep', 'yup', 'ya', 'no', 'nope', 'nah',
  'cool', 'nice', 'great', 'awesome', 'got it', 'understood',
  'right', 'correct', 'fine', 'alright', 'k', 'kk',
  'lol', 'haha', 'heh', 'lmao', 'rofl',
]);

// MUD commands that don't need inference at all
const MUD_COMMANDS = new Set([
  'look', 'l', 'go', 'move', 'take', 'get', 'drop', 'use',
  'inventory', 'help', 'socials',
  'nod', 'smile', 'laugh', 'grin', 'frown', 'shrug', 'sigh',
  'gasp', 'blink', 'wince', 'wave', 'bow', 'clap', 'dance',
  'stretch', 'yawn', 'pace', 'fidget', 'cry', 'cheer', 'groan',
  'blush', 'ponder', 'brood', 'beam', 'sulk', 'hug', 'thank',
  'agree', 'disagree', 'salute', 'welcome',
]);

const CLASSIFICATION_PROMPT = `Classify this message as SIMPLE or COMPLEX.
SIMPLE: greetings, yes/no, navigation, short answers, acknowledgments.
COMPLEX: questions needing thought, personal topics, creative requests, help requests, multi-step tasks.
Answer with one word only.`;

/**
 * Map tier to capability name for InferenceRouter.
 */
export function tierToCapability(tier: Tier): string {
  switch (tier) {
    case 'routine': return 'quick';
    case 'simple': return 'default';
    case 'complex': return 'reasoning';
  }
}

/**
 * Pure heuristic classification. Returns null if undecided (needs LLM).
 */
export function heuristicClassify(text: string): Tier | null {
  const trimmed = text.trim();
  const lower = trimmed.toLowerCase();
  const stripped = lower.replace(/[.!?]+$/, '').trim();

  // Short greetings → ROUTINE
  if (GREETING_PATTERNS.has(stripped)) return 'routine';

  // Acknowledgments → ROUTINE
  if (ACK_PATTERNS.has(stripped)) return 'routine';

  // MUD commands (1-2 words only) → skip inference
  const words = stripped.split(/\s+/);
  const wordCount = words.length;
  if (wordCount <= 2 && MUD_COMMANDS.has(words[0] ?? '')) return null;

  // Very short (1-3 words, no question mark) → ROUTINE
  if (wordCount <= 3 && !trimmed.includes('?')) return 'routine';

  // Short (4-5 words, no question) → SIMPLE
  if (wordCount <= 5 && !trimmed.includes('?')) return 'simple';

  // Long input (> 30 words) → COMPLEX
  if (wordCount > 30) return 'complex';

  // Contains question mark → likely needs thought
  if (trimmed.includes('?') && wordCount > 8) return 'complex';

  // Keywords indicating need for external knowledge or deep reasoning → COMPLEX
  const complexKeywords = [
    'news', 'today', 'yesterday', 'latest', 'recent', 'current',
    'happening', 'happened', 'explain', 'analyze', 'research',
    'summarize', 'review', 'compare', 'investigate', 'story about',
    'tell me about', 'what do you think', 'help me', 'create a',
    'write a', 'build a', 'design a', 'plan a',
  ];
  if (complexKeywords.some(kw => lower.includes(kw))) return 'complex';

  // Undecided — needs LLM
  return null;
}

/**
 * Classify input complexity. Returns tier.
 */
export async function classify(text: string, infer: InferFn): Promise<Tier> {
  const trimmed = text.trim();

  // Heuristic fast-path: no inference needed
  const heuristic = heuristicClassify(trimmed);
  if (heuristic != null) return heuristic;

  // LLM classification
  try {
    return await llmClassify(trimmed, infer);
  } catch {
    return 'simple'; // Fallback
  }
}

/**
 * LLM-based classification. One inference call with tiny prompt.
 */
async function llmClassify(text: string, infer: InferFn): Promise<Tier> {
  const messages: ChatMessage[] = [
    {role: 'system', content: CLASSIFICATION_PROMPT},
    {role: 'user', content: `Message: "${text}"`},
  ];
  const response = await infer(messages, {maxTokens: 8, temperature: 0.1});
  const answer = response.content.trim().toUpperCase();
  return answer.includes('COMPLEX') ? 'complex' : 'simple';
}
