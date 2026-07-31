/**
 * Codecs for aspect and reagent item JSON stored in PhoneSoulItem.text.
 * TypeScript port of AspectItemCodec.java and ReagentItemCodec.java.
 */

import type { PhoneSoulItem } from './PhoneSoulItem';

// ---------------------------------------------------------------------------
// Aspect
// ---------------------------------------------------------------------------

export interface AspectDefinition {
  version: number;
  promptOverlay: string | null;
  vitalityShifts: Record<string, number>;
  selfDescription: string | null;
  slotHint: string;
  tokenEstimate: number;
}

/**
 * Decode a PhoneSoulItem's text field into an AspectDefinition.
 * Returns null if the item is not a valid aspect.
 */
export function decodeAspect(item: PhoneSoulItem): AspectDefinition | null {
  if (!item || !item.text || item.category !== 'aspect') return null;
  return decodeAspectJson(item.text);
}

/**
 * Decode a raw JSON string into an AspectDefinition.
 */
export function decodeAspectJson(json: string): AspectDefinition | null {
  if (!json) return null;
  try {
    const obj = JSON.parse(json);
    return {
      version: obj.version ?? 1,
      promptOverlay: obj.promptOverlay ?? null,
      vitalityShifts: obj.vitalityShifts ?? {},
      selfDescription: obj.selfDescription ?? null,
      slotHint: obj.slotHint ?? 'garment',
      tokenEstimate: obj.tokenEstimate > 0 ? obj.tokenEstimate : 20,
    };
  } catch {
    return null;
  }
}

/**
 * Encode an AspectDefinition to a JSON string (for PhoneSoulItem.text).
 */
export function encodeAspect(def: AspectDefinition): string {
  return JSON.stringify(def);
}

/**
 * Create a new AspectDefinition with defaults applied.
 */
export function createAspectDefinition(
  promptOverlay: string | null,
  vitalityShifts: Record<string, number>,
  selfDescription: string | null,
  slotHint: string,
  tokenEstimate: number,
): AspectDefinition {
  return {
    version: 1,
    promptOverlay,
    vitalityShifts: { ...vitalityShifts },
    selfDescription,
    slotHint: slotHint || 'garment',
    tokenEstimate: tokenEstimate > 0 ? tokenEstimate : 20,
  };
}

/** Whether the aspect injects prompt text. */
export function aspectHasPromptOverlay(def: AspectDefinition): boolean {
  return def.promptOverlay != null && def.promptOverlay.trim().length > 0;
}

/** Whether the aspect modifies vitality baselines. */
export function aspectHasVitalityShifts(def: AspectDefinition): boolean {
  return Object.keys(def.vitalityShifts).length > 0;
}

// ---------------------------------------------------------------------------
// Reagent
// ---------------------------------------------------------------------------

export interface ReagentDefinition {
  version: number;
  vitalityEffects: Record<string, number>;
  durationTicks: number;
  promptOverlay: string | null;
  consumable: boolean;
  tokenEstimate: number;
}

/** Maximum allowed duration: 1800 ticks (~30 minutes). */
export const REAGENT_MAX_DURATION = 1800;

/**
 * Decode a PhoneSoulItem's text field into a ReagentDefinition.
 * Returns null if the item is not a valid reagent.
 */
export function decodeReagent(item: PhoneSoulItem): ReagentDefinition | null {
  if (!item || !item.text || item.category !== 'reagent') return null;
  return decodeReagentJson(item.text);
}

/**
 * Decode a raw JSON string into a ReagentDefinition.
 */
export function decodeReagentJson(json: string): ReagentDefinition | null {
  if (!json) return null;
  try {
    const obj = JSON.parse(json);
    return {
      version: obj.version ?? 1,
      vitalityEffects: obj.vitalityEffects ?? {},
      durationTicks: obj.durationTicks > 0 ? obj.durationTicks : 300,
      promptOverlay: obj.promptOverlay ?? null,
      consumable: obj.consumable ?? true,
      tokenEstimate: obj.tokenEstimate > 0 ? obj.tokenEstimate : 10,
    };
  } catch {
    return null;
  }
}

/**
 * Encode a ReagentDefinition to a JSON string (for PhoneSoulItem.text).
 */
export function encodeReagent(def: ReagentDefinition): string {
  return JSON.stringify(def);
}

/**
 * Create a new ReagentDefinition with defaults applied.
 */
export function createReagentDefinition(
  vitalityEffects: Record<string, number>,
  durationTicks: number,
  promptOverlay: string | null,
  consumable: boolean,
  tokenEstimate: number,
): ReagentDefinition {
  return {
    version: 1,
    vitalityEffects: { ...vitalityEffects },
    durationTicks: Math.min(durationTicks > 0 ? durationTicks : 300, REAGENT_MAX_DURATION),
    promptOverlay,
    consumable,
    tokenEstimate: tokenEstimate > 0 ? tokenEstimate : 10,
  };
}

/** Whether the reagent injects prompt text while active. */
export function reagentHasPromptOverlay(def: ReagentDefinition): boolean {
  return def.promptOverlay != null && def.promptOverlay.trim().length > 0;
}

/** Clamped duration (respects MAX_DURATION). */
export function reagentEffectiveDuration(def: ReagentDefinition): number {
  return Math.min(def.durationTicks, REAGENT_MAX_DURATION);
}
