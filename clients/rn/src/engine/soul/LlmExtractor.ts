/**
 * Wave 2 LLM extraction — enriches a heuristic PhoneFingerprint with
 * topic affinities, stylistic markers, and emotional patterns.
 *
 * Makes one inference call during sleep. On any failure, returns the
 * fingerprint unchanged (extraction is best-effort, never fatal).
 *
 * TypeScript port of KMP's LlmExtractor.kt.
 * Phone port of core/soul/BehavioralExtractor pass2 logic.
 */

import type { PhoneFingerprint } from './PhoneFingerprint';
import type { WorldEvent } from '../events/WorldEvent';
import type { ChatMessage, ChatResponse, CompletionOptions } from '../../inference/types';

export interface LlmExtractionResult {
  topicAffinities: Record<string, number>;
  stylisticMarkers: string[];
  emotionalPatterns: Record<string, number>;
}

function emptyResult(): LlmExtractionResult {
  return {
    topicAffinities: {},
    stylisticMarkers: [],
    emotionalPatterns: {},
  };
}

/**
 * Run LLM extraction on accumulated events to enrich a heuristic fingerprint.
 * Makes one inference call. On any failure, returns the fingerprint unchanged.
 *
 * Accepts a generic inference function rather than a specific client,
 * making it testable with a mock.
 *
 * @param infer       Inference function matching CompanionInferenceClient.complete()
 * @param fingerprint Heuristic fingerprint from Wave 1
 * @param events      WorldEvents since last sleep
 * @param agentName   Display name of the companion
 */
export async function extractWithLlm(
  infer: (messages: ChatMessage[], options: CompletionOptions) => Promise<ChatResponse>,
  fingerprint: PhoneFingerprint,
  events: WorldEvent[],
  agentName: string,
): Promise<PhoneFingerprint> {
  const messages = buildExtractionPrompt(fingerprint, events, agentName);
  try {
    const response = await infer(messages, { maxTokens: 500, temperature: 0.3 });
    const result = parseExtractionResponse(response.content);
    return mergeWithHeuristic(fingerprint, result);
  } catch {
    return fingerprint; // Return unchanged on failure
  }
}

/**
 * Build system + user messages for the LLM extraction call.
 * System prompt instructs structured JSON output; user prompt includes
 * heuristic summary and recent Said events (capped at 30).
 */
export function buildExtractionPrompt(
  fingerprint: PhoneFingerprint,
  events: WorldEvent[],
  agentName: string,
): ChatMessage[] {
  const systemPrompt =
    `You are analyzing conversation patterns for an AI companion named ${agentName}.\n` +
    'Based on the behavioral data and conversation excerpts below, extract:\n\n' +
    '1. topicAffinities: topics the companion gravitates toward, with weights 0.0-1.0\n' +
    '2. stylisticMarkers: distinctive speech patterns, word choices, or habits (list of strings)\n' +
    '3. emotionalPatterns: how the companion responds to different emotions, ' +
    'with responsiveness weights 0.0-1.0\n\n' +
    'Respond ONLY with a JSON object:\n' +
    '{"topicAffinities": {"topic": 0.8, ...}, ' +
    '"stylisticMarkers": ["marker1", ...], ' +
    '"emotionalPatterns": {"emotion": 0.7, ...}}';

  const maxEvents = 30;
  const recentEvents = events.slice(-maxEvents);

  const actionDistStr = JSON.stringify(fingerprint.actionDistribution);
  const topicsStr = fingerprint.topicKeywords.join(', ');

  let userPrompt = '## Behavioral Summary\n';
  userPrompt += `Action distribution: ${actionDistStr}\n`;
  userPrompt += `Average response length: ${fingerprint.averageResponseLength} words\n`;
  userPrompt += `Average response latency: ${fingerprint.averageLatency}s\n`;
  userPrompt += `Top topics: ${topicsStr}\n`;
  userPrompt += `\n## Recent Conversation (last ${recentEvents.length} events)\n`;

  for (const event of recentEvents) {
    if (event.type === 'said') {
      userPrompt += `${event.entityName}: ${event.text}\n`;
    }
  }

  return [
    { role: 'system', content: systemPrompt },
    { role: 'user', content: userPrompt },
  ];
}

/**
 * Parse LLM response into structured extraction result.
 * Robust: finds first '{' to last '}', JSON.parse, extracts known fields.
 * On ANY failure at any step, returns empty LlmExtractionResult.
 */
export function parseExtractionResponse(response: string): LlmExtractionResult {
  try {
    const openIdx = response.indexOf('{');
    const closeIdx = response.lastIndexOf('}');
    if (openIdx < 0 || closeIdx < 0 || closeIdx <= openIdx) {
      return emptyResult();
    }

    const jsonStr = response.substring(openIdx, closeIdx + 1);
    const parsed = JSON.parse(jsonStr);

    const topicAffinities: Record<string, number> = {};
    if (parsed.topicAffinities && typeof parsed.topicAffinities === 'object') {
      for (const [key, val] of Object.entries(parsed.topicAffinities)) {
        if (typeof val === 'number') {
          topicAffinities[key] = val;
        }
      }
    }

    const stylisticMarkers: string[] = [];
    if (Array.isArray(parsed.stylisticMarkers)) {
      for (const item of parsed.stylisticMarkers) {
        if (typeof item === 'string') {
          stylisticMarkers.push(item);
        }
      }
    }

    const emotionalPatterns: Record<string, number> = {};
    if (parsed.emotionalPatterns && typeof parsed.emotionalPatterns === 'object') {
      for (const [key, val] of Object.entries(parsed.emotionalPatterns)) {
        if (typeof val === 'number') {
          emotionalPatterns[key] = val;
        }
      }
    }

    return { topicAffinities, stylisticMarkers, emotionalPatterns };
  } catch {
    return emptyResult();
  }
}

/**
 * Merge LLM extraction results into the heuristic fingerprint.
 * Overwrites the LLM-derived fields; heuristic fields are preserved.
 */
export function mergeWithHeuristic(
  fingerprint: PhoneFingerprint,
  llmResult: LlmExtractionResult,
): PhoneFingerprint {
  return {
    ...fingerprint,
    topicAffinities: llmResult.topicAffinities,
    stylisticMarkers: llmResult.stylisticMarkers,
    emotionalPatterns: llmResult.emotionalPatterns,
  };
}
