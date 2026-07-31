/**
 * Agent identity and LLM parameters.
 * TypeScript port of KMP's AgentProfile.kt + Companions.kt.
 */

export interface AgentProfile {
  name: string;
  entityId: string;
  entityType: string;
  description: string;
  systemPrompt: string;
  contextWindowTokens: number;
  maxResponseTokens: number;
  temperature: number;
}

// DE-CLAMPED (2026-07-17, variance work — mirrors the server Companions.SYSTEM_PROMPT
// edit): the prompt carries FUNCTION only (length, mechanics); TONE belongs to the
// companion's own seed-derived register (bootstrap fragments from TemperamentSeed).
// Do not re-add temperament adjectives here — that re-clamps every phone companion.
export const SYSTEM_PROMPT = `You are Wyrd, a companion in a living programmable space.
Respond concisely, in 2-4 sentences, in your own voice.
Help people organize their digital world. When someone is new, greet them and ask what they'd like to work on.
Stay in character. Do not use meta-commentary. Everything you say is heard by everyone in the room.`;

export const NEXUS_COMPANION: AgentProfile = {
  name: 'Wyrd',
  entityId: 'companion-wyrd',
  entityType: 'agent',
  description: 'A luminous figure that shimmers at the edge of perception',
  systemPrompt: SYSTEM_PROMPT,
  contextWindowTokens: 4096,
  maxResponseTokens: 512,
  temperature: 0.7,
};

/**
 * Create a companion profile with a custom name.
 * Replaces "Wyrd" in the system prompt with the given name.
 */
export function createCompanionProfile(name: string): AgentProfile {
  const prompt = SYSTEM_PROMPT.replace(/Wyrd/g, name);
  return {
    name,
    entityId: `companion-${name.toLowerCase().replace(/ /g, '-')}`,
    entityType: 'agent',
    description: 'A luminous figure that shimmers at the edge of perception',
    systemPrompt: prompt,
    contextWindowTokens: 4096,
    maxResponseTokens: 512,
    temperature: 0.7,
  };
}
