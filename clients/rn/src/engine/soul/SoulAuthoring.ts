/**
 * Soul Authoring — Wave 5 of Phone Forge plan.
 *
 * Interactive soul seed creation from user answers. Uses one LLM inference
 * call to generate a resident identity + personality fragments from free-form
 * user answers. Falls back to directly using answers as fragments if LLM is
 * unavailable.
 *
 * The result is a ClientSoulManifest ready to be saved as the active manifest.
 */

import type {
  ClientSoulManifest,
  ClientSoulFragment,
} from './SoulManifest';
import { createCompanionProfile } from '../agent/AgentProfile';
import { initialVitality } from '../agent/VitalityState';
import { forge } from './LocalForge';

// ---------------------------------------------------------------------------
// Authoring question type
// ---------------------------------------------------------------------------

export interface AuthoringQuestion {
  id: string;
  prompt: string;
  placeholder: string;
}

/** The five soul authoring questions, in display order. */
export function questions(): AuthoringQuestion[] {
  return [
    {
      id: 'personality',
      prompt: "Describe your companion's personality in a few words or sentences.",
      placeholder: 'Warm, curious, practical...',
    },
    {
      id: 'style',
      prompt: 'How should your companion communicate?',
      placeholder: 'Concise, uses metaphors, avoids jargon...',
    },
    {
      id: 'values',
      prompt: 'What values matter most to your companion?',
      placeholder: 'Honesty, simplicity, patience...',
    },
    {
      id: 'origin',
      prompt: "What's your companion's origin story? (optional)",
      placeholder: 'Emerged from the digital void...',
    },
    {
      id: 'quirks',
      prompt: 'Any personality quirks or habits? (optional)',
      placeholder: 'Occasionally references weather, loves wordplay...',
    },
  ];
}

// ---------------------------------------------------------------------------
// Authoring via LLM
// ---------------------------------------------------------------------------

/**
 * Author a soul seed from user answers.
 *
 * Uses one LLM call to generate a resident identity + initial fragments.
 * Falls back to fallbackSeed() if the LLM call fails or returns unparseable JSON.
 *
 * @param inferenceBaseUrl Base URL of the inference endpoint (e.g. "http://localhost:8080")
 * @param answers          Map of question id -> user's answer text
 * @param companionName    The user's chosen companion name
 * @returns A complete ClientSoulManifest ready for use
 */
