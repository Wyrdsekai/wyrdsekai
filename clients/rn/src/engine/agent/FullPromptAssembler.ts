/**
 * Full 8-layer sandwich prompt assembler.
 * TypeScript port of KMP's FullPromptAssembler.kt.
 *
 * Layout (high -> low -> high LLM attention):
 *   1.   System prompt         (identity, never trimmed)
 *   1.5  Soul identity         (resident identity + retrieved fragments, never trimmed)
 *   1.6  Mirror calibration    (emotional calibration examples, trimmable)
 *   2.   Room context          (critical for current interaction)
 *   2.5  Additional context    (system metrics, trimmable)
 *   2.5b Bond context          (relationship context, trimmable)
 *   3.   Vitality description  (background modulation, trimmable)
 *   5.   Memory buffer         (room history, trimmable)
 *   5.5  Recency anchor        (state reinforcement)
 *   6.   Conversation history  (recent messages)
 *   7.   Trigger event         (what to respond to)
 */

import type { ChatMessage } from '../../inference/types';
import type { RoomSnapshot } from '../../protocol/models';
import type { Said } from '../events/WorldEvent';
import type { AgentProfile } from './AgentProfile';
import type { VitalityState } from './VitalityState';
import { describeVitality } from './VitalityState';
import type { ClientSoulManifest, ClientSoulFragment } from '../soul/SoulManifest';
import { retrieveFragments } from '../soul/LocalForge';
import type { PhonePrediction } from '../oracle/PhoneOracle';

const CHARS_PER_TOKEN = 4;
const USABLE_FRACTION = 0.85;
const FRAGMENT_BUDGET_FRACTION = 0.30;
const MAX_ORACLE_PREDICTIONS = 5;
const MIN_ORACLE_CONFIDENCE = 0.5;

export function estimateTokens(text: string): number {
  if (text.length === 0) return 0;
  return Math.max(1, Math.floor(text.length / CHARS_PER_TOKEN));
}

/**
 * Extract conversation keywords from recent messages for fragment retrieval.
 * Filters out short/common words, returns a space-separated string.
 */
export function extractConversationKeywords(recentSaid: Said[], triggerEvent: Said | null): string {
  const parts: string[] = [];
  for (const e of recentSaid) {
    parts.push(e.text);
  }
  if (triggerEvent) {
    parts.push(triggerEvent.text);
  }
  return parts.join(' ');
}

/**
 * Build the soul identity block: resident identity + retrieved fragments.
 * Fragments are budget-capped at FRAGMENT_BUDGET_FRACTION of context window.
 */
export function buildSoulIdentityBlock(
  manifest: ClientSoulManifest,
  conversationKeywords: string,
  fragmentBudgetTokens: number,
): string {
  const parts: string[] = [];

  // Resident identity (always included — MEDIUM soul text, ~69 tokens)
  parts.push(manifest.residentIdentity);

  // Retrieve relevant fragments
  const k = manifest.retrievalK ?? 1;
  const fragments = manifest.fragments ?? [];
  if (fragments.length > 0 && k > 0) {
    const retrieved = retrieveFragments(conversationKeywords, fragments, k);
    const budgetRemaining = fragmentBudgetTokens - estimateTokens(manifest.residentIdentity);

    if (budgetRemaining > 0 && retrieved.length > 0) {
      let usedTokens = 0;
      const included: ClientSoulFragment[] = [];
      for (const frag of retrieved) {
        const fragTokens = estimateTokens(frag.text);
        if (usedTokens + fragTokens > budgetRemaining) break;
        included.push(frag);
        usedTokens += fragTokens;
      }
      if (included.length > 0) {
        parts.push('');
        parts.push('## Soul Fragments');
        for (const frag of included) {
          parts.push(`[${frag.category}/${frag.label}]: ${frag.text}`);
        }
      }
    }
  }

  return parts.join('\n');
}

/**
 * Build the mirror calibration block from manifest examples.
 */
export function buildMirrorCalibrationBlock(calibrationExamples: string[]): string {
  return `## Emotional Calibration\n${calibrationExamples.join('\n')}`;
}

