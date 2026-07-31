/**
 * Compacts the working memory buffer before Layer 5 injection (RN port).
 *
 * Merges related entries (same action type -> keep only last), drops
 * old low-importance entries, and preserves high-importance entries
 * (commitments, tells, findings).
 */

/** Memory should not exceed this fraction of remaining token budget. */
export const MEMORY_BUDGET_FRACTION = 0.20;

const CHARS_PER_TOKEN = 4;

/** Entries containing these keywords are high-importance (always kept). */
const HIGH_IMPORTANCE_KEYWORDS = [
  'commitment', 'promised', 'tell', 'told', 'found', 'discovered',
  'created plan', 'goal', 'important', 'urgent', 'error', 'failed',
];

/** Action type pattern in memory entries. */
const ACTION_TYPE = /\b(go_to_room|library_search|web_search|remember|note|tell_agent|query_oracle|read_content|equip|doff|goal_done|create_task_plan)\b/;

/**
 * Compact memory buffer if it exceeds the budget fraction.
 *
 * @param memoryBuffer         raw memory buffer string (newline-separated entries)
 * @param remainingTokenBudget tokens remaining after higher-priority layers
 * @returns compacted buffer (may be unchanged if under budget)
 */
export function compact(
  memoryBuffer: string | null | undefined,
  remainingTokenBudget: number,
): string | null | undefined {
  if (!memoryBuffer || memoryBuffer.trim().length === 0) return memoryBuffer;

  const budgetTokens = Math.floor(remainingTokenBudget * MEMORY_BUDGET_FRACTION);
  const currentTokens = estimateTokens(memoryBuffer);

  if (currentTokens <= budgetTokens) {
    return memoryBuffer; // under budget -- no compaction needed
  }

  // Parse into entries
  let entries = memoryBuffer.split('\n');
  if (entries.length === 0) return memoryBuffer;

  // Strategy 1: Deduplicate by action type (keep last of each type)
  entries = deduplicateByAction(entries);

  // Check if under budget now
  let result = entries.join('\n');
  if (estimateTokens(result) <= budgetTokens) return result;

  // Strategy 2: Drop low-importance entries from the beginning
  entries = dropLowImportance(entries);

  // Check if under budget now
  result = entries.join('\n');
  if (estimateTokens(result) <= budgetTokens) return result;

  // Strategy 3: Hard truncate -- keep only the last N entries that fit
  return hardTruncate(entries, budgetTokens);
}

/**
 * Keep only the last entry for each action type.
 * Non-action entries are always kept.
 */
export function deduplicateByAction(entries: string[]): string[] {
  // Track last index for each action type
  const lastByAction = new Map<string, number>();
  for (let i = 0; i < entries.length; i++) {
    const actionType = extractActionType(entries[i]);
    if (actionType) {
      lastByAction.set(actionType, i);
    }
  }

  const result: string[] = [];
  for (let i = 0; i < entries.length; i++) {
    const actionType = extractActionType(entries[i]);
    if (actionType == null || lastByAction.get(actionType) === i) {
      result.push(entries[i]);
    }
  }
  return result;
}

/**
 * Drop entries that are not high-importance.
 * Preserves at least the last 5 entries regardless.
 */
export function dropLowImportance(entries: string[]): string[] {
  if (entries.length <= 5) return entries;

  const result: string[] = [];
  const protectedCount = 5; // always keep last 5
  const dropCandidates = entries.length - protectedCount;

  for (let i = 0; i < entries.length; i++) {
    if (i < dropCandidates && !isHighImportance(entries[i])) {
      continue; // drop
    }
    result.push(entries[i]);
  }
  return result;
}

/**
 * Hard truncate: keep only the last entries that fit within token budget.
 */
export function hardTruncate(entries: string[], budgetTokens: number): string {
  let total = 0;
  let startIdx = entries.length;
  for (let i = entries.length - 1; i >= 0; i--) {
    const entryTokens = estimateTokens(entries[i]);
    if (total + entryTokens > budgetTokens) break;
    total += entryTokens;
    startIdx = i;
  }
  return entries.slice(startIdx).join('\n');
}

export function isHighImportance(entry: string): boolean {
  const lower = entry.toLowerCase();
  return HIGH_IMPORTANCE_KEYWORDS.some(kw => lower.includes(kw));
}

export function extractActionType(entry: string): string | null {
  const m = ACTION_TYPE.exec(entry);
  return m ? m[1] : null;
}

function estimateTokens(text: string): number {
  if (!text || text.length === 0) return 0;
  return Math.max(1, Math.floor(text.length / CHARS_PER_TOKEN));
}