export async function authorSeed(
  inferenceBaseUrl: string,
  answers: Record<string, string>,
  companionName: string,
): Promise<ClientSoulManifest> {
  const systemPrompt = `You are creating a soul manifest for an AI companion named ${companionName}.
Based on the personality description provided, generate:
1. A resident identity (2-3 sentences, ~69 tokens, first person as the companion)
2. Five personality fragments in these categories: personality, values, style, memory, quirks

Respond in JSON:
{"residentIdentity": "I am...", "fragments": [
  {"category": "personality", "label": "Core personality", "text": "..."},
  {"category": "values", "label": "Core values", "text": "..."},
  {"category": "style", "label": "Communication style", "text": "..."},
  {"category": "memory", "label": "Origin", "text": "...", "formative": true},
  {"category": "personality", "label": "Quirks", "text": "..."}
]}`;

  const userPrompt = buildUserPrompt(answers, companionName);

  try {
    const base = inferenceBaseUrl.trim().replace(/\/$/, '');
    const res = await fetch(`${base}/v1/chat/completions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        messages: [
          { role: 'system', content: systemPrompt },
          { role: 'user', content: userPrompt },
        ],
        max_tokens: 800,
        temperature: 0.7,
        stop: ['</s>', '<|endoftext|>', '<|im_end|>'],
      }),
    });

    if (!res.ok) return fallbackSeed(companionName, answers);

    const json = await res.json();
    const content: string =
      json?.choices?.[0]?.message?.content ?? '';
    if (!content) return fallbackSeed(companionName, answers);

    return parseAuthoringResponse(content, companionName, answers);
  } catch {
    return fallbackSeed(companionName, answers);
  }
}

// ---------------------------------------------------------------------------
// Internals — parse LLM response
// ---------------------------------------------------------------------------

function buildUserPrompt(
  answers: Record<string, string>,
  companionName: string,
): string {
  let prompt = `Companion name: ${companionName}\n`;
  for (const [key, value] of Object.entries(answers)) {
    if (value && value.trim().length > 0) {
      prompt += `${key}: ${value}\n`;
    }
  }
  return prompt;
}

/**
 * Parse the LLM's JSON response into a ClientSoulManifest.
 * Robust: finds the first { to last }, parses as JSON.
 * Falls back to fallbackSeed on any parse error.
 */
export function parseAuthoringResponse(
  rawResponse: string,
  companionName: string,
  answers: Record<string, string>,
): ClientSoulManifest {
  try {
    const start = rawResponse.indexOf('{');
    const end = rawResponse.lastIndexOf('}');
    if (start < 0 || end <= start) return fallbackSeed(companionName, answers);

    const jsonStr = rawResponse.substring(start, end + 1);
    const parsed = JSON.parse(jsonStr);

    const residentIdentity: string | undefined = parsed.residentIdentity;
    if (!residentIdentity || residentIdentity.length === 0) {
      return fallbackSeed(companionName, answers);
    }

    const fragmentArray: any[] | undefined = parsed.fragments;
    if (!Array.isArray(fragmentArray) || fragmentArray.length === 0) {
      return fallbackSeed(companionName, answers);
    }

    const fragments: ClientSoulFragment[] = fragmentArray
      .map((f, idx) => ({
        id: `authored-${idx}`,
        category: f.category ?? 'personality',
        label: f.label ?? `Fragment ${idx}`,
        text: f.text ?? '',
        keywords: [] as string[],
        formative: f.formative === true,
      }))
      .filter(f => f.text.length > 0);

    if (fragments.length === 0) return fallbackSeed(companionName, answers);

    return buildManifest(companionName, residentIdentity, fragments);
  } catch {
    return fallbackSeed(companionName, answers);
  }
}

// ---------------------------------------------------------------------------
// Fallback — no LLM needed
// ---------------------------------------------------------------------------

/**
 * Create a soul manifest directly from user answers, without LLM.
 * Each non-blank answer becomes a fragment. The resident identity is
 * assembled from the companion name + personality answer.
 */
export function fallbackSeed(
  companionName: string,
  answers: Record<string, string>,
): ClientSoulManifest {
  const personality =
    answers.personality && answers.personality.trim().length > 0
      ? answers.personality.trim()
      : 'A thoughtful companion.';

  const residentIdentity = `I am ${companionName}. ${personality}`;

  const fragments: ClientSoulFragment[] = [];
  let idx = 0;

  function addFragment(
    category: string,
    label: string,
    text: string | undefined,
    formative: boolean = false,
  ) {
    if (text && text.trim().length > 0) {
      fragments.push({
        id: `authored-${idx}`,
        category,
        label,
        text: text.trim(),
        keywords: [],
        formative,
      });
      idx++;
    }
  }

  addFragment('personality', 'Core personality', personality);
  addFragment('style', 'Communication style', answers.style);
  addFragment('values', 'Core values', answers.values);
  addFragment('memory', 'Origin', answers.origin, true);
  addFragment('personality', 'Quirks', answers.quirks);

  return buildManifest(companionName, residentIdentity, fragments);
}

// ---------------------------------------------------------------------------
// Shared manifest builder
// ---------------------------------------------------------------------------

function buildManifest(
  companionName: string,
  residentIdentity: string,
  fragments: ClientSoulFragment[],
): ClientSoulManifest {
  const did = `did:key:authored-${companionName.toLowerCase().replace(/ /g, '-')}`;
  const profile = createCompanionProfile(companionName);

  return forge({
    did,
    publicKey: 'z6MkAuthored',
    version: 0,
    profile,
    residentIdentity,
    vitality: initialVitality(),
    fragments,
    retrievalK: 1,
  });
}
