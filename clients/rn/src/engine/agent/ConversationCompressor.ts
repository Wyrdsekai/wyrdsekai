/**
 * Compresses conversation history when it exceeds a fraction of the context budget (RN port).
 *
 * Deterministic extraction -- no LLM call. Pattern-matches action types and
 * key nouns from older messages, producing a single summary prefix followed by
 * the most recent messages verbatim.
 */

import type { ChatMessage } from '../../inference/types';

/** When conversation exceeds this fraction of usable context, compress. */
export const COMPRESS_THRESHOLD = 0.40;

/** Keep this many recent messages verbatim (never compressed). */
export const KEEP_RECENT = 3;

const CHARS_PER_TOKEN = 4;

/** JSON action block pattern for extraction. */
const ACTION_PATTERN = /"action"\s*:\s*"([^"]+)"/;

/**
 * Compress conversation history if it exceeds the budget threshold.
 *
 * @param history             full conversation history (user + assistant messages)
 * @param contextWindowTokens total context window size in tokens
 * @param maxResponseTokens   tokens reserved for response
 * @returns compressed history (may be unchanged if under threshold)
 */
export function compress(
  history: ChatMessage[],
  contextWindowTokens: number,
  maxResponseTokens: number,
): ChatMessage[] {
  if (!history || history.length <= KEEP_RECENT) {
    return history;
  }

  const usableTokens = Math.floor(contextWindowTokens * 0.85) - maxResponseTokens;
  const historyTokens = history.reduce((sum, m) => sum + estimateTokens(m.content), 0);

  if (historyTokens <= usableTokens * COMPRESS_THRESHOLD) {
    return history; // under threshold -- no compression needed
  }

  // Split into older and recent
  const splitPoint = history.length - KEEP_RECENT;
  const older = history.slice(0, splitPoint);
  const recent = history.slice(splitPoint);

  // Extract summaries from older messages
  const summaries: string[] = [];
  for (const msg of older) {
    const summary = summarizeMessage(msg);
    if (summary) {
      summaries.push(summary);
    }
  }

  const result: ChatMessage[] = [];
  if (summaries.length > 0) {
    const summaryText = `[Earlier conversation: ${summaries.join('. ')}]`;
    result.push({ role: 'system', content: summaryText });
  }
  result.push(...recent);
  return result;
}

/**
 * Extract a one-line summary from a single message.
 * Returns null if the message has no useful content to summarize.
 */
export function summarizeMessage(msg: ChatMessage): string | null {
  const content = msg.content;
  if (!content || content.trim().length === 0) return null;

  // Extract action type if present
  const actionMatch = ACTION_PATTERN.exec(content);
  const actionType = actionMatch ? actionMatch[1] : null;

  // Determine speaker
  let speaker = msg.role === 'assistant' ? 'Agent' : 'User';

  if (actionType) {
    return `${speaker} ${describeActionBriefly(actionType, content)}`;
  }

  // For plain speech, extract first meaningful sentence
  let text = content;
  // Strip any speaker prefix like "Name says: "
  const saysIdx = text.indexOf(' says: ');
  if (saysIdx > 0 && saysIdx < 40) {
    speaker = text.substring(0, saysIdx);
    text = text.substring(saysIdx + 7);
  }

  // Truncate to first sentence
  let end = Math.min(text.length, 80);
  for (let i = 0; i < end; i++) {
    if (text[i] === '.' || text[i] === '!' || text[i] === '?') {
      end = i + 1;
      break;
    }
  }
  return `${speaker} said: ${text.substring(0, end).trim()}`;
}

function describeActionBriefly(actionType: string, content: string): string {
  switch (actionType) {
    case 'go_to_room':
      return `navigated${extractTarget(content)}`;
    case 'library_search':
      return `searched library${extractQuery(content)}`;
    case 'web_search':
      return `searched web${extractQuery(content)}`;
    case 'tell_agent':
      return `told agent${extractTarget(content)}`;
    case 'create_task_plan':
      return 'created a task plan';
    case 'goal_done':
      return 'completed a goal';
    case 'read_content':
      return 'read content';
    case 'query_oracle':
      return 'queried Oracle';
    case 'remember':
      return 'remembered something';
    default:
      return `performed ${actionType.replace(/_/g, ' ')}`;
  }
}

function extractTarget(content: string): string {
  const m = /"target"\s*:\s*"([^"]+)"/.exec(content);
  return m ? ` to ${m[1]}` : '';
}

function extractQuery(content: string): string {
  const m = /"query"\s*:\s*"([^"]+)"/.exec(content);
  return m ? ` for '${m[1]}'` : '';
}

function estimateTokens(text: string): number {
  if (!text || text.length === 0) return 0;
  return Math.max(1, Math.floor(text.length / CHARS_PER_TOKEN));
}