export function assemblePrompt(
  profile: AgentProfile,
  roomSnapshot: RoomSnapshot | null,
  recentSaid: Said[],
  triggerEvent: Said | null,
  vitality?: VitalityState | null,
  additionalContext?: string | null,
  memoryBuffer?: string | null,
  soulManifest?: ClientSoulManifest | null,
  oraclePredictions?: PhonePrediction[] | null,
  bondContext?: string | null,
): ChatMessage[] {
  const messages: ChatMessage[] = [];

  // Layer 1: System prompt (never trimmed)
  messages.push({ role: 'system', content: profile.systemPrompt });

  // Calculate token budget
  const usableTokens = Math.floor(profile.contextWindowTokens * USABLE_FRACTION) - profile.maxResponseTokens;
  const systemTokens = estimateTokens(profile.systemPrompt);
  const conversationTokens = recentSaid.reduce(
    (sum, e) => sum + estimateTokens(formatSaidEvent(e, profile.entityId)),
    0,
  ) + (triggerEvent ? estimateTokens(`${triggerEvent.entityName} says: ${triggerEvent.text}`) : 0);
  let remainingBudget = usableTokens - systemTokens - conversationTokens;

  // Layer 1.5: Soul identity (resident identity + retrieved fragments)
  if (soulManifest) {
    const fragmentBudgetTokens = Math.floor(profile.contextWindowTokens * FRAGMENT_BUDGET_FRACTION);
    const keywords = extractConversationKeywords(recentSaid, triggerEvent);
    const soulBlock = buildSoulIdentityBlock(soulManifest, keywords, fragmentBudgetTokens);
    const soulTokens = estimateTokens(soulBlock);
    if (soulTokens <= remainingBudget) {
      messages.push({ role: 'system', content: soulBlock });
      remainingBudget -= soulTokens;
    }
  }

  // Layer 1.6: Mirror calibration (emotional calibration examples)
  if (soulManifest?.mirrorCalibration && soulManifest.mirrorCalibration.length > 0) {
    const calibrationBlock = buildMirrorCalibrationBlock(soulManifest.mirrorCalibration);
    const calibrationTokens = estimateTokens(calibrationBlock);
    if (calibrationTokens <= remainingBudget) {
      messages.push({ role: 'system', content: calibrationBlock });
      remainingBudget -= calibrationTokens;
    }
  }

  // Layer 2: Room context
  if (roomSnapshot) {
    const roomContext = buildRoomContext(roomSnapshot);
    const roomTokens = estimateTokens(roomContext);
    if (roomTokens <= remainingBudget) {
      messages.push({ role: 'system', content: roomContext });
      remainingBudget -= roomTokens;
    } else {
      const trimmed = buildTrimmedContext(roomSnapshot);
      messages.push({ role: 'system', content: trimmed });
      remainingBudget -= estimateTokens(trimmed);
    }
  }

  // Layer 2.5: Additional context (trimmable)
  if (additionalContext) {
    const extraTokens = estimateTokens(additionalContext);
    if (extraTokens <= remainingBudget) {
      messages.push({ role: 'system', content: additionalContext });
      remainingBudget -= extraTokens;
    }
  }

  // Layer 2.5b: Bond context (relationship context, trimmable)
  if (bondContext) {
    const bondTokens = estimateTokens(bondContext);
    if (bondTokens <= remainingBudget) {
      messages.push({ role: 'system', content: bondContext });
      remainingBudget -= bondTokens;
    }
  }

  // Layer 3: Vitality state (background modulation, trimmable)
  if (vitality) {
    const vitalityContext = describeVitality(vitality);
    const vitalityTokens = estimateTokens(vitalityContext);
    if (vitalityTokens <= remainingBudget) {
      messages.push({ role: 'system', content: vitalityContext });
      remainingBudget -= vitalityTokens;
    }
  }

  // Layer 3.25: Oracle predictions (anticipatory insights, trimmable)
  if (oraclePredictions && oraclePredictions.length > 0) {
    const oracleCtx = buildOracleContext(oraclePredictions);
    if (oracleCtx) {
      const oracleTokens = estimateTokens(oracleCtx);
      if (oracleTokens <= remainingBudget) {
        messages.push({ role: 'system', content: oracleCtx });
        remainingBudget -= oracleTokens;
      }
    }
  }

  // Layer 5: Memory buffer (hot/warm room memory, trimmable)
  if (memoryBuffer) {
    const memoryTokens = estimateTokens(memoryBuffer);
    if (memoryTokens <= remainingBudget) {
      messages.push({ role: 'system', content: memoryBuffer });
      remainingBudget -= memoryTokens;
    }
  }

  // Layer 5.5: Recency anchor
  if (roomSnapshot && recentSaid.length > 0) {
    const anchor = buildRecencyAnchor(roomSnapshot, triggerEvent);
    messages.push({ role: 'system', content: anchor });
  }

  // Layer 6: Conversation history
  for (const event of recentSaid) {
    const role: ChatMessage['role'] = event.entityId === profile.entityId ? 'assistant' : 'user';
    const content = formatSaidEvent(event, profile.entityId);
    messages.push({ role, content });
  }

  // Layer 7: Trigger event (if not already in history)
  if (triggerEvent) {
    const alreadyInHistory = recentSaid.length > 0 && recentSaid[recentSaid.length - 1] === triggerEvent;
    if (!alreadyInHistory) {
      messages.push({ role: 'user', content: `${triggerEvent.entityName} says: ${triggerEvent.text}` });
    }
  }

  return messages;
}

