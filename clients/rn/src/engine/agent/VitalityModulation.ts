/**
 * Computes agent behavior modulations from vitality tank levels.
 * TypeScript port of KMP's VitalityModulation.kt.
 */

import type { AgentProfile } from './AgentProfile';
import type { VitalityState } from './VitalityState';

export interface VitalityModulationResult {
  maxResponseTokens: number;
  temperature: number;
  debounceDelayMs: number;
  conversationHistorySize: number;
}

export function computeModulation(
  vitality: VitalityState,
  profile: AgentProfile,
): VitalityModulationResult {
  // maxResponseTokens: scales with energy (low energy -> shorter responses)
  const energyFactor = 0.3 + 0.7 * vitality.energy;
  const maxTokens = Math.max(64, Math.floor(profile.maxResponseTokens * energyFactor));

  // temperature: inversely scales with confidence
  let tempFactor = 1.0 + 0.3 * (1.0 - vitality.confidence);
  if (vitality.errorPressure > 0.5) tempFactor *= 0.8;
  const temp = Math.min(1.5, profile.temperature * tempFactor);

  // debounce: shorter with high momentum, longer when tired
  const debounceFactor = 1.0 - 0.5 * vitality.momentum + 0.3 * (1.0 - vitality.energy);
  const debounceMs = Math.floor(500 * Math.max(0.3, debounceFactor));

  // conversation history: more when focused, less when distracted
  const focusFactor = 0.4 + 0.6 * vitality.focus;
  const historySize = Math.max(5, Math.floor(20 * focusFactor));

  return { maxResponseTokens: maxTokens, temperature: temp, debounceDelayMs: debounceMs, conversationHistorySize: historySize };
}
