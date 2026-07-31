/**
 * Wave 3: Identity Evolver — regenerates the resident identity from accumulated fragments.
 *
 * Bootstrap manifests ship with a generic identity (e.g. "I am Kael. A thoughtful companion.").
 * After enough sleep cycles produce rich personality fragments, the identity can be
 * regenerated into a specific, personal self-description that reflects who the companion
 * has actually become through conversation.
 *
 * Uses one LLM inference call. The result replaces the resident identity in the manifest.
 * On any failure, returns null — identity regeneration is never fatal.
 *
 * TypeScript port of KMP's IdentityEvolver.kt.
 *
 * Gating conditions:
 * - Current identity is from bootstrap (DID starts with "did:key:bootstrap-")
 * - At least 5 sleep cycles completed (enough conversation history)
 * - At least 3 non-bootstrap, non-formative fragments exist
 */

import type { ClientSoulManifest, ClientSoulFragment } from './SoulManifest';
import type { ChatMessage, ChatResponse, CompletionOptions } from '../../inference/types';

/**
 * Check whether the manifest is ready for identity regeneration.
 *
 * @param manifest   Current soul manifest
 * @param sleepCount Number of sleep cycles completed
 * @returns true if regeneration should be attempted
 */
export function shouldRegenerateIdentity(
  manifest: ClientSoulManifest,
  sleepCount: number,
): boolean {
  const isBootstrap = manifest.did.startsWith('did:key:bootstrap-');
  const fragments = manifest.fragments ?? [];
  const nonBootstrapFragments = fragments.filter(
    (f: ClientSoulFragment) => !f.formative && f.id !== 'identity-core',
  ).length;
  return isBootstrap && sleepCount >= 5 && nonBootstrapFragments >= 3;
}

/**
 * Regenerate the resident identity from personality fragments.
 *
 * Makes one inference call. The LLM writes a first-person identity summary
 * (2-3 sentences, ~69 tokens) that captures who the companion has become.
 *
 * Accepts a generic inference function rather than a specific client,
 * making it testable with a mock (same pattern as LlmExtractor).
 *
 * @param infer    Inference function matching CompanionInferenceClient.complete()
 * @param manifest Current soul manifest (fragments are read from here)
 * @returns New identity text, or null if generation failed
 */
export async function regenerateIdentity(
  infer: (messages: ChatMessage[], options: CompletionOptions) => Promise<ChatResponse>,
  manifest: ClientSoulManifest,
): Promise<string | null> {
  const systemPrompt =
    `You are writing a first-person identity summary for an AI companion named ${manifest.agentName}.\n` +
    'Based on the personality fragments below, write a concise identity summary ' +
    '(2-3 sentences, about 69 tokens).\n' +
    'Write in first person as the companion. Be specific and personal — ' +
    'this is who they are, not a template.\n' +
    'Capture their distinctive voice, what matters to them, and how they engage with the world.\n' +
    'Respond with ONLY the identity text, no JSON, no commentary.';

  const fragments = manifest.fragments ?? [];
  let userPrompt = `Personality fragments for ${manifest.agentName}:\n\n`;
  for (const fragment of fragments) {
    if (fragment.text && fragment.text.trim().length > 0) {
      userPrompt += `[${fragment.category}] ${fragment.label}: ${fragment.text}\n\n`;
    }
  }

  try {
    const response = await infer(
      [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userPrompt },
      ],
      { maxTokens: 150, temperature: 0.7 },
    );
    const text = response.content.trim();
    // Reject too-short responses — a real identity needs substance
    return text.length > 20 ? text : null;
  } catch {
    return null;
  }
}