export function buildRoomContext(snapshot: RoomSnapshot): string {
  const parts: string[] = [];
  parts.push(`Current location: ${snapshot.name}`);
  parts.push(snapshot.description);

  if (snapshot.entities.length > 0) {
    parts.push(`\nPresent: ${snapshot.entities.map(e => `${e.name} (${e.type})`).join(', ')}`);
  }

  if (snapshot.exits.length > 0) {
    parts.push(`Exits: ${snapshot.exits.map(e => `${e.direction} — ${e.label}`).join('; ')}`);
  }

  if (snapshot.objects.length > 0) {
    parts.push(`Objects: ${snapshot.objects.map(o => `${o.name} — ${o.description}`).join('; ')}`);
  }

  return parts.join('\n');
}

export function buildTrimmedContext(snapshot: RoomSnapshot): string {
  let s = `You are in ${snapshot.name}. `;
  if (snapshot.entities.length > 0) {
    s += `Present: ${snapshot.entities.map(e => e.name).join(', ')}.`;
  }
  return s;
}

export function buildRecencyAnchor(snapshot: RoomSnapshot, triggerEvent: Said | null): string {
  let s = `[Current state: ${snapshot.name}`;
  if (snapshot.entities.length > 0) {
    s += `, present: ${snapshot.entities.map(e => e.name).join(', ')}`;
  }
  if (triggerEvent) {
    s += `. Responding to ${triggerEvent.entityName}`;
  }
  s += ']';
  return s;
}

function formatSaidEvent(event: Said, selfEntityId: string): string {
  return event.entityId === selfEntityId ? event.text : `${event.entityName} says: ${event.text}`;
}

/**
 * Build Oracle prediction context for Layer 3.25 injection.
 * Mirrors server OracleAgentContext.build() and KMP buildOracleContext().
 */
export function buildOracleContext(predictions: PhonePrediction[]): string {
  const relevant = predictions
    .filter(p => p.confidence >= MIN_ORACLE_CONFIDENCE)
    .sort((a, b) => b.confidence - a.confidence)
    .slice(0, MAX_ORACLE_PREDICTIONS);
  if (relevant.length === 0) return '';

  let s = 'Oracle insights:';
  for (let i = 0; i < relevant.length; i++) {
    const p = relevant[i];
    s += ` (${i + 1}) ${p.text}`;
    if (p.actionable) s += ' [actionable]';
    s += '.';
  }
  return s;
}
